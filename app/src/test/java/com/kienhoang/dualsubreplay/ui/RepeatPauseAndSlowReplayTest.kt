package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pause between repeats (Discussion #257) and Slow replay on a second tap (Discussion #81). */
class RepeatPauseAndSlowReplayTest {
    private val lines =
        listOf(
            SubtitleSegment(1, 1_000, 3_000, "first"),
            SubtitleSegment(2, 3_500, 4_000, "second"),
        )

    private fun play(
        driver: StudyPlaybackDriver,
        settings: StudySettings,
        fromMs: Long,
        toMs: Long,
        session: String,
    ): List<StudyAction> {
        val asked = mutableListOf<StudyAction>()
        var time = fromMs
        while (time <= toMs) {
            driver.tick(StudyTick("abcdefghijk", time, session, false, lines, settings))?.let { asked += it }
            time += 33
        }
        return asked
    }

    @Test
    fun aPauseFollowsEveryPlayOfTheRepeatedLineTheLastOneToo() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 2, repeatPause = RepeatPause.LINE)
        // The first play ends: the video waits as long as the line (2 s) at its start, then plays it again.
        assertEquals(listOf(StudyAction.PlayFromAfter(1_000, 2_000)), play(driver, settings, 900, 3_000, "s1"))
        // The paused seek lands, then the replay: nothing more is asked while paused.
        assertNull(driver.tick(StudyTick("abcdefghijk", 1_000, "s2", true, lines, settings)))
        // The last play ends: a pause where the video is, then it goes on to the next line.
        val last = play(driver, settings, 1_000, 3_000, "s3")
        assertEquals(1, last.size)
        val pause = last.single() as StudyAction.PlayFromAfter
        assertEquals(2_000, pause.waitMs)
        assertTrue(pause.ms in 2_900..3_000)
        // The next, short line waits the shortest pause.
        assertEquals(listOf(StudyAction.PlayFromAfter(3_500, 1_000)), play(driver, settings, pause.ms, 4_000, "s4"))
    }

    @Test
    fun withoutAPauseRepeatsPlayOnAtOnce() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 2)
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), play(driver, settings, 900, 3_000, "s1"))
        assertEquals(emptyList<StudyAction>(), play(driver, settings, 1_000, 3_400, "s2"))
    }

    @Test
    fun pauseLengths() {
        assertNull(repeatPauseMs(RepeatPause.OFF, 3_000))
        assertEquals(3_000L, repeatPauseMs(RepeatPause.LINE, 3_000))
        assertEquals(1_000L, repeatPauseMs(RepeatPause.LINE, 200))
        assertEquals(15_000L, repeatPauseMs(RepeatPause.LINE, 60_000))
        assertEquals(2_000L, repeatPauseMs(RepeatPause.TWO_SECONDS, 9_000))
        assertEquals(5_000L, repeatPauseMs(RepeatPause.FIVE_SECONDS, 9_000))
        assertEquals(RepeatPause.OFF, storedRepeatPause(null))
        assertEquals(RepeatPause.LINE, storedRepeatPause("line"))
        assertEquals(RepeatPause.OFF, StudySettings().repeatPause)
        assertFalse(StudySettings().slowReplay)
    }

    @Test
    fun slowReplayIsHalfSpeedUnlessThreeQuartersIsPicked() {
        assertEquals(SlowReplaySpeed.HALF, StudySettings().slowReplaySpeed)
        assertEquals(SlowReplaySpeed.HALF, storedSlowReplaySpeed(null))
        assertEquals(SlowReplaySpeed.HALF, storedSlowReplaySpeed("2"))
        assertEquals(SlowReplaySpeed.THREE_QUARTERS, storedSlowReplaySpeed("0.75"))
        assertEquals(0.5f, SlowReplaySpeed.HALF.rate)
        assertEquals(6_000L, slowedLineMs(3_000, SlowReplaySpeed.HALF.rate))
        assertEquals(4_000L, slowedLineMs(3_000, SlowReplaySpeed.THREE_QUARTERS.rate))
    }

    @Test
    fun onlyASecondTapOnTheSameLineWhileItReplaysIsSlow() {
        val taps = SlowReplayTaps()
        // A 3 s line plays at most 6 s (at 0.5×); a second tap counts until a second after that.
        assertFalse(taps.tap(lineId = 1, lineMs = 3_000, nowMs = 10_000))
        assertTrue(taps.tap(lineId = 1, lineMs = 3_000, nowMs = 11_000))
        // Tapping again during the slow replay slows it once more.
        assertTrue(taps.tap(lineId = 1, lineMs = 3_000, nowMs = 13_000))
        // Too late: a normal replay.
        assertFalse(taps.tap(lineId = 1, lineMs = 3_000, nowMs = 21_000))
        // Another line is a normal replay.
        assertFalse(taps.tap(lineId = 2, lineMs = 3_000, nowMs = 21_500))
    }

    @Test
    fun theSlowReplayScriptOnlySlowsAndAlwaysGivesTheSpeedBack() {
        val slow = webSlowReplayScript(0.1f, 5_000)
        assertTrue("const rate = 0.5;" in slow)
        assertTrue("setTimeout(restore, 5000)" in slow)
        assertTrue("window.location.protocol !== 'https:'" in slow)
        assertTrue("const rate = 1.0;" in webSlowReplayScript(3f, 1_000))
        assertTrue("const rate = null;" in webSlowReplayScript(null, 0))
    }
}
