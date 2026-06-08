package com.phucnt.mytranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phucnt.mytranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var overlayRequested = false
    private var notifRequested = false

    // ── Permission / projection launchers ──────────────────────────────
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { tryStart() }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tryStart() }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryStart() else setStatus("Microphone permission is required.")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startServiceSystem(result.resultCode, data)
            } else {
                setStatus("System audio capture was not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        setupSpinners()
        restoreState()

        b.startStopButton.setOnClickListener {
            if (EngineBus.state.running) stopService() else onStartPressed()
        }
        b.clearButton.setOnClickListener {
            b.translationText.text = ""; b.provisionalText.text = ""; b.sourceText.text = ""
        }
        b.ttsSwitch.setOnCheckedChangeListener { _, c -> prefs.ttsEnabled = c }
        b.overlaySwitch.setOnCheckedChangeListener { _, c -> prefs.overlayEnabled = c }
        b.refineSwitch.setOnCheckedChangeListener { _, c -> prefs.deepSeekRefine = c }
        b.ttsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.ttsRatePercent = 50 + p
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        EngineBus.listener = { state -> runOnUiThread { render(state) } }
        render(EngineBus.state)
    }

    override fun onPause() {
        super.onPause()
        EngineBus.listener = null
    }

    // ── Setup / restore ────────────────────────────────────────────────

    private fun setupSpinners() {
        b.engineSpinner.adapter = adapter(
            listOf(getString(R.string.engine_soniox), getString(R.string.engine_openai))
        )
        b.sourceAudioSpinner.adapter = adapter(
            listOf(getString(R.string.source_mic), getString(R.string.source_system))
        )
        b.sourceSpinner.adapter = adapter(Languages.source.map { it.name })
        b.targetSpinner.adapter = adapter(Languages.target.map { it.name })

        b.engineSpinner.onItemSelectedListener = onSelect { pos ->
            prefs.engine = if (pos == 1) Prefs.ENGINE_OPENAI else Prefs.ENGINE_SONIOX
        }
        b.sourceAudioSpinner.onItemSelectedListener = onSelect { pos ->
            prefs.audioSource = if (pos == 1) Prefs.SOURCE_SYSTEM else Prefs.SOURCE_MIC
        }
        b.sourceSpinner.onItemSelectedListener = onSelect { pos ->
            prefs.sourceLang = Languages.source[pos].code
        }
        b.targetSpinner.onItemSelectedListener = onSelect { pos ->
            prefs.targetLang = Languages.target[pos].code
        }
    }

    private fun restoreState() {
        b.sonioxKeyInput.setText(prefs.apiKey)
        b.openaiKeyInput.setText(prefs.openAiKey)
        b.deepseekKeyInput.setText(prefs.deepSeekKey)
        b.glossaryInput.setText(prefs.glossary)
        b.refineSwitch.isChecked = prefs.deepSeekRefine
        b.engineSpinner.setSelection(if (prefs.engine == Prefs.ENGINE_OPENAI) 1 else 0)
        b.sourceAudioSpinner.setSelection(if (prefs.audioSource == Prefs.SOURCE_SYSTEM) 1 else 0)
        b.sourceSpinner.setSelection(Languages.indexOfCode(Languages.source, prefs.sourceLang))
        b.targetSpinner.setSelection(Languages.indexOfCode(Languages.target, prefs.targetLang))
        b.ttsSwitch.isChecked = prefs.ttsEnabled
        b.overlaySwitch.isChecked = prefs.overlayEnabled
        b.ttsSpeed.progress = (prefs.ttsRatePercent - 50).coerceIn(0, 150)
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun onSelect(onPos: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPos(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    // ── Start flow ─────────────────────────────────────────────────────

    private fun onStartPressed() {
        prefs.apiKey = b.sonioxKeyInput.text.toString().trim()
        prefs.openAiKey = b.openaiKeyInput.text.toString().trim()
        prefs.deepSeekKey = b.deepseekKeyInput.text.toString().trim()
        prefs.glossary = b.glossaryInput.text.toString().trim()

        val keyOk = if (prefs.engine == Prefs.ENGINE_OPENAI) prefs.openAiKey.isNotBlank()
        else prefs.apiKey.isNotBlank()
        if (!keyOk) {
            setStatus("Enter the API key for the selected engine.")
            return
        }
        overlayRequested = false
        notifRequested = false
        tryStart()
    }

    /** Re-entrant: requests one missing prerequisite at a time, then launches. */
    private fun tryStart() {
        if (prefs.overlayEnabled && !Settings.canDrawOverlays(this) && !overlayRequested) {
            overlayRequested = true
            setStatus("Grant ‘display over other apps’, then press Start again.")
            overlayLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && !notifRequested
        ) {
            notifRequested = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (prefs.audioSource == Prefs.SOURCE_MIC &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (prefs.audioSource == Prefs.SOURCE_SYSTEM) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
            return
        }
        startServiceMic()
    }

    private fun startServiceMic() {
        val intent = Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Starting…")
    }

    private fun startServiceSystem(resultCode: Int, data: Intent) {
        val intent = Intent(this, TranslationService::class.java)
            .setAction(TranslationService.ACTION_START)
            .putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Starting…")
    }

    private fun stopService() {
        val intent = Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP)
        startService(intent)
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private fun render(s: EngineBus.State) {
        b.startStopButton.text = getString(if (s.running) R.string.stop else R.string.start)
        setControlsEnabled(!s.running)

        val statusLine = s.error ?: when (s.status) {
            "connecting" -> "Connecting…"
            "connected" -> getString(R.string.waiting)
            "disconnected", "stopped" -> "Stopped."
            else -> getString(R.string.status_idle)
        }
        b.statusText.text = statusLine

        b.translationText.text = s.translation
        b.provisionalText.text = s.provisionalTranslation
        b.sourceText.text = listOf(s.source, s.provisionalSource).filter { it.isNotBlank() }.joinToString(" ")
        b.scrollView.post { b.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        b.engineSpinner.isEnabled = enabled
        b.sourceAudioSpinner.isEnabled = enabled
        b.sourceSpinner.isEnabled = enabled
        b.targetSpinner.isEnabled = enabled
        b.sonioxKeyInput.isEnabled = enabled
        b.openaiKeyInput.isEnabled = enabled
        b.deepseekKeyInput.isEnabled = enabled
        b.glossaryInput.isEnabled = enabled
        b.refineSwitch.isEnabled = enabled
    }

    private fun setStatus(text: String) {
        b.statusText.text = text
    }
}
