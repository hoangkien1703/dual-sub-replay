package com.kienhoang.dualsubreplay.translation

import java.util.Locale

/** Traditional Chinese, the one translation language told apart by more than its base code (Google's zh-TW). */
const val TRADITIONAL_CHINESE = "zh-TW"

/** Places whose Chinese is written in Traditional characters. */
private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")

data class TranslationLanguageOption(
    val code: String,
    val name: String,
    /** ML Kit has a model for it; the rest translate only online (Google or AI) and through YouTube. */
    val onDevice: Boolean = true,
    /** Other English names people (and the AI assistant) use for it, such as "Chinese" for Simplified Chinese. */
    val aliases: List<String> = emptyList(),
)

object TranslationLanguages {
    /**
     * Every subtitle translation language: the on-device (ML Kit) ones, and the ones only Google and
     * the AI services translate ([TranslationLanguageOption.onDevice] false, owner pick 2026-11-02).
     */
    val all: List<TranslationLanguageOption> = listOf(
        TranslationLanguageOption("af", "Afrikaans"),
        TranslationLanguageOption("sq", "Albanian"),
        TranslationLanguageOption("am", "Amharic", onDevice = false),
        TranslationLanguageOption("ar", "Arabic"),
        TranslationLanguageOption("hy", "Armenian", onDevice = false),
        TranslationLanguageOption("as", "Assamese", onDevice = false),
        TranslationLanguageOption("az", "Azerbaijani", onDevice = false),
        TranslationLanguageOption("eu", "Basque", onDevice = false),
        TranslationLanguageOption("be", "Belarusian"),
        TranslationLanguageOption("bn", "Bengali"),
        TranslationLanguageOption("bs", "Bosnian", onDevice = false),
        TranslationLanguageOption("bg", "Bulgarian"),
        TranslationLanguageOption("my", "Burmese", onDevice = false),
        TranslationLanguageOption("ca", "Catalan"),
        // Chinese keeps its old code "zh" for Simplified, so an earlier pick of "Chinese" stays the same;
        // Traditional is online only: ML Kit's one Chinese model writes Simplified (owner pick 2026-11-20).
        TranslationLanguageOption("zh", "Chinese (Simplified)", aliases = listOf("Chinese", "Simplified Chinese")),
        TranslationLanguageOption("zh-TW", "Chinese (Traditional)", onDevice = false, aliases = listOf("Traditional Chinese")),
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
        TranslationLanguageOption("ha", "Hausa", onDevice = false),
        TranslationLanguageOption("he", "Hebrew"),
        TranslationLanguageOption("hi", "Hindi"),
        TranslationLanguageOption("hu", "Hungarian"),
        TranslationLanguageOption("is", "Icelandic"),
        TranslationLanguageOption("ig", "Igbo", onDevice = false),
        TranslationLanguageOption("id", "Indonesian"),
        TranslationLanguageOption("ga", "Irish"),
        TranslationLanguageOption("it", "Italian"),
        TranslationLanguageOption("ja", "Japanese"),
        TranslationLanguageOption("kn", "Kannada"),
        TranslationLanguageOption("kk", "Kazakh", onDevice = false),
        TranslationLanguageOption("km", "Khmer", onDevice = false),
        TranslationLanguageOption("rw", "Kinyarwanda", onDevice = false),
        TranslationLanguageOption("ko", "Korean"),
        TranslationLanguageOption("ku", "Kurdish", onDevice = false),
        TranslationLanguageOption("ky", "Kyrgyz", onDevice = false),
        TranslationLanguageOption("lo", "Lao", onDevice = false),
        TranslationLanguageOption("la", "Latin", onDevice = false),
        TranslationLanguageOption("lv", "Latvian"),
        TranslationLanguageOption("lt", "Lithuanian"),
        TranslationLanguageOption("lb", "Luxembourgish", onDevice = false),
        TranslationLanguageOption("mk", "Macedonian"),
        TranslationLanguageOption("mg", "Malagasy", onDevice = false),
        TranslationLanguageOption("ms", "Malay"),
        TranslationLanguageOption("ml", "Malayalam", onDevice = false),
        TranslationLanguageOption("mt", "Maltese"),
        TranslationLanguageOption("mi", "Maori", onDevice = false),
        TranslationLanguageOption("mr", "Marathi"),
        TranslationLanguageOption("mn", "Mongolian", onDevice = false),
        TranslationLanguageOption("ne", "Nepali", onDevice = false),
        TranslationLanguageOption("no", "Norwegian"),
        TranslationLanguageOption("or", "Odia", onDevice = false),
        TranslationLanguageOption("ps", "Pashto", onDevice = false),
        TranslationLanguageOption("fa", "Persian"),
        TranslationLanguageOption("pl", "Polish"),
        TranslationLanguageOption("pt", "Portuguese"),
        TranslationLanguageOption("pa", "Punjabi", onDevice = false),
        TranslationLanguageOption("ro", "Romanian"),
        TranslationLanguageOption("ru", "Russian"),
        TranslationLanguageOption("sm", "Samoan", onDevice = false),
        TranslationLanguageOption("gd", "Scottish Gaelic", onDevice = false),
        TranslationLanguageOption("sr", "Serbian", onDevice = false),
        TranslationLanguageOption("sn", "Shona", onDevice = false),
        TranslationLanguageOption("sd", "Sindhi", onDevice = false),
        TranslationLanguageOption("si", "Sinhala", onDevice = false),
        TranslationLanguageOption("sk", "Slovak"),
        TranslationLanguageOption("sl", "Slovenian"),
        TranslationLanguageOption("so", "Somali", onDevice = false),
        TranslationLanguageOption("es", "Spanish"),
        TranslationLanguageOption("su", "Sundanese", onDevice = false),
        TranslationLanguageOption("sw", "Swahili"),
        TranslationLanguageOption("sv", "Swedish"),
        TranslationLanguageOption("tl", "Tagalog"),
        TranslationLanguageOption("tg", "Tajik", onDevice = false),
        TranslationLanguageOption("ta", "Tamil"),
        TranslationLanguageOption("tt", "Tatar", onDevice = false),
        TranslationLanguageOption("te", "Telugu"),
        TranslationLanguageOption("th", "Thai"),
        TranslationLanguageOption("tr", "Turkish"),
        TranslationLanguageOption("tk", "Turkmen", onDevice = false),
        TranslationLanguageOption("uk", "Ukrainian"),
        TranslationLanguageOption("ur", "Urdu"),
        TranslationLanguageOption("ug", "Uyghur", onDevice = false),
        TranslationLanguageOption("uz", "Uzbek", onDevice = false),
        TranslationLanguageOption("vi", "Vietnamese"),
        TranslationLanguageOption("cy", "Welsh"),
        TranslationLanguageOption("xh", "Xhosa", onDevice = false),
        TranslationLanguageOption("yi", "Yiddish", onDevice = false),
        TranslationLanguageOption("yo", "Yoruba", onDevice = false),
        TranslationLanguageOption("zu", "Zulu", onDevice = false),
    )

