package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private val entries =
        listOf(
            SettingsSearchEntry("Text size", "Reading & view", SearchableSetting.TEXT_SIZE),
            SettingsSearchEntry("Loop button on the playing line", "Study tools", SearchableSetting.LOOP_BUTTON),
            SettingsSearchEntry("Play mode", "Study tools", SearchableSetting.PLAY_MODE),
            SettingsSearchEntry("Cỡ chữ", "Đọc & xem", SearchableSetting.TEXT_SIZE),
            SettingsSearchEntry("Study tools", "More settings", section = MoreSettingsSection.STUDY),
            SettingsSearchEntry("Translate to", "Languages", SearchableSetting.TRANSLATE_TO),
        )

    @Test
    fun searchFindsNamesWithoutCaseOrAccents() {
        assertEquals(listOf("Loop button on the playing line"), searchSettings(entries, "LOOP").map { it.title })
        assertEquals(listOf("Cỡ chữ"), searchSettings(entries, "co chu").map { it.title })
        assertEquals(listOf("Cỡ chữ"), searchSettings(entries, "đọc").map { it.title })
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings(entries, "   "))
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings(entries, "volume"))
    }

    @Test
    fun namesThatStartWithTheQueryComeFirstThenNameMatchesThenSectionMatches() {
        val titles = searchSettings(entries, "study").map { it.title }
        assertEquals(listOf("Study tools", "Loop button on the playing line", "Play mode"), titles)
        assertEquals(listOf("Play mode", "Loop button on the playing line"), searchSettings(entries, "play").map { it.title })
        // Words can come from the name and the section together.
        assertEquals(listOf("Loop button on the playing line"), searchSettings(entries, "loop study").map { it.title })
    }

    @Test
    fun eachPreferenceKeyAndPlaceBelongsToOneSetting() {
        val keys = SearchableSetting.entries.flatMap { it.keys }
        assertEquals(keys.size, keys.toSet().size)
        val anchors = SearchableSetting.entries.mapNotNull { it.anchor }
        assertEquals(anchors.size, anchors.toSet().size)
        assertEquals(SearchableSetting.LOOP_BUTTON, SearchableSetting.forKey(LOOP_BUTTON_PREFERENCE))
        assertEquals(SearchableSetting.PLAY_MODE, SearchableSetting.forKey(STUDY_REPEAT_COUNT_PREFERENCE))
        assertNull(SearchableSetting.forKey("last_browser_url"))
        assertNull(SearchableSetting.forKey(null))
    }

    @Test
    fun aDragCountsOnceHoweverLongItLasts() {
        var history = recordSettingChange(emptyList(), "TEXT_SIZE", now)
        // Slider steps keep coming, each soon after the one before.
        for (step in 1..10) history = recordSettingChange(history, "TEXT_SIZE", now + step * (SETTING_CHANGE_MERGE_MS - 1))
        assertEquals(listOf(SettingChange("TEXT_SIZE", now + 10 * (SETTING_CHANGE_MERGE_MS - 1))), history)
        // A change after a pause counts again, even a few seconds later.
        history = recordSettingChange(history, "TEXT_SIZE", now + 11 * (SETTING_CHANGE_MERGE_MS - 1) + SETTING_CHANGE_MERGE_MS)
        assertEquals(2, history.size)
    }

    @Test
    fun changesOlderThanSevenDaysAreDropped() {
        val old = listOf(SettingChange("TEXT_SIZE", now - SETTING_HISTORY_MS - 1), SettingChange("TEXT_SIZE", now - day))
        assertEquals(
            listOf(SettingChange("TEXT_SIZE", now - day), SettingChange("SKIP_ADS", now)),
            recordSettingChange(old, "SKIP_ADS", now),
        )
        assertEquals(emptyList<String>(), oftenChangedSettings(old, now))
    }

    @Test
    fun oftenChangedListsUpToThreeSettingsChangedTwiceMostChangedFirst() {
        fun changes(
            setting: String,
            count: Int,
            latest: Long,
        ) = (0 until count).map { SettingChange(setting, latest - it * day / 10) }
        val history =
            changes("TEXT_SIZE", 2, now - day) +
                changes("LOOP_BUTTON", 4, now - 2 * day) +
                changes("SKIP_ADS", 1, now) +
                changes("PLAY_MODE", 2, now - 1_000) +
                changes("APP_COLORS", 3, now - 3 * day) +
                changes("BLUR_BOX", 2, now - 6 * day)
        // Ties (2 changes each) go to the latest change.
        assertEquals(listOf("LOOP_BUTTON", "APP_COLORS", "PLAY_MODE"), oftenChangedSettings(history, now))
        assertTrue("SKIP_ADS" !in oftenChangedSettings(history.filter { it.setting == "SKIP_ADS" }, now))
    }

    @Test
    fun historyReadsBackWhatWasWritten() {
        val history = listOf(SettingChange("TEXT_SIZE", 5), SettingChange("LOOP_BUTTON", 123_456_789_000))
        assertEquals(history, decodeSettingHistory(encodeSettingHistory(history)))
        assertEquals(emptyList<SettingChange>(), decodeSettingHistory(null))
        assertEquals(listOf(SettingChange("A", 1)), decodeSettingHistory("A@1;broken;@2;B@x"))
    }
}
