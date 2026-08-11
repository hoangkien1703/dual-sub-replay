package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArchitectureTest {
    @Test
    fun browseVideoSelectionNormalizesEverySupportedWatchRoute() {
        val expected = BrowseVideoSelection(
            videoId = "dQw4w9WgXcQ",
            canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        )

        assertEquals(expected, browseVideoSelection("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(expected, browseVideoSelection("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(browseVideoSelection("https://m.youtube.com/results?search_query=english"))
    }

    @Test
    fun watchPageKeepsCurrentVideoButHandsDifferentVideosToLearning() {
        assertNull(
            watchVideoSelection(
                currentVideoId = "dQw4w9WgXcQ",
                url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            ),
        )
        assertEquals(
            BrowseVideoSelection(
                videoId = "aqz-KE-bpKQ",
                canonicalUrl = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            ),
            watchVideoSelection(
                currentVideoId = "dQw4w9WgXcQ",
                url = "https://m.youtube.com/watch?v=aqz-KE-bpKQ",
            ),
        )
        assertNull(
            watchVideoSelection(
                currentVideoId = "dQw4w9WgXcQ",
                url = "https://m.youtube.com/results?search_query=english",
            ),
        )
    }

    @Test
    fun watchPageScriptCollapsesNativePlayerAndPreservesScrolling() {
        assertTrue(WATCH_NATIVE_PLAYER_SELECTORS.contains("ytm-player"))
        assertTrue(WATCH_NATIVE_PLAYER_SELECTORS.contains("#player"))
        assertTrue(WATCH_NATIVE_PLAYER_SELECTORS.contains("#player-container-id"))
        assertTrue(WATCH_NATIVE_PLAYER_SELECTORS.contains("#movie_player"))
        assertTrue(WATCH_NATIVE_PLAYER_SELECTORS.none { it.startsWith("ytm-watch ") })
        WATCH_NATIVE_PLAYER_SELECTORS.forEach { selector ->
            assertTrue(WATCH_DETAILS_SCRIPT.contains(selector))
        }
        assertTrue(WATCH_DETAILS_SCRIPT.contains("player.querySelectorAll('video')"))
        assertTrue(WATCH_DETAILS_SCRIPT.contains("video.pause()"))
        assertTrue(WATCH_DETAILS_SCRIPT.contains("MutationObserver"))
        assertFalse(WATCH_DETAILS_SCRIPT.contains("document.querySelectorAll('video')"))
        assertTrue(WATCH_DETAILS_SCRIPT.contains("overflow-y: auto"))
    }

    @Test
    fun learningDestinationRetainsCompleteWatchSession() {
        val session = WatchSession(
            videoId = "dQw4w9WgXcQ",
            canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            returnBrowseUrl = "https://m.youtube.com/results?search_query=english",
            resumeSecond = 42.5f,
        )
        val state = DualSubUiState(destination = AppDestination.Learning(session))

        assertSame(session, state.watchSession)
        assertEquals(42.5f, state.watchSession?.resumeSecond)
        assertEquals(session.returnBrowseUrl, state.watchSession?.returnBrowseUrl)
    }

    @Test
    fun browseDestinationHasNoWatchSession() {
        assertNull(DualSubUiState(destination = AppDestination.Browse).watchSession)
    }

    @Test
    fun activeSubtitleUsesStartInclusiveEndExclusiveBoundariesAndGaps() {
        val segments = listOf(
            SubtitleSegment(1, 1_000, 2_000, "First", null),
            SubtitleSegment(2, 2_500, 3_000, "Second", null),
        )

        assertEquals(-1, activeSubtitleIndex(segments, 999))
        assertEquals(0, activeSubtitleIndex(segments, 1_000))
        assertEquals(0, activeSubtitleIndex(segments, 1_999))
        assertEquals(-1, activeSubtitleIndex(segments, 2_000))
        assertEquals(-1, activeSubtitleIndex(segments, 2_499))
        assertEquals(1, activeSubtitleIndex(segments, 2_500))
        assertEquals(-1, activeSubtitleIndex(segments, 3_000))
    }

    @Test
    fun activeSubtitleIsStableForRepeatedTimesWithinSameSegment() {
        val segments = listOf(SubtitleSegment(1, 10_000, 20_000, "Stable", null))

        assertEquals(0, activeSubtitleIndex(segments, 10_000))
        assertEquals(0, activeSubtitleIndex(segments, 15_000))
        assertEquals(0, activeSubtitleIndex(segments, 19_999))
    }

    @Test
    fun controllerSanitizesReplaySecondsAndForwardsCommands() {
        val controller = EmbeddedPlayerControllerImpl()
        val replaySeconds = mutableListOf<Float>()
        var retryCount = 0
        var fullscreenExitCount = 0
        controller.bind(
            replayFrom = replaySeconds::add,
            retry = { retryCount += 1 },
            exitFullscreen = {
                fullscreenExitCount += 1
                true
            },
        )

        controller.replayFrom(12.25f)
        controller.replayFrom(-4f)
        controller.replayFrom(Float.NaN)
        controller.retry()

        assertEquals(listOf(12.25f, 0f, 0f), replaySeconds)
        assertEquals(1, retryCount)
        assertTrue(controller.exitFullscreen())
        assertEquals(1, fullscreenExitCount)
    }

    @Test
    fun staleControllerBindingCannotUnbindCurrentPlayer() {
        val controller = EmbeddedPlayerControllerImpl()
        val firstToken = controller.bind({}, {}, { false })
        var currentReplay: Float? = null
        val currentToken = controller.bind({ currentReplay = it }, {}, { true })

        controller.unbind(firstToken)
        controller.replayFrom(8f)
        assertEquals(8f, currentReplay)
        assertTrue(controller.exitFullscreen())

        controller.unbind(currentToken)
        currentReplay = null
        controller.replayFrom(9f)
        assertNull(currentReplay)
        assertFalse(controller.exitFullscreen())
    }

    @Test
    fun playbackFailuresExposeOnlySafeRetryPaths() {
        assertTrue(PlaybackFailure.InitializationTimeout.recoverable)
        assertTrue(PlaybackFailure.Initialization("offline").recoverable)
        assertTrue(PlaybackFailure.YouTube("HTML_5_PLAYER", recoverable = true).recoverable)
        assertFalse(PlaybackFailure.YouTube("VIDEO_NOT_FOUND", recoverable = false).recoverable)
        assertEquals(
            listOf(
                PlaybackState.IDLE,
                PlaybackState.READY,
                PlaybackState.PLAYING,
                PlaybackState.PAUSED,
                PlaybackState.BUFFERING,
                PlaybackState.ENDED,
                PlaybackState.ERROR,
            ),
            PlaybackState.entries,
        )
    }
}
