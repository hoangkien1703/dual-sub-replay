package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The script-boundary tokenizer once added empty tokens forever at a Han character its kanji check
 * did not cover (U+9FB0–U+9FFF). The timeouts make a regression fail instead of hanging the build.
 */
class TokenizerTerminationTest {
    @Test(timeout = 5_000)
    fun hanCharactersAtTheEndOfTheUnifiedBlockAreTokenized() {
        for (codePoint in listOf(0x9FAF, 0x9FB0, 0x9FCF, 0x9FFF)) {
            val character = String(Character.toChars(codePoint))
            for (language in listOf("zh", null, "ja")) {
                val tokens = assertValidSpans(character, language)
                assertEquals("U+%04X ($language)".format(codePoint), listOf(character), tokens.map { it.text })
            }
        }
    }

    @Test(timeout = 5_000)
    fun chineseSentenceWithARareCharacterKeepsItsWords() {
        // 鿏 (U+9FCF) is the character for meitnerium.
        val text = "第109号元素鿏是人工合成的"
        val tokens = assertValidSpans(text, "zh")

        assertTrue(tokens.any { "鿏" in it.text })
        assertEquals(text, tokens.joinToString("") { it.text })
    }

    @Test(timeout = 5_000)
    fun captionWordTimingsFinishForChineseCuesWithRareCharacters() {
        val words = estimateWordTimings("第109号元素鿏是人工合成的", 0, 3_000)

        assertTrue(words.isNotEmpty())
        assertEquals(3_000L, words.last().endMs)
    }

    @Test(timeout = 5_000)
    fun mixedScriptsSurrogatesAndMarksProduceValidSpans() {
        val inputs =
            listOf(
                "𠮷野家で食べる", // supplementary Han
                "日本🎌語です", // emoji between kanji
                "カフェ café で", // combining-free and precomposed Latin
                "caféの話", // combining acute accent
                "、。！？「」", // punctuation only
                " \t\n ",
                "\uD800漢字", // lone high surrogate
                "漢字\uDC00です", // lone low surrogate
                "中文 English 123 混合",
            )
        for (text in inputs) {
            for (language in listOf("zh", "ja", null)) assertValidSpans(text, language)
        }
    }

    private fun assertValidSpans(
        text: String,
        language: String?,
    ): List<AnalyzedToken> {
        val tokens = LanguageAwareTokenizer.tokenize(text, language)
        var previousEnd = 0
        for (token in tokens) {
            val label = "'$text' ($language): $token"
            assertTrue(label, token.startIndex >= previousEnd && token.startIndex < token.endIndex && token.endIndex <= text.length)
            assertEquals(label, text.substring(token.startIndex, token.endIndex), token.text)
            assertFalse(label, splitsSurrogatePair(text, token.startIndex) || splitsSurrogatePair(text, token.endIndex))
            previousEnd = token.endIndex
        }
        return tokens
    }

    private fun splitsSurrogatePair(
        text: String,
        index: Int,
    ): Boolean = index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
}
