package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowTest {
    @Test
    fun movesDownThePageStaysTheDefault() {
        assertEquals(TranscriptFollow.PAGE, storedTranscriptFollow(null))
        assertEquals(TranscriptFollow.PAGE, storedTranscriptFollow("lyrics"))
        assertEquals(TranscriptFollow.CENTER, storedTranscriptFollow("center"))
        assertEquals(TranscriptFollow.PAGE, DualSubUiState().transcriptFollow)
        assertTrue(TRANSCRIPT_FOLLOW_PREFERENCE in RESETTABLE_SETTING_KEYS)
    }

    @Test
    fun aRowIsCenteredInTheViewport() {
        // A 100 px row at 500 px in a 600 px viewport: its middle (550) moves to 300.
        assertEquals(250, centreScrollDelta(offset = 500, size = 100, viewportEnd = 600))
        // A row above the middle scrolls back.
        assertEquals(-200, centreScrollDelta(offset = 50, size = 100, viewportEnd = 600))
        // Already centered.
        assertEquals(0, centreScrollDelta(offset = 250, size = 100, viewportEnd = 600))
    }

    @Test
    fun aRowTallerThanTheViewportAlignsItsTop() {
        assertEquals(40, centreScrollDelta(offset = 40, size = 900, viewportEnd = 600))
        assertEquals(-30, centreScrollDelta(offset = -30, size = 600, viewportEnd = 600))
    }
}
