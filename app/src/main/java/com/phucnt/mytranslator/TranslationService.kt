package com.phucnt.mytranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that runs the whole translation pipeline so it keeps going
 * while the user switches to other apps. It owns the audio capture, the chosen
 * engine (Soniox / OpenAI), voice output (TTS or native audio), and the floating
 * subtitle overlay.
 */
class TranslationService : Service(), EngineListener {

    companion object {
        const val ACTION_START = "com.phucnt.mytranslator.START"
        const val ACTION_STOP = "com.phucnt.mytranslator.STOP"
        const val ACTION_UPDATE = "com.phucnt.mytranslator.UPDATE"
        const val ACTION_CLEAR = "com.phucnt.mytranslator.CLEAR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "translation"
        private const val NOTIF_ID = 1
        // Wait a moment after speech ends before re-opening the mic, so the tail
        // of the spoken translation isn't recaptured (half-duplex, two-way).
        private const val MIC_RESUME_DELAY_MS = 350L
    }

    private val main = Handler(Looper.getMainLooper())

    private var engine: TranslationEngine? = null
    private var audio: AudioCapture? = null
    private var tts: TtsManager? = null
    private var player: AudioTrackPlayer? = null
    private var overlay: OverlayController? = null
    private var projection: MediaProjection? = null

    private val sourceTranscript = StringBuilder()
    private val translationSegments = mutableListOf<String>()
    private var provSource = ""
    private var provTranslation = ""
    private var lastSourceSegment = ""

    private var ttsForVoice = false // Soniox: speak via TTS. OpenAI: native audio instead.
    private var refiner: DeepSeekRefiner? = null
    private var twoWayActive = false

