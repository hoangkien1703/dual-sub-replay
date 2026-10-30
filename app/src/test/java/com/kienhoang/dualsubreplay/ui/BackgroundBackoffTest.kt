package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RemoteSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background translation slows down while Google refuses, and a remote switch can slow it or stop ad skipping. */
class BackgroundBackoffTest {
    @Test
    fun thePauseDoublesWithEachRefusalUpToAMinute() {
        assertEquals(3_000L, backgroundPrefetchPauseMs(3_000, 0))
        assertEquals(6_000L, backgroundPrefetchPauseMs(3_000, 1))
        assertEquals(48_000L, backgroundPrefetchPauseMs(3_000, 4))
        assertEquals(MAX_BACKGROUND_PREFETCH_PAUSE_MS, backgroundPrefetchPauseMs(3_000, 5))
        assertEquals(MAX_BACKGROUND_PREFETCH_PAUSE_MS, backgroundPrefetchPauseMs(3_000, 400))
        // A remote switch's slower pace is the starting point.
        assertEquals(10_000L, backgroundPrefetchPauseMs(10_000, 0))
    }

    @Test
    fun failedStepsRestLongerUpToSixteenMinutes() {
        val rest = 120_000L
        assertEquals(listOf(120_000L, 240_000L, 480_000L, 960_000L, 960_000L), (1..5).map { backgroundRestMs(rest, it) })
        assertEquals(1L, backgroundRestMs(1, 1))
    }

    @Test
    fun aRemoteSwitchOnlyEverTurnsSkippingOff() {
        val user = AdSkipSettings(skipAds = true, skipSponsors = true, alwaysSkipSponsors = true)
        assertEquals(user, user.withRemote(RemoteSwitches()))
        val off = user.withRemote(RemoteSwitches(skipAdsOff = true, skipSponsorsOff = true))
        assertFalse(off.skipAds || off.skipSponsors)
        val userOff = AdSkipSettings(skipAds = false, skipSponsors = false)
        assertEquals(userOff, userOff.withRemote(RemoteSwitches()))
        assertTrue(user.withRemote(RemoteSwitches(skipAdsOff = true)).skipSponsors)
    }
}
