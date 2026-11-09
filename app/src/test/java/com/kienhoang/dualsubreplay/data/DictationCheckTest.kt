package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationCheckTest {
    private fun rightWords(
        line: String,
        answer: String,
        language: String? = "en",
        tokens: List<AnalyzedToken> = emptyList(),
    ): List<Pair<String, Boolean>> = DictationCheck.check(line, answer, language, tokens).parts.map { it.text to it.right }

    @Test
    fun capitalsPunctuationAndSpacingDoNotCount() {
        val result = DictationCheck.check("We begin to make a snowman.", "we  begin to make a SNOWMAN", "en")
        assertTrue(result.perfect)
        assertEquals(6, result.rightCount)
        assertTrue(result.spaced)
        assertEquals(listOf("We", "begin", "to", "make", "a", "snowman"), result.parts.map { it.text })
    }

    @Test
    fun missedWordsAreWrongButTheRestStayRight() {
        assertEquals(
            listOf("We" to true, "begin" to true, "to" to false, "make" to true, "a" to false, "snowman" to true),
            rightWords("We begin to make a snowman.", "we begin make snowman"),
        )
        assertEquals(4, DictationCheck.check("We begin to make a snowman.", "we begin make snowman", "en").rightCount)
    }

    @Test
    fun aWrongWordIsWrongAndExtraWordsCostNothingElse() {
        assertEquals(
            listOf("ahorita" to true, "que" to true, "se" to true, "termine" to false, "mi" to true, "peli" to true, "amor" to true),
            rightWords("ahorita que se termine mi peli, amor.", "ahorita que se termina mi peli mi amor", "es"),
        )
    }

    @Test
    fun accentsCountButApostrophesAndHyphensDoNot() {
        assertEquals(listOf("Película" to false), rightWords("Película", "pelicula", "es"))
        assertTrue(DictationCheck.check("I don't know.", "i dont know", "en").perfect)
        assertTrue(DictationCheck.check("It's well-known", "it’s well known", "en").perfect)
        assertTrue(DictationCheck.check("Tôi đi học.", "tôi đi học", "vi").perfect)
        assertFalse(DictationCheck.check("Tôi đi học.", "toi di hoc", "vi").perfect)
    }

    @Test
    fun anEmptyAnswerGetsNothingRight() {
        val result = DictationCheck.check("Hello there", "", "en")
        assertEquals(0, result.rightCount)
        assertFalse(result.perfect)
    }

    @Test
    fun chineseIsCheckedPerCharacter() {
        val result = DictationCheck.check("我们开始堆雪人。", "我们开始对雪人", "zh-Hans")
        assertFalse(result.spaced)
        assertEquals(listOf("我", "们", "开", "始", "堆", "雪", "人"), result.parts.map { it.text })
        assertEquals(listOf(true, true, true, true, false, true, true), result.parts.map { it.right })
    }

    @Test
    fun japaneseAcceptsKanaForAWordWhoseReadingIsKnown() {
        val line = "私は学生です。"
        val tokens =
            listOf(
                AnalyzedToken("私", 0, 1, PartOfSpeech.PRONOUN, reading = "ワタシ"),
                AnalyzedToken("は", 1, 2, PartOfSpeech.PARTICLE, reading = "ハ"),
                AnalyzedToken("学生", 2, 4, PartOfSpeech.NOUN, reading = "ガクセイ"),
                AnalyzedToken("です", 4, 6, PartOfSpeech.VERB, reading = "デス"),
                AnalyzedToken("。", 6, 7, PartOfSpeech.OTHER),
            )
        assertTrue(DictationCheck.check(line, "わたしはがくせいです", "ja", tokens).perfect)
        assertTrue("Kanji typed by the keyboard count too", DictationCheck.check(line, "私は学生です", "ja", tokens).perfect)
        assertTrue("Katakana counts as hiragana", DictationCheck.check(line, "ワタシハガクセイデス", "ja", tokens).perfect)
        assertEquals(
            listOf("私" to true, "は" to true, "学生" to false, "です" to true),
            rightWords(line, "わたしはがっこうです", "ja", tokens),
        )
    }

    @Test
    fun japaneseWithoutReadingsIsCheckedPerCharacter() {
        val result = DictationCheck.check("おはよう！", "おはよ", "ja")
        assertFalse(result.spaced)
        assertEquals(3, result.rightCount)
        assertEquals(listOf("お", "は", "よ", "う"), result.parts.map { it.text })
    }

    @Test
    fun hiddenLinesShowOneDotPerWordOrCharacter() {
        assertEquals("• • • • • •", DictationCheck.hiddenDots("We begin to make a snowman.", "en"))
        assertEquals("•••••••", DictationCheck.hiddenDots("我们开始堆雪人。", "zh"))
        assertEquals("•", DictationCheck.hiddenDots("♪", "en"))
    }
}
