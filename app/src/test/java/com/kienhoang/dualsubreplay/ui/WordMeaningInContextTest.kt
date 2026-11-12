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
        assertEquals("I go to school.", meaningContext(go)?.sentence)
        assertEquals("I go", meaningContext(go.copy(segment = go.segment!!.copy(sentence = null)))?.sentence)
        // A word tapped in the translation line is read in the translated sentence.
        assertEquals("Mən gedirəm", meaningContext(go.copy(translated = true))?.sentence)
        assertNull(meaningContext(go.copy(segment = null)))
        assertNull(meaningContext(go.copy(translated = true, segment = go.segment!!.copy(translatedText = " "))))
    }

    @Test
    fun theContextHasTheSentencesTranslationAndTheLinesAroundIt() {
        val before = SubtitleSegment(0, 0, 900, "I wake up early.", "Mən tezdən dururam.")
        val first = go.segment!!.copy(translatedText = "Mən hər gün")
        val second =
            SubtitleSegment(2, 3_000, 4_000, "to school.", "məktəbə gedirəm.", sentence = SentenceSlice("I go to school.", listOf(4), 1))
        val after = SubtitleSegment(3, 4_500, 5_500, "Then I come home.", "Sonra evə qayıdıram.")
        val segments = listOf(before, first, second, after)
        val context = meaningContext(go.copy(segment = first), segments)
        assertEquals(
            MeaningContext("I go to school.", "Mən hər gün məktəbə gedirəm.", "I wake up early.", "Then I come home."),
            context,
        )
        // Tapped in the sentence's second row: the same sentence, translation and neighbours.
        assertEquals(context, meaningContext(go.copy(segment = second), segments))
        // In the translation line, the sentence and its neighbours are the translations, the original is the "translation".
        assertEquals(
            MeaningContext("Mən hər gün məktəbə gedirəm.", "I go to school.", "Mən tezdən dururam.", "Sonra evə qayıdıram."),
            meaningContext(go.copy(segment = first, translated = true), segments),
        )
        // A row of the sentence still untranslated, or outside the transcript window: no translation.
        assertNull(meaningContext(go.copy(segment = first), listOf(before, first, second.copy(translatedText = null), after))?.translation)
        assertNull(meaningContext(go.copy(segment = first), listOf(before, first))?.translation)
        assertEquals("I wake up early.", meaningContext(go.copy(segment = first), listOf(before, first))?.before)
    }

    @Test
    fun theRequestNamesTheLanguagesTheSentenceAndTheWord() {
        val (system, user) = contextMeaningRequest("go", MeaningContext("I go to school."), "en", "az")
        assertTrue("written in Azerbaijani" in system)
        assertTrue("one to four words" in system)
        assertEquals("Sentence (English): I go to school.\nTapped word: go", user)
    }

    @Test
    fun theRequestGivesTheTranslationSoTheMeaningMatchesIt() {
        val context =
            MeaningContext("So, an example of that is 'spitting'.", "Buna nümunə olaraq 'çiskin'i göstərmək olar.", "Yeah.", "Yes.")
        val (system, user) = contextMeaningRequest("spitting", context, "en", "az")
        assertTrue("exactly the words of that translation" in system)
        assertEquals(
            "Line before: Yeah.\nSentence (English): So, an example of that is 'spitting'.\nLine after: Yes.\n" +
                "Translation of the sentence (Azerbaijani): Buna nümunə olaraq 'çiskin'i göstərmək olar.\nTapped word: spitting",
            user,
        )
    }

    @Test
    fun aPhraseIsAskedAsAWholeAndMayGetALongerAnswer() {
        val (system, user) =
            contextMeaningRequest(
                "I asked meteorologist and BBC weather presenter",
                MeaningContext("I asked meteorologist and BBC weather presenter,"),
                "en",
                "az",
                phrase = true,
            )
        assertFalse("one to four words" in system)
        assertTrue("the whole selection" in system)
        assertTrue(user.endsWith("\nSelected words: I asked meteorologist and BBC weather presenter"))
        val long = "Mən meteoroloqdan və BBC-nin hava proqnozu aparıcısından yağış üçün hansı sözləri istifadə edə biləcəyimizi soruşdum"
        assertTrue(long.length > 80)
        assertNull(cleanContextMeaning(long))
        assertEquals(long, cleanContextMeaning(long, phrase = true))
        assertNull(cleanContextMeaning("x".repeat(241), phrase = true))
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
        // Show first → Dictionary meaning.
        assertEquals("getmək (gedirəm)", combinedMeaning("gedirəm", "getmək", contextFirst = false))
        assertEquals("getmək", combinedMeaning("Getmək", "getmək", contextFirst = false))
        assertEquals(MEANING_ORDER_SENTENCE_FIRST, storedMeaningOrder(null))
        assertEquals(MEANING_ORDER_SENTENCE_FIRST, storedMeaningOrder("sideways"))
        assertEquals(MEANING_ORDER_DICTIONARY_FIRST, storedMeaningOrder("dictionary_first"))
        assertTrue(differsFromDictionary("gedirəm", "getmək"))
        assertFalse(differsFromDictionary(" ", "getmək"))
    }
}
