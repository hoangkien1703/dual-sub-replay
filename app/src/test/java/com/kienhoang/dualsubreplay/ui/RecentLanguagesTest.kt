package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentLanguagesTest {
    @Test
    fun aPickGoesFirstAndKeepsTheLanguageItReplaced() {
        assertEquals(listOf("en", "vi"), rememberRecentLanguage(emptyList(), previous = "vi", picked = "en"))
    }

    @Test
    fun onlyTheThreeNewestPicksAreKept() {
        var recent = emptyList<String>()
        var current = "vi"
        for (picked in listOf("en", "ja", "zh", "ko")) {
            recent = rememberRecentLanguage(recent, current, picked)
            current = picked
        }
        assertEquals(listOf("ko", "zh", "ja"), recent)
    }

    @Test
    fun pickingARecentLanguageAgainMovesItToTheTop() {
        assertEquals(listOf("vi", "en", "ja"), rememberRecentLanguage(listOf("en", "vi", "ja"), previous = "en", picked = "vi"))
    }

    @Test
    fun storedListSurvivesARoundTripAndDropsUnknownCodes() {
        assertEquals(listOf("en", "ja"), parseRecentLanguages(encodeRecentLanguages(listOf("en", "ja"))))
        assertEquals(listOf("en", "zh"), parseRecentLanguages("en, xx,,en,zh-CN"))
        assertTrue(parseRecentLanguages(null).isEmpty())
    }

    @Test
    fun recentGroupShowsTheCurrentLanguageFirstAndNeedsASecondOne() {
        assertTrue(recentLanguagesToShow(emptyList(), selected = "vi").isEmpty())
        assertTrue(recentLanguagesToShow(listOf("vi"), selected = "vi").isEmpty())
        assertEquals(listOf("vi", "en"), recentLanguagesToShow(emptyList<String>() + "en", selected = "vi"))
        assertEquals(listOf("en", "vi", "ja"), recentLanguagesToShow(listOf("en", "vi", "ja"), selected = "en"))
    }

    @Test
    fun resetAllSettingsClearsRecentLanguages() {
        assertTrue(RECENT_TARGET_LANGUAGES_PREFERENCE in RESETTABLE_SETTING_KEYS)
    }
}