    @Volatile private var pipelineActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_UPDATE -> if (pipelineActive) applyLiveSettings() else stopSelf()
            ACTION_CLEAR -> if (pipelineActive) clearTranscript() else stopSelf()
            else -> {
                if (!pipelineActive) { // ignore duplicate starts (double-tap, re-delivery)
                    startForegroundNotification(intent)
                    startPipeline(intent)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startPipeline(intent: Intent?) {
        val prefs = Prefs(this)
        pipelineActive = true
        sourceTranscript.clear()
        synchronized(translationSegments) { translationSegments.clear() }
        provSource = ""; provTranslation = ""; lastSourceSegment = ""

        val useOpenAi = prefs.engine == Prefs.ENGINE_OPENAI
        val voiceOn = prefs.ttsEnabled
        // Two-way (bilingual conversation) is Soniox-only and always uses the mic.
        val twoWay = !useOpenAi && prefs.translationMode == Prefs.MODE_TWO_WAY
        twoWayActive = twoWay

        // Build engine.
        engine = if (useOpenAi) {
            ttsForVoice = false
            if (voiceOn) player = AudioTrackPlayer().also { it.start() }
            OpenAiRealtimeClient(
                OpenAiRealtimeClient.Config(
                    apiKey = prefs.openAiKey,
                    targetLanguage = prefs.targetLang,
                    wantAudio = voiceOn,
                ),
                this
            )
        } else {
            ttsForVoice = voiceOn
            tts = TtsManager(this).also { // always created: TTS can be toggled on live
                it.enabled = voiceOn
                it.setRate(prefs.ttsRatePercent / 100f)
                if (twoWay) {
                    // Half-duplex: mute the mic while the translation is spoken so it
                    // isn't recaptured and translated back. Language is set per segment.
                    it.onSpeakingChanged = { sp ->
                        if (sp) audio?.setMuted(true)
                        else main.postDelayed({ audio?.setMuted(false) }, MIC_RESUME_DELAY_MS)
                    }
                } else {
                    it.setLanguageByCode(prefs.targetLang)
                }
            }
            if (!twoWay && prefs.deepSeekRefine && prefs.deepSeekKey.isNotBlank()) {
                refiner = DeepSeekRefiner(
                    apiKey = prefs.deepSeekKey,
                    targetLanguageName = Languages.nameForCode(prefs.targetLang),
                    glossary = prefs.glossary,
                )
            }
            SonioxClient(
                SonioxClient.Config(
                    apiKey = prefs.apiKey,
                    sourceLanguage = prefs.sourceLang,
                    targetLanguage = prefs.targetLang,
                    endpointDelayMs = prefs.endpointDelayMs,
                    twoWay = twoWay,
                    langA = prefs.langA,
                    langB = prefs.langB,
                ),
                this
            )
        }

        // Resolve audio source (mic or system playback capture). Two-way is mic-only.
        val source = if (!twoWay && prefs.audioSource == Prefs.SOURCE_SYSTEM)
            AudioCapture.Source.SYSTEM else AudioCapture.Source.MIC

        if (source == AudioCapture.Source.SYSTEM) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (data == null) {
                onError("System audio permission missing")
                stopEverything(); return
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data).also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stopEverything() }
                }, main)
            }
        }

        // Overlay.
        if (prefs.overlayEnabled && canDrawOverlay()) {
            overlay = OverlayController(this) { stopEverything() }.also { it.show() }
        }

        val eng = engine!!
        audio = AudioCapture(
            source = source,
            sampleRate = eng.sampleRate,
            mediaProjection = projection,
            onChunk = { eng.sendAudio(it) },
            // Capture errors are fatal (init failures) — stop, keeping the message.
            onError = { msg -> onError(msg); main.post { stopEverything() } },
        )

        EngineBus.publish(EngineBus.State(running = true, status = "connecting", twoWay = twoWay))
        eng.connect()
        audio?.start()
    }

    /** Re-read prefs and apply what can change mid-session (voice, speed, overlay). */
    private fun applyLiveSettings() {
        val prefs = Prefs(this)

        if (engine is SonioxClient) {
            ttsForVoice = prefs.ttsEnabled
            tts?.enabled = prefs.ttsEnabled
            tts?.setRate(prefs.ttsRatePercent / 100f)
            if (!prefs.ttsEnabled) tts?.stop()
        } else {
            // OpenAI: native voice can be muted live (chunks already stream).
            player?.setMuted(!prefs.ttsEnabled)
        }

        if (prefs.overlayEnabled && overlay == null && canDrawOverlay()) {
            overlay = OverlayController(this) { stopEverything() }.also { it.show() }
        } else if (!prefs.overlayEnabled && overlay != null) {
            overlay?.hide(); overlay = null
        }
    }

    private fun clearTranscript() {
        sourceTranscript.clear()
        synchronized(translationSegments) { translationSegments.clear() }
        provSource = ""; provTranslation = ""; lastSourceSegment = ""
        publishText()
    }

    private fun stopEverything() {
        pipelineActive = false
        audio?.stop(); audio = null
        engine?.disconnect(); engine = null
        tts?.shutdown(); tts = null
        player?.stop(); player = null
        overlay?.hide(); overlay = null
        projection?.stop(); projection = null
        refiner?.shutdown(); refiner = null
        // Keep transcript and any error message visible after stopping.
        EngineBus.update { it.copy(running = false, status = "stopped") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pipelineActive) stopEverything()
    }

    // ─── EngineListener ──────────────────────────────────────────────

    override fun onStatus(status: String) {
        // A successful (re)connect supersedes any earlier transient error.
        EngineBus.update {
            it.copy(status = status, error = if (status == "connected") null else it.error)
        }
        // "error" (fatal API error / reconnects exhausted) and an engine-initiated
        // "disconnected" are terminal: shut down so the mic isn't left recording
        // into a dead session. The error message set via onError survives
        // stopEverything(). During an intentional stop, pipelineActive is already
        // false, so the posted call is a no-op.
        if (status == "error" || status == "disconnected") {
            main.post { if (pipelineActive) stopEverything() }
        }
    }

    override fun onSourceText(text: String, isFinal: Boolean) {
        if (isFinal) {
            appendWithSpace(sourceTranscript, text)
            lastSourceSegment = text
            provSource = ""
        } else {
            provSource = text
        }
        publishText()
    }

    override fun onTranslationText(text: String, isFinal: Boolean) {
        if (!isFinal) {
            provTranslation = text
            overlay?.update("", text)
            publishText()
            return
        }
        provTranslation = ""
        val r = refiner
        if (r == null) {
            synchronized(translationSegments) { translationSegments.add(text) }
            if (ttsForVoice) tts?.speak(text)
            overlay?.update(text, "")
            publishText()
            return
        }
        // Refine: show Soniox's draft instantly, then upgrade the segment in place
        // (and speak the improved version) when DeepSeek returns.
        val index = synchronized(translationSegments) {
            translationSegments.add(text); translationSegments.size - 1
        }
        overlay?.update(text, "")
        publishText()
        r.refine(lastSourceSegment, text) { improved ->
            main.post {
                if (!pipelineActive) return@post
                synchronized(translationSegments) {
                    if (index < translationSegments.size) translationSegments[index] = improved
                }
                if (ttsForVoice) tts?.speak(improved)
                overlay?.update(improved, "")
                publishText()
            }
        }
    }

    override fun onSegment(text: String, language: String, isTranslation: Boolean, isFinal: Boolean) {
        if (!isFinal) {
            provTranslation = text
            overlay?.update("", text)
            publishText()
            return
        }
        val label = language.uppercase()
        // Original line, then its translation indented underneath, forming a chat log.
        val line = if (isTranslation) "   ↳ $label  $text" else "▸ $label  $text"
        synchronized(translationSegments) { translationSegments.add(line) }
        provTranslation = ""
        overlay?.update("$label  $text", "")
        if (isTranslation && ttsForVoice) tts?.speak(text, language) // speak in the target side's language
        publishText()
    }

    override fun onAudioChunk(pcmBase64: String) {
        player?.push(pcmBase64)
    }

    override fun onError(message: String) = EngineBus.update { it.copy(error = message) }

    private fun publishText() {
        val sep = if (twoWayActive) "\n" else " "
        val translation = synchronized(translationSegments) { translationSegments.joinToString(sep) }
        EngineBus.update {
            it.copy(
                source = sourceTranscript.toString(),
                translation = translation,
                provisionalSource = provSource,
                provisionalTranslation = provTranslation,
                twoWay = twoWayActive,
            )
        }
    }

    private fun appendWithSpace(sb: StringBuilder, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
        sb.append(t)
    }

    // ─── Foreground notification ─────────────────────────────────────

    private fun startForegroundNotification(intent: Intent?) {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        val prefs = Prefs(this)
        // Two-way forces the mic, so don't declare a media-projection FGS type
        // (Android 14+ requires a live projection token for that type).
        val twoWay = prefs.engine == Prefs.ENGINE_SONIOX && prefs.translationMode == Prefs.MODE_TWO_WAY
        val isSystem = !twoWay && prefs.audioSource == Prefs.SOURCE_SYSTEM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (isSystem)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun canDrawOverlay(): Boolean =
        android.provider.Settings.canDrawOverlays(this)
}
