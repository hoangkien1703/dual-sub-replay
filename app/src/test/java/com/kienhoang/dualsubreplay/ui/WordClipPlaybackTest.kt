package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.*
import org.junit.Assert.*
import org.junit.Test

class WordClipPlaybackTest {
    private fun card() = savedWordFrom(LearningWordSelection(AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "a word", null), false), "từ", true, false)
    @Test fun clipWaitsForMatchingVideoAndDoesNotRepeatOnPolling() {
        val controller = YouTubeWebController()
        val scripts = mutableListOf<String>()
        controller.bindScripts(scripts::add)
        controller.replayClip(card())
        scripts.clear()
        controller.observePage("https://m.youtube.com/watch?v=aaaaaaaaaaa")
        assertTrue(scripts.isEmpty())
        controller.observePage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, scripts.size)
        controller.observePage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, scripts.size)
        controller.observePage("https://m.youtube.com/")
        assertEquals(webPauseScript(), scripts.last())
    }
    @Test fun dismissalCancelsPendingClipAndScriptsGuardOriginAndEndBoundary() {
        val controller = YouTubeWebController()
        val scripts = mutableListOf<String>()
        controller.bindScripts(scripts::add)
        controller.replayClip(card()); controller.pause(); scripts.clear()
        controller.observePage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(scripts.isEmpty())
        val script = webClipReplayScript("dQw4w9WgXcQ", 1000, 3000)
        assertTrue(script.contains("window.location.protocol === 'https:'"))
        assertTrue(script.contains("host.endsWith('.youtube.com')"))
        assertTrue(script.contains("video.currentTime >= 3.0"))
        assertTrue(script.contains(".ad-showing"))
    }
}
