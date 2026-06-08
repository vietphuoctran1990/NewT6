package com.phucnt.mytranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phucnt.mytranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsManager

    private var soniox: SonioxClient? = null
    private var audio: AudioCapture? = null
    private var running = false

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTranslating() else setStatus("Microphone permission is required to translate.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        tts = TtsManager(this)

        setupSpinners()
        restoreState()

        b.startStopButton.setOnClickListener { if (running) stopTranslating() else onStartPressed() }
        b.clearButton.setOnClickListener { clearTranscript() }
        b.ttsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.ttsEnabled = checked
            tts.enabled = checked
            if (!checked) tts.stop()
        }
    }

    private fun setupSpinners() {
        b.sourceSpinner.adapter = languageAdapter(Languages.source.map { it.name })
        b.targetSpinner.adapter = languageAdapter(Languages.target.map { it.name })

        b.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.sourceLang = Languages.source[pos].code
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val code = Languages.target[pos].code
                prefs.targetLang = code
                tts.setLanguageByCode(code)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun languageAdapter(names: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun restoreState() {
        b.apiKeyInput.setText(prefs.apiKey)
        b.sourceSpinner.setSelection(Languages.indexOfCode(Languages.source, prefs.sourceLang))
        b.targetSpinner.setSelection(Languages.indexOfCode(Languages.target, prefs.targetLang))
        b.ttsSwitch.isChecked = prefs.ttsEnabled
        tts.enabled = prefs.ttsEnabled
        tts.setLanguageByCode(prefs.targetLang)
    }

    private fun onStartPressed() {
        prefs.apiKey = b.apiKeyInput.text.toString().trim()
        if (prefs.apiKey.isBlank()) {
            setStatus("Enter your Soniox API key first.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startTranslating()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startTranslating() {
        if (running) return
        running = true
        setControlsRunning(true)

        val config = SonioxClient.Config(
            apiKey = prefs.apiKey,
            sourceLanguage = prefs.sourceLang,
            targetLanguage = prefs.targetLang,
        )

        val client = SonioxClient(
            onStatus = { status -> runOnUiThread { onStatus(status) } },
            onOriginal = { text -> runOnUiThread { appendSource(text) } },
            onTranslation = { text ->
                runOnUiThread { appendTranslation(text) }
                tts.speak(text)
            },
            onProvisional = { text -> runOnUiThread { b.provisionalText.text = text } },
            onError = { msg -> runOnUiThread { setStatus(msg) } },
        )
        soniox = client
        client.connect(config)

        val capture = AudioCapture(
            onChunk = { chunk -> client.sendAudio(chunk) },
            onError = { msg -> runOnUiThread { setStatus(msg); stopTranslating() } },
        )
        audio = capture
        capture.start()
    }

    private fun stopTranslating() {
        if (!running) return
        running = false
        audio?.stop()
        audio = null
        soniox?.disconnect()
        soniox = null
        tts.stop()
        setControlsRunning(false)
        b.provisionalText.text = ""
        setStatus("Stopped.")
    }

    private fun onStatus(status: SonioxClient.Status) {
        when (status) {
            SonioxClient.Status.CONNECTING -> setStatus("Connecting…")
            SonioxClient.Status.CONNECTED -> setStatus(getString(R.string.waiting))
            SonioxClient.Status.DISCONNECTED -> setStatus("Disconnected.")
            SonioxClient.Status.ERROR -> {} // message comes through onError
        }
    }

    private fun appendTranslation(text: String) {
        appendTo(b.translationText, text)
        scrollToBottom()
    }

    private fun appendSource(text: String) {
        appendTo(b.sourceText, text)
        scrollToBottom()
    }

    private fun appendTo(view: android.widget.TextView, text: String) {
        val existing = view.text?.toString().orEmpty()
        val sep = if (existing.isEmpty() || existing.endsWith(" ")) "" else " "
        view.text = existing + sep + text.trim()
    }

    private fun clearTranscript() {
        b.translationText.text = ""
        b.sourceText.text = ""
        b.provisionalText.text = ""
    }

    private fun scrollToBottom() {
        b.scrollView.post {
            if (isAtBottomZone()) b.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Only auto-scroll when the user is already near the bottom (smart scroll). */
    private fun isAtBottomZone(): Boolean {
        val child = b.scrollView.getChildAt(0) ?: return true
        val diff = child.bottom - (b.scrollView.height + b.scrollView.scrollY)
        return diff <= child.height // generous threshold; jumps to bottom in normal use
    }

    private fun setControlsRunning(isRunning: Boolean) {
        b.startStopButton.text = getString(if (isRunning) R.string.stop else R.string.start)
        b.apiKeyInput.isEnabled = !isRunning
        b.sourceSpinner.isEnabled = !isRunning
        b.targetSpinner.isEnabled = !isRunning
    }

    private fun setStatus(text: String) {
        b.statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopTranslating()
        tts.shutdown()
    }
}
