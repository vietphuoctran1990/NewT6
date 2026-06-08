package com.phucnt.mytranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks translated text aloud using Android's built-in Text-to-Speech.
 * Pre-warms on init and supports a configurable speech rate so audio keeps up
 * with live translation.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingLocale: Locale? = null
    private var rate = 1.0f

    @Volatile var enabled = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.setSpeechRate(rate)
            pendingLocale?.let { applyLocale(it) }
        }
    }

    fun setLanguageByCode(code: String) {
        val locale = localeFor(code)
        if (ready) applyLocale(locale) else pendingLocale = locale
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

    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "tr-${System.nanoTime()}")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
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
