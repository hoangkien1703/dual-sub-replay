package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.learningSelection
import com.kienhoang.dualsubreplay.data.savedWordFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words on the third subtitle line can be tapped (25kiuo, Discussion #81, 2026-11-21). */
class ThirdLineTapTest {
    private val row = SubtitleSegment(id = 4, startMs = 1_000, endMs = 2_000, originalText = "The dog sleeps.", translatedText = "狗在睡觉。")

    // The tapped word's segment carries the third line (German) as its translation.
    private val tap =
        WordTap(AnalyzedToken("Hund", 4, 8, PartOfSpeech.NOUN), row.copy(translatedText = "Der Hund schläft."), true, language = "de")

    private val state =
        DualSubUiState(
            activeVideoId = "dQw4w9WgXcQ",
            resolvedSourceLanguage = "en",
            targetLanguage = "zh-CN",
            thirdLineEnabled = true,
            thirdLanguage = "de",
            thirdLines = ThirdLines("dQw4w9WgXcQ", "de", mapOf(4L to "Der Hund schläft.")),
            segments = listOf(row),
        )

    @Test fun aThirdLineWordIsInItsOwnLanguageAndExplainedInTheLearnersLanguage() {
        val selection = learningSelection(tap, "en", "zh-CN", "dQw4w9WgXcQ")
        assertEquals("de", selection.wordLanguage)
        assertEquals("zh-CN", selection.meaningLanguage)
        assertEquals("de", selection.lineLanguage)
        // The other two lines keep their languages.
        assertNull(learningSelection(tap.copy(language = null, translated = false), "en", "zh-CN", null).lineLanguage)
        assertEquals("en", learningSelection(tap.copy(language = null, translated = true), "en", "zh-CN", null).meaningLanguage)
    }

    @Test fun itsCardKeepsTheThirdLineAsItsSentenceAndTheTranslationAsItsThirdLine() {
        val selection = learningSelection(tap, "en", "zh-CN", "dQw4w9WgXcQ").withSecondLineAsThird(state)
        assertEquals("狗在睡觉。", selection.thirdText)
        assertEquals("zh-CN", selection.thirdLanguage)
        val saved = savedWordFrom(selection, "狗", true)
        assertEquals("Der Hund schläft.", saved.translatedSentence)
        assertEquals("The dog sleeps.", saved.sentence)
        assertEquals("狗在睡觉。", saved.thirdSentence)
    }

    @Test fun itsMeaningIsAskedInTheThirdLinesSentence() {
        val selection = learningSelection(tap, "en", "zh-CN", "dQw4w9WgXcQ")
        val context = meaningContext(selection, state.segments)!!
        assertEquals("Der Hund schläft.", context.sentence)
        assertTrue(context.translation == "The dog sleeps.")
    }
}
