package com.phucnt.mytranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks translated text aloud using Android's built-in Text-to-Speech.
 * Pre-warms on init, supports a configurable speech rate, can switch the spoken
 * language per utterance (for two-way conversations), and reports when it is
 * actively speaking so the caller can mute the mic (half-duplex echo control).
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private var pendingLocale: Locale? = null
    private var rate = 1.0f
    private var currentLang = ""
    // Utterances requested before async init finished — flushed once ready.
    private val pending = mutableListOf<Pair<String, String?>>()
    private val speaking = AtomicInteger(0)

    @Volatile var enabled = false

    /** Invoked (off the main thread) when speech starts (true) / fully drains (false). */
    @Volatile var onSpeakingChanged: ((Boolean) -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (speaking.getAndIncrement() == 0) onSpeakingChanged?.invoke(true)
            }
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()

            private fun finish() {
                if (speaking.decrementAndGet() <= 0) {
                    speaking.set(0)
                    onSpeakingChanged?.invoke(false)
                }
            }
        })
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setSpeechRate(rate)
            pendingLocale?.let { applyLocale(it) }
            val backlog = synchronized(pending) {
                val copy = pending.toList(); pending.clear(); copy
            }
            backlog.forEach { (text, lang) -> speakNow(text, lang) }
        }
    }

    fun setLanguageByCode(code: String) {
        val locale = localeFor(code)
        if (ready) { applyLocale(locale); currentLang = code } else pendingLocale = locale
    }

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 2.0f)
        if (ready) tts.setSpeechRate(rate)
    }

    private fun applyLocale(locale: Locale) {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("TtsManager", "TTS language not available: $locale")
        }
    }

    /** Speak [text]; if [langCode] is given, switch the voice to that language first. */
    fun speak(text: String, langCode: String? = null) {
        if (!enabled || text.isBlank()) return
        if (!ready) {
            synchronized(pending) { pending.add(text to langCode) }
            return
        }
        speakNow(text, langCode)
    }

    private fun speakNow(text: String, langCode: String?) {
        if (!langCode.isNullOrEmpty() && langCode != currentLang) {
            applyLocale(localeFor(langCode))
            currentLang = langCode
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tr-${System.nanoTime()}")
    }

    fun stop() {
        if (ready) tts.stop()
        if (speaking.getAndSet(0) > 0) onSpeakingChanged?.invoke(false)
    }

    fun shutdown() {
        onSpeakingChanged = null
        tts.stop()
        tts.shutdown()
    }

    private fun localeFor(code: String): Locale = when (code) {
        "zh" -> Locale.CHINA
        "yue" -> Locale.TRADITIONAL_CHINESE
        "ja" -> Locale.JAPAN
        "ko" -> Locale.KOREA
        else -> Locale.forLanguageTag(code)
    }
}
