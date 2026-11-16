package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** English IPA on the word card (25kiuo, Discussion #81; spec docs/specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md). */
class EnglishIpaTest {
    private val table = readEnglishIpa("# header\ngo\tɡoʊ\nread\trɛd,rid\nit's\tɪts\n\nbroken\n".byteInputStream())

    @Test
    fun aWordShowsItsPronunciationsBetweenSlashes() {
        assertEquals("/ɡoʊ/", englishIpa(table, "go"))
        assertEquals("/ɡoʊ/", englishIpa(table, " Go "))
        assertEquals("/rɛd/, /rid/", englishIpa(table, "read"))
        assertEquals("/ɪts/", englishIpa(table, "It’s"))
        assertNull(englishIpa(table, "broken"))
        assertNull(englishIpa(table, ""))
    }

    @Test
    fun onlyEnglishGetsIpa() {
        assertTrue(isEnglishLanguage("en"))
        assertTrue(isEnglishLanguage("en-GB"))
        assertTrue(isEnglishLanguage("EN_us"))
        assertFalse(isEnglishLanguage("ja"))
        assertFalse(isEnglishLanguage("eng"))
        assertFalse(isEnglishLanguage(null))
    }

    @Test
    fun theBundledFileHasTheCommonWords() {
        val file = File("src/main/learner-data/en-ipa.txt")
        val bundled = file.inputStream().use(::readEnglishIpa)
        assertEquals("/ɡoʊ/", englishIpa(bundled, "go"))
        assertEquals("/ˈwɔtɝ/", englishIpa(bundled, "water"))
        assertTrue(bundled.size > 30_000)
        assertTrue(file.length() < 1_000_000)
    }

    @Test
    fun aJapaneseWordKeepsItsKana() {
        val selection =
            LearningWordSelection(AnalyzedToken("猫", 0, 1, PartOfSpeech.NOUN, reading = "ねこ"), "ja", "en", null, null, false)
        assertEquals("ねこ", wordReading(selection))
        val phrase = selection.copy(token = AnalyzedToken("go home", 0, 7, PartOfSpeech.VERB), wordLanguage = "en")
        assertNull(wordReading(phrase.copy(parts = listOf(phrase.token, phrase.token))))
    }
}
