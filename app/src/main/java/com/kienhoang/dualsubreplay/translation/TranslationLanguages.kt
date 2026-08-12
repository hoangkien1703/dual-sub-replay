package com.kienhoang.dualsubreplay.translation

data class TranslationLanguageOption(
    val code: String,
    val name: String,
)

object TranslationLanguages {
    val all: List<TranslationLanguageOption> = listOf(
        TranslationLanguageOption("af", "Afrikaans"),
        TranslationLanguageOption("sq", "Albanian"),
        TranslationLanguageOption("ar", "Arabic"),
        TranslationLanguageOption("be", "Belarusian"),
        TranslationLanguageOption("bn", "Bengali"),
        TranslationLanguageOption("bg", "Bulgarian"),
        TranslationLanguageOption("ca", "Catalan"),
        TranslationLanguageOption("zh", "Chinese"),
        TranslationLanguageOption("hr", "Croatian"),
        TranslationLanguageOption("cs", "Czech"),
        TranslationLanguageOption("da", "Danish"),
        TranslationLanguageOption("nl", "Dutch"),
        TranslationLanguageOption("en", "English"),
        TranslationLanguageOption("eo", "Esperanto"),
        TranslationLanguageOption("et", "Estonian"),
        TranslationLanguageOption("fi", "Finnish"),
        TranslationLanguageOption("fr", "French"),
        TranslationLanguageOption("gl", "Galician"),
        TranslationLanguageOption("ka", "Georgian"),
        TranslationLanguageOption("de", "German"),
        TranslationLanguageOption("el", "Greek"),
        TranslationLanguageOption("gu", "Gujarati"),
        TranslationLanguageOption("ht", "Haitian"),
        TranslationLanguageOption("he", "Hebrew"),
        TranslationLanguageOption("hi", "Hindi"),
        TranslationLanguageOption("hu", "Hungarian"),
        TranslationLanguageOption("is", "Icelandic"),
        TranslationLanguageOption("id", "Indonesian"),
        TranslationLanguageOption("ga", "Irish"),
        TranslationLanguageOption("it", "Italian"),
        TranslationLanguageOption("ja", "Japanese"),
        TranslationLanguageOption("kn", "Kannada"),
        TranslationLanguageOption("ko", "Korean"),
        TranslationLanguageOption("lv", "Latvian"),
        TranslationLanguageOption("lt", "Lithuanian"),
        TranslationLanguageOption("mk", "Macedonian"),
        TranslationLanguageOption("ms", "Malay"),
        TranslationLanguageOption("mt", "Maltese"),
        TranslationLanguageOption("mr", "Marathi"),
        TranslationLanguageOption("no", "Norwegian"),
        TranslationLanguageOption("fa", "Persian"),
        TranslationLanguageOption("pl", "Polish"),
        TranslationLanguageOption("pt", "Portuguese"),
        TranslationLanguageOption("ro", "Romanian"),
        TranslationLanguageOption("ru", "Russian"),
        TranslationLanguageOption("sk", "Slovak"),
        TranslationLanguageOption("sl", "Slovenian"),
        TranslationLanguageOption("es", "Spanish"),
        TranslationLanguageOption("sw", "Swahili"),
        TranslationLanguageOption("sv", "Swedish"),
        TranslationLanguageOption("tl", "Tagalog"),
        TranslationLanguageOption("ta", "Tamil"),
        TranslationLanguageOption("te", "Telugu"),
        TranslationLanguageOption("th", "Thai"),
        TranslationLanguageOption("tr", "Turkish"),
        TranslationLanguageOption("uk", "Ukrainian"),
        TranslationLanguageOption("ur", "Urdu"),
        TranslationLanguageOption("vi", "Vietnamese"),
        TranslationLanguageOption("cy", "Welsh"),
    )

    private val byCode = all.associateBy(TranslationLanguageOption::code)

    fun normalize(code: String): String = when (code.substringBefore('-').lowercase()) {
        "iw" -> "he"
        else -> code.substringBefore('-').lowercase()
    }

    fun find(code: String?): TranslationLanguageOption? =
        code?.let(::normalize)?.let(byCode::get)

    fun displayName(code: String?): String =
        find(code)?.name ?: code.orEmpty().uppercase().ifBlank { "Unknown" }

    fun isSupported(code: String): Boolean = find(code) != null
}