    private val byCode = all.associateBy(TranslationLanguageOption::code)

    /**
     * The base language of [code] ("zh" for "zh-TW"), as captions, the learning language and the
     * on-device models use it. A language to translate to goes through [targetCode] instead.
     */
    fun normalize(code: String): String = when (code.substringBefore('-').lowercase()) {
        "iw" -> "he"
        else -> code.substringBefore('-').lowercase()
    }

    /**
     * [code] as a language to translate to: its [normalize]d base, except that Traditional Chinese
     * (zh-TW, zh-HK, zh-MO, zh-Hant) stays [TRADITIONAL_CHINESE], so it is not Simplified "zh".
     */
    fun targetCode(code: String): String = if (isTraditionalChinese(code)) TRADITIONAL_CHINESE else normalize(code)

    /** A Chinese code that names the Traditional script or a place that writes it. */
    internal fun isTraditionalChinese(code: String): Boolean {
        val parts = code.trim().lowercase().split('-', '_')
        if (parts.first() != "zh") return false
        val rest = parts.drop(1)
        return "hans" !in rest && ("hant" in rest || rest.any { it in TRADITIONAL_CHINESE_REGIONS })
    }

    /** The catalog entry for [code]: Traditional Chinese codes find "Chinese (Traditional)", other Chinese ones Simplified. */
    fun find(code: String?): TranslationLanguageOption? =
        code?.let(::targetCode)?.let(byCode::get)

    /** The entry whose English name or alias is [name] (any case), for names such as "Traditional Chinese". */
    fun findByName(name: String): TranslationLanguageOption? {
        val trimmed = name.trim()
        return all.firstOrNull { option ->
            option.name.equals(trimmed, ignoreCase = true) || option.aliases.any { it.equals(trimmed, ignoreCase = true) }
        }
    }

    /**
     * The English name an AI request uses for a language to translate to: Chinese names its script,
     * so the model writes the one the person picked.
     */
    fun englishTargetName(code: String): String =
        when (targetCode(code)) {
            "zh" -> "Simplified Chinese"
            TRADITIONAL_CHINESE -> "Traditional Chinese"
            else -> Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
        }

    /** The code YouTube's own caption translation uses: it tells Chinese apart by script (zh-Hans, zh-Hant). */
    fun youTubeTranslationCode(code: String): String =
        when (val target = targetCode(code)) {
            "zh" -> "zh-Hans"
            TRADITIONAL_CHINESE -> "zh-Hant"
            else -> target
        }

    fun displayName(code: String?): String =
        find(code)?.name ?: code.orEmpty().uppercase().ifBlank { "Unknown" }

    fun isSupported(code: String): Boolean = find(code) != null

    /** [code] has an on-device model, so the video can move off Google when Google fails. */
    fun hasOnDeviceModel(code: String): Boolean = find(code)?.onDevice == true
}
