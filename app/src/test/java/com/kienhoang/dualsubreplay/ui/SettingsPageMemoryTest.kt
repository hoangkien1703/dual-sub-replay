package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageMemoryTest {
    private val layoutPlace = SettingsPagePlace(MoreSettingsSection.LAYOUT, scrollOffset = 1_200)

    @Test
    fun aFreshMemoryOpensAtTheTop() {
        assertEquals(SettingsPagePlace(), SettingsPageMemory().placeToRestore(nowMillis = 5_000))
    }

    @Test
    fun reopeningWithinThreeMinutesRestoresTheSectionAndScroll() {
        val memory = SettingsPageMemory()
        memory.remember(layoutPlace, nowMillis = 10_000)

        assertEquals(layoutPlace, memory.placeToRestore(nowMillis = 10_000 + 60_000))
        assertEquals(layoutPlace, memory.placeToRestore(nowMillis = 10_000 + SETTINGS_PAGE_MEMORY_MILLIS))
    }

    @Test
    fun afterThreeMinutesThePageStartsAtTheTopAgain() {
        val memory = SettingsPageMemory()
        memory.remember(layoutPlace, nowMillis = 10_000)

        assertEquals(SettingsPagePlace(), memory.placeToRestore(nowMillis = 10_000 + SETTINGS_PAGE_MEMORY_MILLIS + 1))
    }

    @Test
    fun aClockThatWentBackwardsForgetsThePlace() {
        assertFalse(settingsPlaceStillFresh(closedAtMillis = 10_000, nowMillis = 9_000))
        assertTrue(settingsPlaceStillFresh(closedAtMillis = 10_000, nowMillis = 10_000))
    }

    @Test
    fun aShortcutOpensItsSectionAtTheTopWhateverWasRemembered() {
        val memory = SettingsPageMemory()
        memory.remember(SettingsPagePlace(MoreSettingsSection.COLORS, scrollOffset = 900), nowMillis = 0)

        assertEquals(
            SettingsPagePlace(MoreSettingsSection.LAYOUT, scrollOffset = 0),
            memory.placeOnOpen(MoreSettingsSection.LAYOUT, nowMillis = 1_000),
        )
        assertEquals(SettingsPagePlace(MoreSettingsSection.COLORS, scrollOffset = 900), memory.placeOnOpen(null, nowMillis = 1_000))
    }

    @Test
    fun aQuickTapKeepsThePageSeeThroughAndAHoldDoesNot() {
        assertTrue(settingsPeekLockedAfterClick(locked = false, pressedAtMillis = 1_000, nowMillis = 1_100))
        assertFalse(settingsPeekLockedAfterClick(locked = false, pressedAtMillis = 1_000, nowMillis = 1_000 + SETTINGS_PEEK_TAP_MILLIS))
        // TalkBack and TV remotes click without a touch: that counts as a tap.
        assertTrue(settingsPeekLockedAfterClick(locked = false, pressedAtMillis = null, nowMillis = 1_000))
        assertFalse(settingsPeekLockedAfterClick(locked = true, pressedAtMillis = null, nowMillis = 1_000))
    }

    @Test
    fun peekingOrHoldingASliderHidesTheWholePage() {
        assertEquals(1f, settingsPageAlpha(peeking = false, sliderHeld = false))
        assertEquals(0f, settingsPageAlpha(peeking = false, sliderHeld = true))
        assertEquals(0f, settingsPageAlpha(peeking = true, sliderHeld = false))
    }
}
