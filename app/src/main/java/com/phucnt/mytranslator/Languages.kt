package com.phucnt.mytranslator

/** A language option. [code] is the BCP-47-ish code Soniox expects (e.g. "en", "vi"). */
data class Language(val code: String, val name: String)

object Languages {

    /** "auto" means let Soniox auto-detect the source language. Only valid as a source. */
    const val AUTO = "auto"

    /** Source list: auto-detect first, then common languages, then the rest A–Z. */
    val source: List<Language> = listOf(Language(AUTO, "Auto-detect")) + COMMON_AND_REST

    /** Target list: a concrete language must be chosen (no auto-detect). */
    val target: List<Language> = COMMON_AND_REST

    fun indexOfCode(list: List<Language>, code: String): Int {
        val i = list.indexOfFirst { it.code == code }
        return if (i >= 0) i else 0
    }
}

private val COMMON_AND_REST: List<Language> = listOf(
    Language("en", "English"),
    Language("vi", "Vietnamese"),
    Language("ja", "Japanese"),
    Language("ko", "Korean"),
    Language("zh", "Chinese"),
    Language("yue", "Cantonese"),
    Language("es", "Spanish"),
    Language("fr", "French"),
    Language("de", "German"),
    Language("ru", "Russian"),
    Language("pt", "Portuguese"),
    Language("it", "Italian"),
    Language("id", "Indonesian"),
    Language("ms", "Malay"),
    Language("th", "Thai"),
    Language("hi", "Hindi"),
    Language("ar", "Arabic"),
    Language("tr", "Turkish"),
    Language("nl", "Dutch"),
    // rest, alphabetical by name
    Language("af", "Afrikaans"),
    Language("az", "Azerbaijani"),
    Language("be", "Belarusian"),
    Language("bn", "Bengali"),
    Language("bs", "Bosnian"),
    Language("bg", "Bulgarian"),
    Language("ca", "Catalan"),
    Language("hr", "Croatian"),
    Language("cs", "Czech"),
    Language("da", "Danish"),
    Language("et", "Estonian"),
    Language("fi", "Finnish"),
    Language("gl", "Galician"),
    Language("el", "Greek"),
    Language("gu", "Gujarati"),
    Language("he", "Hebrew"),
    Language("hu", "Hungarian"),
    Language("is", "Icelandic"),
    Language("jv", "Javanese"),
    Language("kn", "Kannada"),
    Language("kk", "Kazakh"),
    Language("ky", "Kyrgyz"),
    Language("lv", "Latvian"),
    Language("lt", "Lithuanian"),
    Language("mk", "Macedonian"),
    Language("ml", "Malayalam"),
    Language("mr", "Marathi"),
    Language("ne", "Nepali"),
    Language("no", "Norwegian"),
    Language("fa", "Persian"),
    Language("pl", "Polish"),
    Language("pa", "Punjabi"),
    Language("ro", "Romanian"),
    Language("sr", "Serbian"),
    Language("sk", "Slovak"),
    Language("sl", "Slovenian"),
    Language("sw", "Swahili"),
    Language("sv", "Swedish"),
    Language("ta", "Tamil"),
    Language("te", "Telugu"),
    Language("uk", "Ukrainian"),
    Language("ur", "Urdu"),
    Language("uz", "Uzbek"),
)
