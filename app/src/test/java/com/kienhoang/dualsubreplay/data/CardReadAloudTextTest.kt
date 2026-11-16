package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read aloud on the back (lion737, Discussion #81; spec docs/specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md). */
class CardReadAloudTextTest {
    private val word =
        SavedWord(
            id = "1",
            word = "gehst",
            reading = null,
            wordLanguage = "de",
            meaningLanguage = "en",
            meaning = "are going (to go)",
            sentence = "Wohin gehst du?",
            translatedSentence = "Where are you going?",
            videoId = null,
            startMs = 0,
            endMs = 1_000,
            translated = false,
        )

    @Test
    fun everyPartIsReadWhenEveryChipIsOn() {
        assertEquals(ReadAloudText(listOf("are going", "to go"), "Where are you going?"), readAloudTexts(word, CardReadAloud.ALL))
    }

    @Test
    fun eachChipAddsOnlyItsOwnPart() {
        val onlyContext = CardReadAloud(meaningInSentence = true, dictionaryMeaning = false, translation = false)
        val onlyDictionary = CardReadAloud(meaningInSentence = false, dictionaryMeaning = true, translation = false)
        val onlyTranslation = CardReadAloud(meaningInSentence = false, dictionaryMeaning = false, translation = true)
        assertEquals(ReadAloudText(listOf("are going")), readAloudTexts(word, onlyContext))
        assertEquals(ReadAloudText(listOf("to go")), readAloudTexts(word, onlyDictionary))
        assertEquals(ReadAloudText(translation = "Where are you going?"), readAloudTexts(word, onlyTranslation))
        assertEquals(ReadAloudText(), readAloudTexts(word, CardReadAloud.NONE))
        // Dictionary meaning shown first: "dictionary (in sentence)".
        val dictionaryFirst = word.copy(meaning = "to go (are going)")
        assertEquals(ReadAloudText(listOf("are going")), readAloudTexts(dictionaryFirst, onlyContext, contextFirst = false))
    }

    @Test
    fun blankRepeatedOrSameAsTheWordPartsAreLeftOut() {
        assertEquals(listOf("cat"), readAloudTexts(word.copy(meaning = "cat (Cat)"), CardReadAloud.ALL).meanings)
        assertEquals(listOf("dog"), readAloudTexts(word.copy(meaning = "dog"), CardReadAloud.ALL).meanings)
        assertTrue(readAloudTexts(word.copy(meaning = "Gehst"), CardReadAloud.ALL).meanings.isEmpty())
        assertEquals(null, readAloudTexts(word.copy(translatedSentence = " "), CardReadAloud.ALL).translation)
        // Tapped in the translation line: the sentence in the meaning's language is the original line.
        assertEquals("Wohin gehst du?", readAloudTexts(word.copy(translated = true), CardReadAloud.ALL).translation)
    }

    @Test
    fun aSavedMeaningSplitsAtItsLastBrackets() {
        assertEquals("gedirəm" to "getmək", splitSavedMeaning("gedirəm (getmək)"))
        assertEquals("to go" to null, splitSavedMeaning(" to go "))
        assertEquals("(a) b" to null, splitSavedMeaning("(a) b"))
    }
}
