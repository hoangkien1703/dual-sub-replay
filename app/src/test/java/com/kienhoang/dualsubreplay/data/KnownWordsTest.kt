package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KnownWordsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun wordsAreKnownByTheirDictionaryFormInLowerCase() {
        assertEquals("go", knownWordKey(AnalyzedToken("Went", 0, 4, PartOfSpeech.VERB, baseForm = "go")))
        assertEquals("cats", knownWordKey(AnalyzedToken("Cats", 0, 4, PartOfSpeech.NOUN)))
        assertEquals("食べる", knownWordKey(AnalyzedToken("食べた", 0, 3, PartOfSpeech.VERB, baseForm = "食べる")))
        assertEquals("ok", knownWordKey(AnalyzedToken("OK", 0, 2, PartOfSpeech.NOUN, baseForm = " ")))
        // Punctuation, numbers, whole lines and analyzer mistakes are never kept.
        assertNull(knownWordKey("、"))
        assertNull(knownWordKey("2026"))
        assertNull(knownWordKey("two\nlines"))
        assertNull(knownWordKey("a".repeat(65)))
    }

    @Test
    fun eachLanguageHasOneListByItsBaseCode() {
        assertEquals("en", knownWordLanguage("en-US"))
        assertEquals("zh", knownWordLanguage("zh-Hans"))
        assertEquals("pt", knownWordLanguage("pt_BR"))
        assertNull(knownWordLanguage("../en"))
        assertNull(knownWordLanguage(null))
    }

    @Test
    fun comprehensionCountsKnownWordsAndFindsLinesWithOneNewWord() {
        val lines =
            listOf(
                listOf("i", "like", "cats"),
                listOf("i", "like", "dogs"),
                listOf("cats", "and", "dogs", "sleep"),
                emptyList(),
            )
        val result = videoComprehension(lines, known = setOf("i", "like", "and"), learning = setOf("dogs"))
        assertEquals(10, result.words)
        assertEquals(5, result.known)
        assertEquals(2, result.learning)
        assertEquals(50, result.percent)
        assertEquals(setOf("cats", "sleep"), result.newWords)
        // A saved word is being learned, not new: only the first line has exactly one new word.
        assertEquals(setOf(0), result.oneNewWordLines)
        assertEquals(0, videoComprehension(emptyList(), emptySet(), emptySet()).percent)
        assertEquals(100, videoComprehension(lines.take(1), setOf("i", "like", "cats"), emptySet()).percent)
    }

    @Test
    fun knownWordsAreKeptPerLanguageAcrossRestarts() =
        runBlocking {
            val directory = File(folder.root, "known-words")
            val repository = KnownWordsRepository(directory)
            repository.setKnown("en", listOf("Cats", "dogs", "!!"), known = true)
            repository.setKnown("ja", listOf("猫"), known = true)
            repository.setKnown("en", listOf("dogs"), known = false)
            assertEquals(setOf("cats"), repository.words("en").value)
            assertEquals(2, repository.total.value)
            // A language code that is not a base code is refused rather than written as a path.
            repository.setKnown("../en", listOf("x"), known = true)
            assertEquals(setOf("en.txt", "ja.txt"), directory.list().orEmpty().toSet())

            val restarted = KnownWordsRepository(directory)
            assertEquals(setOf("猫"), withTimeout(5_000) { restarted.words("ja").first { it.isNotEmpty() } })
            restarted.clearAll()
            assertTrue(restarted.words("ja").value.isEmpty())
            assertEquals(0, restarted.total.value)
        }
}
