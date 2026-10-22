package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PagePollPacingTest {
    private val playing = WebPlaybackSnapshot(url = "https://m.youtube.com/watch?v=abcdefghijk", currentSecond = 12f)

    @Test
    fun aPlayingVideoKeepsTheFastReadsTheWordHighlightNeeds() {
        assertEquals(100L, playbackPollIntervalMs(watchPage = true, latest = playing))
        assertEquals(33L, playbackTickIntervalMs(playing))
        // Buffering goes on by itself, so it counts as playing.
        assertEquals(100L, playbackPollIntervalMs(watchPage = true, latest = playing.copy(buffering = true)))
    }

    @Test
    fun pausedOrNotStartedVideosAreReadLessOften() {
        assertEquals(250L, playbackPollIntervalMs(watchPage = true, latest = playing.copy(paused = true)))
        assertEquals(250L, playbackPollIntervalMs(watchPage = true, latest = null))
        assertEquals(250L, playbackPollIntervalMs(watchPage = true, latest = playing.copy(currentSecond = null)))
        assertEquals(100L, playbackTickIntervalMs(playing.copy(paused = true)))
        assertEquals(100L, playbackTickIntervalMs(null))
    }

    @Test
    fun pagesWithoutAVideoAreReadTwiceASecond() {
        assertEquals(500L, playbackPollIntervalMs(watchPage = false, latest = playing))
        assertEquals(500L, playbackPollIntervalMs(watchPage = false, latest = null))
    }
}
