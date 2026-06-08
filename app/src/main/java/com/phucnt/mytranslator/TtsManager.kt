package com.phucnt.mytranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks the translated text aloud using Android's built-in Text-to-Speech.
 * This is the "voice" half of the app — the subtitle text from Soniox is
 * queued here and read out in the target language.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingLocale: Locale? = null

    @Volatile var enabled = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) pendingLocale?.let { applyLocale(it) }
    }

    /** Set the spoken language from a Soniox language code (e.g. "vi", "zh"). */
    fun setLanguageByCode(code: String) {
        val locale = localeFor(code)
        if (ready) applyLocale(locale) else pendingLocale = locale
    }

    private fun applyLocale(locale: Locale) {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("TtsManager", "TTS language not available: $locale")
        }
    }

    /** Queue text to be spoken. No-op when disabled. */
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
