package com.kienhoang.dualsubreplay.data

import java.util.Locale

/** The languages the word card explains grammar for. */
internal enum class GrammarLanguage { JAPANESE, CHINESE }

/** Which grammar applies to [text]: Japanese by its code or kana, Chinese by its language code. */
internal fun grammarLanguage(
    text: String,
    languageCode: String?,
): GrammarLanguage? {
    val code = languageCode?.lowercase(Locale.ROOT).orEmpty()
    return when {
        LanguageAwareTokenizer.isJapanese(text, languageCode) -> GrammarLanguage.JAPANESE
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
        GrammarLanguage.CHINESE -> ChineseMorphology.morphemes(line)?.let(::chineseGrammar)
    }
