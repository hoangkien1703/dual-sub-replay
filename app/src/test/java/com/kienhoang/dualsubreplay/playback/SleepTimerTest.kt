package com.kienhoang.dualsubreplay.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private val playback = MutableStateFlow<WebVideoPlayback?>(video("a", positionMs = 0))
    private var pauses = 0

    private fun video(
        id: String,
        positionMs: Long,
        durationMs: Long? = 600_000,
    ) = WebVideoPlayback(id, "Title", paused = false, positionMs = positionMs, durationMs = durationMs, playbackRate = 1f)

    private fun TestScope.timer() =
        SleepTimerRunner(backgroundScope, now = { testScheduler.currentTime }, playback = playback, pause = { pauses++ })

    @Test
    fun minutesPauseTheVideoWhenTheTimeIsUp() =
        runTest {
            val timer = timer()
            timer.set(SleepTimerChoice.Minutes(15))
            assertEquals(15, timer.minutesLeft())
            advanceTimeBy(10 * 60_000L + 1)
            assertEquals(5, timer.minutesLeft())
            assertEquals(0, pauses)
            advanceTimeBy(5 * 60_000L)
            runCurrent()
            assertEquals(1, pauses)
            assertNull(timer.run.value)
            assertNull(timer.minutesLeft())
        }

    @Test
    fun turningTheTimerOffOrPickingAgainReplacesIt() =
        runTest {
            val timer = timer()
            timer.set(SleepTimerChoice.Minutes(15))
            advanceTimeBy(10 * 60_000L)
            timer.set(SleepTimerChoice.Minutes(30))
            advanceTimeBy(20 * 60_000L)
            assertEquals(0, pauses)
            timer.set(null)
            advanceTimeBy(60 * 60_000L)
            assertEquals(0, pauses)
            assertNull(timer.run.value)
        }

    @Test
    fun endOfVideoPausesAtTheEndOrWhenTheNextVideoStarts() =
        runTest {
            val timer = timer()
            timer.set(SleepTimerChoice.EndOfVideo)
            runCurrent()
            playback.value = video("a", positionMs = 300_000)
            runCurrent()
            assertEquals(0, pauses)
            playback.value = video("a", positionMs = 599_500)
            runCurrent()
            assertEquals(1, pauses)
            assertNull(timer.run.value)

            timer.set(SleepTimerChoice.EndOfVideo)
            runCurrent()
            // Autoplay moved on before the last second was reported.
            playback.value = video("b", positionMs = 0)
            runCurrent()
            assertEquals(2, pauses)
        }

    @Test
    fun theEndIsUnknownWithoutADuration() {
        assertFalse(reachedEnd("a", video("a", positionMs = 900_000, durationMs = null)))
        assertFalse(reachedEnd("a", null))
        assertTrue(reachedEnd(null, video("a", positionMs = 599_000)))
        assertTrue(reachedEnd("a", video("b", positionMs = 0)))
    }
}
