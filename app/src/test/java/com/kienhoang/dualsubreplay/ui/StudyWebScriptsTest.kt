package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyWebScriptsTest {
    private val scripts =
        listOf(
            webSeekPausedScript(3.5f),
            webGapSpeedScript(2f, 1_500),
            webGapSpeedScript(null, 0),
            webBlurBoxScript(true),
            webBlurBoxScript(false),
        )

    @Test
    fun studyScriptsRunOnlyOnYouTubeAndTouchOnlyThePageVideoOrTheirBox() {
        scripts.forEach { script ->
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("host.endsWith('.youtube.com')"))
            assertFalse(script.contains("fetch("))
            assertFalse(script.contains("preventDefault"))
            assertFalse(script.contains("setPlaybackRate"))
        }
        assertTrue(webSeekPausedScript(Float.NaN).contains("video.currentTime = 0.0;"))
        assertTrue(webSeekPausedScript(-2f).contains("video.currentTime = 0.0;"))
    }

    @Test
    fun skipSilenceKeepsTheUsersSpeedAndBringsItBackByItself() {
        val fast = webGapSpeedScript(9f, 10_000_000)
        assertTrue("At most 4x", fast.contains("const rate = 4.0;"))
        assertTrue("The page's own restore waits at most 10 minutes", fast.contains("setTimeout(restore, 600000)"))
        assertTrue(fast.contains("if (typeof video.__dualSubGapRate !== 'number') video.__dualSubGapRate = video.playbackRate;"))
        val restore = webGapSpeedScript(null, 0)
        assertTrue(restore.contains("const rate = null;"))
        // A speed the user picked meanwhile stays.
        assertTrue(restore.contains("if (video.playbackRate === video.__dualSubGapFast) video.playbackRate = video.__dualSubGapRate;"))
    }

    @Test
    fun theBlurBoxKeepsItsTouchesAndRemembersItsPlace() {
        val box = webBlurBoxScript(true)
        assertTrue(box.contains("document.getElementById('movie_player')"))
        assertTrue(box.contains("backdrop-filter:blur(14px)"))
        assertTrue(box.contains("event.stopPropagation()"))
        assertTrue(box.contains("touch-action:none"))
        assertTrue(box.contains("window.localStorage.setItem(key, JSON.stringify(place))"))
        assertTrue(webBlurBoxScript(false).contains("if (!false) {"))
        assertTrue(box.contains("if (!true) {"))
    }
}
