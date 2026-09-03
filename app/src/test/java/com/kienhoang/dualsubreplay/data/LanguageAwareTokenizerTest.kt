package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.ui.findWordAtOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAwareTokenizerTest {

    @Test
    fun segmentsJapaneseTextIntoMorphemes() {
        val text = "日本語を勉強します"
        val tokens = LanguageAwareTokenizer.tokenize(text, languageCode = "ja")

        assertTrue("Should split into multiple tokens, got ${tokens.size}", tokens.size > 1)
        val surfaces = tokens.map { it.text }
        assertTrue("Tokens should cover the text", surfaces.joinToString("").replace(" ", "") == text)
        val woToken = tokens.find { it.text == "を" }
        assertNotNull("Should identify particle を", woToken)
        assertEquals(PartOfSpeech.PARTICLE, woToken?.partOfSpeech)
    }

    @Test
    fun segmentsJapaneseKatakanaAndKanji() {
        val text = "ラーメンを食べる"
        val tokens = LanguageAwareTokenizer.tokenize(text, languageCode = "ja")

        assertTrue(tokens.size >= 3)
        assertEquals("ラーメン", tokens[0].text)
        assertEquals(PartOfSpeech.NOUN, tokens[0].partOfSpeech)
        assertEquals("を", tokens[1].text)
        assertEquals(PartOfSpeech.PARTICLE, tokens[1].partOfSpeech)
    }

    @Test
    fun tagsEnglishWordsWithPartOfSpeech() {
        val text = "The quick brown fox jumps over the lazy dog"
        val tokens = LanguageAwareTokenizer.tokenize(text, languageCode = "en")

        assertEquals(9, tokens.size)
        assertEquals(PartOfSpeech.PARTICLE, tokens[0].partOfSpeech) // "The" -> article/grammar particle
        assertEquals(PartOfSpeech.NOUN, tokens[3].partOfSpeech) // fox
        assertEquals(PartOfSpeech.PREPOSITION, tokens[5].partOfSpeech) // over -> preposition
    }

    @Test
    fun findsWordAtOffsetCorrectly() {
        val text = "Learning Kotlin is fun"
        val tokenKotlin = findWordAtOffset(text, charOffset = 11, languageCode = "en")

        assertNotNull(tokenKotlin)
        assertEquals("Kotlin", tokenKotlin?.text)
        assertEquals(9, tokenKotlin?.startIndex)
        assertEquals(15, tokenKotlin?.endIndex)

        val outOfBounds = findWordAtOffset(text, charOffset = 100, languageCode = "en")
        assertNull(outOfBounds)
    }

    @Test
    fun findsJapaneseWordAtOffset() {
        val text = "桜の花が咲く"
        val tokenHana = findWordAtOffset(text, charOffset = 2, languageCode = "ja")

        assertNotNull(tokenHana)
        assertEquals("花", tokenHana?.text)
    }

    @Test
    fun colorForPartOfSpeechIsDistinct() {
        val nounColor = PartOfSpeech.NOUN.colorHex
        val verbColor = PartOfSpeech.VERB.colorHex
        val particleColor = PartOfSpeech.PARTICLE.colorHex
        val unalignedColor = PartOfSpeech.UNALIGNED.colorHex

        assertTrue(nounColor != verbColor)
        assertTrue(verbColor != particleColor)
        assertTrue(unalignedColor != nounColor)
        assertEquals(0xFF78909CL, unalignedColor)
    }

    @Test
    fun alignsTranslationTokensAndColorsUnalignedWordsInDarkGray() {
        val originalText = "Both Cursor and OpenAI in order to get as many"
        val originalTokens = LanguageAwareTokenizer.tokenize(originalText, "en")
        val translationText = "Cả con trỏ và Openai để có được nhiều"

        val alignedTokens = LanguageAwareTokenizer.alignAndTokenizeTranslation(
            translationText = translationText,
            originalTokens = originalTokens,
            translationLanguage = "vi",
        )

        assertTrue(alignedTokens.isNotEmpty())
        val openaiToken = alignedTokens.find { it.text.equals("Openai", ignoreCase = true) }
        assertNotNull(openaiToken)
        assertEquals(PartOfSpeech.NOUN, openaiToken?.partOfSpeech)

        val duocToken = alignedTokens.find { it.text == "được" }
        assertNotNull(duocToken)
        assertEquals(PartOfSpeech.UNALIGNED, duocToken?.partOfSpeech)
        assertEquals(0xFF78909CL, duocToken?.partOfSpeech?.colorHex)
    }

    @Test
    fun classifiesEnglishPronounsAndConjunctionsDistinctly() {
        val text = "Both you and I"
        val tokens = LanguageAwareTokenizer.tokenize(text, "en")
        assertEquals(PartOfSpeech.CONJUNCTION, tokens[0].partOfSpeech)
        assertEquals(PartOfSpeech.PRONOUN, tokens[1].partOfSpeech)
        assertEquals(PartOfSpeech.CONJUNCTION, tokens[2].partOfSpeech)
        assertEquals(PartOfSpeech.PRONOUN, tokens[3].partOfSpeech)
    }
}

