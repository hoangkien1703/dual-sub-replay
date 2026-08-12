package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun singleWebSurfaceAcceptsOnlyYouTubeMainFrameUrls() {
        assertTrue(isYouTubeWebUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(isYouTubeWebUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(isYouTubeWebUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(isYouTubeWebUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(isYouTubeWebUrl("javascript:alert(1)"))
    }

    @Test
    fun playbackSnapshotParsesWebViewJavascriptResult() {
        val raw = "\"{\\\"url\\\":\\\"https://m.youtube.com/watch?v=dQw4w9WgXcQ\\\",\\\"currentSecond\\\":12.5}\""

        assertEquals(
            WebPlaybackSnapshot(
                url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                currentSecond = 12.5f,
            ),
            parseWebPlaybackSnapshot(raw),
        )
        assertNull(parseWebPlaybackSnapshot("null"))
        assertNull(parseWebPlaybackSnapshot("not-json"))
    }

    @Test
    fun replayScriptSeeksAndResumesTheNativePageVideo() {
        val script = webReplayScript(42.25f)

        assertTrue(script.contains("document.querySelectorAll('video')"))
        assertTrue(script.contains("video.currentTime = 42.25"))
        assertTrue(script.contains("video.play()"))
        assertTrue(webReplayScript(-2f).contains("video.currentTime = 0.0"))
        assertTrue(webReplayScript(Float.NaN).contains("video.currentTime = 0.0"))
    }

    @Test
    fun controllerSanitizesReplaySecondsAndIgnoresStaleUnbinds() {
        val controller = YouTubeWebController()
        val firstValues = mutableListOf<Float>()
        val firstToken = controller.bind(firstValues::add)
        val currentValues = mutableListOf<Float>()
        val currentToken = controller.bind(currentValues::add)

        controller.unbind(firstToken)
        controller.replayFrom(12.25f)
        controller.replayFrom(-4f)
        controller.replayFrom(Float.NaN)

        assertTrue(firstValues.isEmpty())
        assertEquals(listOf(12.25f, 0f, 0f), currentValues)

        controller.unbind(currentToken)
        controller.replayFrom(9f)
        assertEquals(listOf(12.25f, 0f, 0f), currentValues)
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
    fun activeSubtitleStaysInPlaceUntilItReachesTheBottomOfThePanel() {
        val visibleItems = listOf(4, 5, 6, 7)

        assertFalse(shouldPromoteActiveSubtitle(5, visibleItems))
        assertFalse(shouldPromoteActiveSubtitle(6, visibleItems))
        assertTrue(shouldPromoteActiveSubtitle(7, visibleItems))
    }

    @Test
    fun activeSubtitlePromotesAfterASeekBeyondTheVisiblePanel() {
        assertTrue(shouldPromoteActiveSubtitle(12, listOf(4, 5, 6, 7)))
        assertFalse(shouldPromoteActiveSubtitle(-1, listOf(4, 5, 6, 7)))
        assertFalse(shouldPromoteActiveSubtitle(7, emptyList()))
    }
}
