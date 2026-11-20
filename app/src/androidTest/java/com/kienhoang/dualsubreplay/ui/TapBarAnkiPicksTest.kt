package com.kienhoang.dualsubreplay.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Add to Anki's icon joins the tap bar after 3 picks from the ▾ list in one day (owner pick 2026-11-20). */
class TapBarAnkiPicksTest {
    private val preferences =
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("tap-bar-anki-picks-test", 0)

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun theThirdPickInADayPutsTheIconInTheBar() {
        assertFalse(TapBarButton.ANKI in readTapBarButtons(preferences))
        assertFalse(countAnkiPickedFromList(preferences, today = 100))
        assertFalse(countAnkiPickedFromList(preferences, today = 100))
        assertTrue(countAnkiPickedFromList(preferences, today = 100))
        assertTrue(TapBarButton.ANKI in readTapBarButtons(preferences))
        assertFalse(preferences.contains(TAP_BAR_ANKI_PICKS_PREFERENCE))
    }

    @Test
    fun picksOnDifferentDaysDoNotAddUp() {
        countAnkiPickedFromList(preferences, today = 100)
        countAnkiPickedFromList(preferences, today = 100)
        assertFalse(countAnkiPickedFromList(preferences, today = 101))
        assertEquals("101:1", preferences.getString(TAP_BAR_ANKI_PICKS_PREFERENCE, null))
        assertFalse(TapBarButton.ANKI in readTapBarButtons(preferences))
    }

    @Test
    fun aChipTheLearnerSetIsNeverChangedByPicks() {
        preferences.edit().putBoolean(TapBarButton.ANKI.preference, false).commit()
        repeat(5) { assertFalse(countAnkiPickedFromList(preferences, today = 100)) }
        assertFalse(TapBarButton.ANKI in readTapBarButtons(preferences))
        assertFalse(preferences.contains(TAP_BAR_ANKI_PICKS_PREFERENCE))
    }
}
