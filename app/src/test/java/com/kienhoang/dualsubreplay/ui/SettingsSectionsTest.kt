package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun captionTrackLabelDoesNotRepeatTheGeneratedMarker() {
        assertEquals("English (auto-generated)", captionTrackLabel("English (auto-generated)", generated = true))
        assertEquals("English (Auto-Generated)", captionTrackLabel("English (Auto-Generated)", generated = true))
        assertEquals("English (auto-generated)", captionTrackLabel("English", generated = true))
        assertEquals("English", captionTrackLabel("English", generated = false))
    }
}
