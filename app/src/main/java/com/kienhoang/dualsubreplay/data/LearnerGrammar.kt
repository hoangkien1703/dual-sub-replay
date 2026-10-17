package com.kienhoang.dualsubreplay.data

import java.util.Locale

/** The languages the word card explains grammar for. */
internal enum class GrammarLanguage { JAPANESE, ENGLISH, CHINESE }

/** Which grammar applies to [text]: Japanese by code or kana, English and Chinese by their language codes. */
internal fun grammarLanguage(
    text: String,
    languageCode: String?,
): GrammarLanguage? {
    val code = languageCode?.lowercase(Locale.ROOT).orEmpty()
    return when {
        LanguageAwareTokenizer.isJapanese(text, languageCode) -> GrammarLanguage.JAPANESE
        code == "en" || code.startsWith("en-") || code.startsWith("en_") -> GrammarLanguage.ENGLISH
        code == "zh" || code.startsWith("zh-") || code.startsWith("zh_") -> GrammarLanguage.CHINESE
        else -> null
    }
}

/** The grammar points of a whole [line], or null while that language's analyzer is still loading. */
internal fun grammarMatches(
    line: String,
    language: GrammarLanguage,
): List<GrammarMatch>? =
    when (language) {
        GrammarLanguage.JAPANESE -> JapaneseMorphology.morphemes(line)?.let(::japaneseGrammar)
        GrammarLanguage.ENGLISH -> EnglishMorphology.morphemes(line)?.let(::englishGrammar)
        GrammarLanguage.CHINESE -> ChineseMorphology.morphemes(line)?.let(::chineseGrammar)
    }
