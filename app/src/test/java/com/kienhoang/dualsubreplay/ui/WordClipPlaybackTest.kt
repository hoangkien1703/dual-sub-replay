package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.*
import org.junit.Assert.*
import org.junit.Test

class WordClipPlaybackTest {
    private fun card() = savedWordFrom(LearningWordSelection(AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "a word", null), false), "từ", true)
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

    @Test fun aReplayForTheCardsSoundIsMutedAndAlwaysUnmutesAgain() {
        // lion737, #81: captureStream keeps a muted video's sound (emulator check 2026-11-16), so the replay is silent.
        val controller = YouTubeWebController()
        val scripts = mutableListOf<String>()
        controller.bindScripts(scripts::add)
        controller.replayClip(card(), muted = true)
        controller.observePage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        val muted = scripts.last()
        assertTrue(muted.contains("const muted = true;"))
        assertTrue(muted.contains("video.muted = true;"))
        assertTrue(muted.contains("video.muted = wasMuted;"))
        assertTrue(webClipReplayScript("dQw4w9WgXcQ", 1000, 3000).contains("const muted = false;"))
        // Pausing, or a new clip, gives the sound back.
        assertTrue(webPauseScript().contains("window.__dualSubClipUnmute()"))
        assertTrue(webSeekPausedScript(2f).contains("window.__dualSubClipUnmute()"))
        // The learner's own replay from saved words keeps its sound.
        controller.replayClip(card())
        controller.observePage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(scripts.last().contains("const muted = false;"))
    }
}
