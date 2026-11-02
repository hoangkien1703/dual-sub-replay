package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdLineAndVideoFilterTest {
    @Test fun thirdLineIsOffUntilAValidLanguageIsSaved() {
        assertNull(storedThirdLanguage(null))
        assertNull(storedThirdLanguage(""))
        assertNull(storedThirdLanguage("xx-not-a-language"))
        assertEquals("fa", storedThirdLanguage("fa"))
    }

    @Test fun thirdLineNeverRepeatsTheOriginalOrTheTranslation() {
        assertEquals("fa", thirdLineLanguage("fa", source = "de", target = "en"))
        assertNull(thirdLineLanguage("en", source = "de", target = "en"))
        assertNull(thirdLineLanguage("de", source = "de", target = "en"))
        assertNull(thirdLineLanguage(null, source = "de", target = "en"))
        assertEquals("fa", thirdLineLanguage("fa", source = null, target = "en"))
    }

    @Test fun rowsShowTheirThirdLineOnlyForTheOpenVideoAndLanguage() {
        val row = SubtitleSegment(id = 4, startMs = 0, endMs = 1_000, originalText = "Hallo", translatedText = "Hello")
        val state =
            DualSubUiState(
                activeVideoId = "video",
                resolvedSourceLanguage = "de",
                targetLanguage = "en",
                thirdLineEnabled = true,
                thirdLanguage = "fa",
                thirdLines = ThirdLines("video", "fa", mapOf(4L to "سلام")),
            )

        assertEquals("سلام", state.thirdLineFor(row))
        assertNull(state.copy(thirdLineEnabled = false).thirdLineFor(row))
        assertNull(state.copy(thirdLanguage = null).thirdLineFor(row))
        assertNull(state.copy(thirdLanguage = "ja").thirdLineFor(row))
        assertNull(state.copy(activeVideoId = "other").thirdLineFor(row))
        assertNull(state.copy(liveFallback = true).thirdLineFor(row))
        assertNull(state.thirdLineFor(row.copy(id = 5)))
    }

    @Test fun thirdLineIsOffByDefaultAndRemembersItsLanguage() {
        assertEquals(ThirdLineChoice(false, null), DualSubUiState().thirdLine)
        assertNull(ThirdLineChoice(enabled = false, language = "fa").shownLanguage)
        assertEquals("fa", ThirdLineChoice(enabled = true, language = "fa").shownLanguage)
    }

    private fun saved(
        id: String,
        videoId: String?,
        title: String?,
        notes: String = "",
    ) = SavedWord(id, "w$id", null, "de", "en", "m$id", "", null, videoId, 0, 0, false, false, sourceTitle = title, notes = notes)

    @Test fun videoFilterListsTitledVideosFromAToZ() {
        val words =
            listOf(
                saved("1", "bbbbbbbbbbb", "Zebra talk"),
                saved("2", "aaaaaaaaaaa", null),
                saved("3", "aaaaaaaaaaa", "apple talk"),
                saved("4", null, null),
                saved("5", "ccccccccccc", null),
            )

        assertEquals(
            listOf(PracticeVideo("aaaaaaaaaaa", "apple talk", 2), PracticeVideo("bbbbbbbbbbb", "Zebra talk", 1)),
            practiceVideos(words),
        )
    }

    @Test fun searchFindsTitlesAndNotesToo() {
        val word = saved("1", "aaaaaaaaaaa", "Easy German", notes = "plural Hunde")

        assertTrue(matchesPracticeSearch(word, ""))
        assertTrue(matchesPracticeSearch(word, "w1"))
        assertTrue(matchesPracticeSearch(word, "easy german"))
        assertTrue(matchesPracticeSearch(word, "hunde"))
        assertFalse(matchesPracticeSearch(word, "katze"))
    }
}
