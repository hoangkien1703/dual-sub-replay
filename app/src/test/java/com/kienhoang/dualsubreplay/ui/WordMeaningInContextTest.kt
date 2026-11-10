package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMeaningInContextTest {
    private val go =
        LearningWordSelection(
            AnalyzedToken("go", 2, 4, PartOfSpeech.VERB),
            "en",
            "az",
            "dQw4w9WgXcQ",
            SubtitleSegment(1, 1_000, 3_000, "I go", "Mən gedirəm", sentence = SentenceSlice("I go to school.", listOf(4), 0)),
            false,
        )

    @Test
    fun theSentenceIsTheWholeSentenceOfTheTappedLine() {
        assertEquals("I go to school.", contextSentence(go))
        assertEquals("I go", contextSentence(go.copy(segment = go.segment!!.copy(sentence = null))))
        // A word tapped in the translation line is read in the translated sentence.
        assertEquals("Mən gedirəm", contextSentence(go.copy(translated = true)))
        assertNull(contextSentence(go.copy(segment = null)))
        assertNull(contextSentence(go.copy(translated = true, segment = go.segment!!.copy(translatedText = " "))))
    }

    @Test
    fun theRequestNamesTheLanguagesTheSentenceAndTheWord() {
        val (system, user) = contextMeaningRequest("go", "I go to school.", "en", "az")
        assertTrue("written in Azerbaijani" in system)
        assertEquals("Sentence (English): I go to school.\nTapped word: go", user)
    }

    @Test
    fun repliesAreCutToTheMeaning() {
        assertEquals("gedirəm", cleanContextMeaning("\"gedirəm\"\n"))
        assertEquals("gedirəm", cleanContextMeaning("\n  **gedirəm.**  \nThis is the first person."))
        assertEquals("đi học", cleanContextMeaning("«đi học»"))
        assertNull(cleanContextMeaning(null))
        assertNull(cleanContextMeaning("  \n "))
        assertNull(cleanContextMeaning("x".repeat(81)))
    }

    @Test
    fun theSavedMeaningPutsTheSentenceMeaningFirst() {
        assertEquals("gedirəm (getmək)", combinedMeaning("gedirəm", "getmək"))
        assertEquals("getmək", combinedMeaning("Getmək", "getmək"))
        assertEquals("getmək", combinedMeaning(null, "getmək"))
        assertEquals("gedirəm", combinedMeaning("gedirəm", " "))
        assertTrue(differsFromDictionary("gedirəm", "getmək"))
        assertFalse(differsFromDictionary(" ", "getmək"))
    }
}
