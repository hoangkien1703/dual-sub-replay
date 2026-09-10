package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.*
import org.junit.Test

class CaptionPlaybackClockTest {
    private fun sample(
        second: Float = 10f,
        epoch: Long = 1000,
        session: String = "a",
    ) = WebPlaybackSnapshot("https://m.youtube.com/watch?v=dQw4w9WgXcQ", second, sampledAtEpochMs = epoch, sessionId = session)

    @Test fun projectsSamplingTimeInsteadOfCallbackArrivalAtEverySpeed() {
        listOf(0.5, 1.0, 1.5, 2.0).forEach { rate ->
            val clock = CaptionPlaybackClock()
            assertTrue(clock.accept(sample().copy(playbackRate = rate), 100, 180, 1080))
            assertEquals(10000 + (100 * rate).toLong(), clock.position(200))
        }
    }

    @Test fun bufferingAndPauseDoNotAdvanceAndStaleDataExpires() {
        listOf(sample().copy(paused = true), sample().copy(buffering = true)).forEach { stopped ->
            val clock = CaptionPlaybackClock()
            assertTrue(clock.accept(stopped, 100, 100, 1000))
            assertEquals(10000L, clock.position(220))
            assertNull(clock.position(351))
        }
    }

    @Test fun rejectsDelayedOutOfOrderAndRetiredElementCallbacks() {
        val clock = CaptionPlaybackClock()
        assertFalse(clock.accept(sample(), 100, 800, 1700))
        assertTrue(clock.accept(sample(), 100, 110, 1010))
        assertTrue(clock.accept(sample(2f, 1100, "b"), 200, 210, 1110))
        assertFalse(clock.accept(sample(11f, 1050), 200, 220, 1120))
        assertFalse(clock.accept(sample(12f, 1200), 300, 310, 1210))
        assertEquals(2040L, clock.position(240))
    }

    @Test fun seeksReanchorWithoutSmoothingAcrossDiscontinuity() {
        val clock = CaptionPlaybackClock()
        assertTrue(clock.accept(sample(), 100, 100, 1000))
        assertTrue(clock.accept(sample(2f, 1100, "seek").copy(seeking = true), 200, 200, 1100))
        assertNull(clock.position(220))
        assertTrue(clock.accept(sample(2f, 1200, "seek"), 300, 300, 1200))
        assertEquals(2033L, clock.position(333))
    }

    @Test fun controlledSteadyPlaybackAddsLessThan150msAcrossPresentationRestarts() {
        repeat(3) {
            val clock = CaptionPlaybackClock()
            for (elapsed in 0L..3000L step 33) {
                val sampleTime = elapsed / 100 * 100
                clock.accept(sample(sampleTime / 1000f, 1000 + sampleTime), sampleTime, sampleTime + 25, 1025 + sampleTime)
                val projected = clock.position(elapsed + 25)!!
                assertTrue(kotlin.math.abs(projected - (elapsed + 25)) < 150)
            }
        }
    }
}
