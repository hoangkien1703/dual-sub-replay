package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {
    @Test
    fun toggleOpensOneSectionAtATime() {
        val layout = toggleMoreSettingsSection(null, MoreSettingsSection.LAYOUT)
        assertEquals(MoreSettingsSection.LAYOUT, layout)
        assertEquals(MoreSettingsSection.COLORS, toggleMoreSettingsSection(layout, MoreSettingsSection.COLORS))
        assertNull(toggleMoreSettingsSection(layout, MoreSettingsSection.LAYOUT))
    }

    @Test
    fun sectionKeysAreUniqueForTestTags() {
        val keys = MoreSettingsSection.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everySectionIsListedOnceUnderItsGroupInPageOrder() {
        val page = settingsGroupsOnPage(withAiAssistant = true)
        assertEquals(SettingsGroup.entries, page.map { it.first })
        // The page order is the enum order, so a group's sections must stand together in the enum.
        assertEquals(MoreSettingsSection.entries, page.flatMap { it.second })
        page.forEach { (group, sections) -> assertTrue(sections.all { it.group == group }) }
    }

    @Test
    fun subtitlesComeFirstAndStartWithLanguages() {
        val (group, sections) = settingsGroupsOnPage(withAiAssistant = true).first()
        assertEquals(SettingsGroup.SUBTITLES, group)
        assertEquals(MoreSettingsSection.LANGUAGES, sections.first())
    }

    @Test
    fun backupAndSharingStaysLastJustAboveReset() {
        val (group, sections) = settingsGroupsOnPage(withAiAssistant = true).last()
        assertEquals(SettingsGroup.APP, group)
        assertEquals(MoreSettingsSection.BACKUP, sections.last())
    }

    @Test
    fun theAiAssistantSectionShowsOnlyInBuildsWithTheAssistant() {
        val without = settingsGroupsOnPage(withAiAssistant = false).flatMap { it.second }
        assertEquals(MoreSettingsSection.entries - MoreSettingsSection.AI_ASSISTANT, without)
    }

    @Test
    fun aGroupReadsAsOneCard() {
        val corner = CornerSize(16.dp)
        val square = CornerSize(0.dp)
        val first = settingsSectionShape(0, 3)
        assertEquals(corner, first.topStart)
        assertEquals(square, first.bottomEnd)
        val middle = settingsSectionShape(1, 3)
        assertEquals(square, middle.topEnd)
        assertEquals(square, middle.bottomStart)
        val last = settingsSectionShape(2, 3)
        assertEquals(square, last.topStart)
        assertEquals(corner, last.bottomEnd)
        val alone = settingsSectionShape(0, 1)
        assertEquals(corner, alone.topStart)
        assertEquals(corner, alone.bottomEnd)
    }

    @Test
    fun captionTrackLabelDoesNotRepeatTheGeneratedMarker() {
        val mark: (String) -> String = { "$it (auto-generated)" }
        assertEquals("English (auto-generated)", captionTrackLabel("English (auto-generated)", generated = true, mark))
        assertEquals("English (Auto-Generated)", captionTrackLabel("English (Auto-Generated)", generated = true, mark))
        assertEquals("English (auto-generated)", captionTrackLabel("English", generated = true, mark))
        assertEquals("English", captionTrackLabel("English", generated = false, mark))
    }
}
