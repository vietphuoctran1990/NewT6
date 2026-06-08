package com.phucnt.mytranslator

import android.content.Context

/** Tiny SharedPreferences wrapper for persisting user settings between launches. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("my_translator", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var sourceLang: String
        get() = sp.getString("source_lang", Languages.AUTO) ?: Languages.AUTO
        set(v) = sp.edit().putString("source_lang", v).apply()

    var targetLang: String
        get() = sp.getString("target_lang", "vi") ?: "vi"
        set(v) = sp.edit().putString("target_lang", v).apply()

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts_enabled", false)
        set(v) = sp.edit().putBoolean("tts_enabled", v).apply()
}
