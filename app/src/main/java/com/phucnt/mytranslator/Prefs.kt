package com.phucnt.mytranslator

import android.content.Context

/** SharedPreferences wrapper for persisting user settings between launches. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("my_translator", Context.MODE_PRIVATE)

    companion object {
        const val ENGINE_SONIOX = "soniox"
        const val ENGINE_OPENAI = "openai"
        const val SOURCE_MIC = "mic"
        const val SOURCE_SYSTEM = "system"
    }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var openAiKey: String
        get() = sp.getString("openai_key", "") ?: ""
        set(v) = sp.edit().putString("openai_key", v).apply()

    var deepSeekKey: String
        get() = sp.getString("deepseek_key", "") ?: ""
        set(v) = sp.edit().putString("deepseek_key", v).apply()

    /** When on (Soniox engine), DeepSeek re-translates each final segment for fluency. */
    var deepSeekRefine: Boolean
        get() = sp.getBoolean("deepseek_refine", false)
        set(v) = sp.edit().putBoolean("deepseek_refine", v).apply()

    /** Optional glossary, one "source = target" per line, fed to DeepSeek. */
    var glossary: String
        get() = sp.getString("glossary", "") ?: ""
        set(v) = sp.edit().putString("glossary", v).apply()

    var engine: String
        get() = sp.getString("engine", ENGINE_SONIOX) ?: ENGINE_SONIOX
        set(v) = sp.edit().putString("engine", v).apply()

    var audioSource: String
        get() = sp.getString("audio_source", SOURCE_MIC) ?: SOURCE_MIC
        set(v) = sp.edit().putString("audio_source", v).apply()

    var sourceLang: String
        get() = sp.getString("source_lang", Languages.AUTO) ?: Languages.AUTO
        set(v) = sp.edit().putString("source_lang", v).apply()

    var targetLang: String
        get() = sp.getString("target_lang", "vi") ?: "vi"
        set(v) = sp.edit().putString("target_lang", v).apply()

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts_enabled", false)
        set(v) = sp.edit().putBoolean("tts_enabled", v).apply()

    /** Speech rate, stored as int percent (50..200) → multiplier /100. */
    var ttsRatePercent: Int
        get() = sp.getInt("tts_rate", 110)
        set(v) = sp.edit().putInt("tts_rate", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    /** Soniox endpoint delay (ms): lower = snappier finalization, more fragments. */
    var endpointDelayMs: Int
        get() = sp.getInt("endpoint_delay", 1200)
        set(v) = sp.edit().putInt("endpoint_delay", v).apply()
}
