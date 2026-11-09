package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.audioLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAudioScriptsTest {
    private val originCheck =
        "if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;"

    @Test
    fun theRecorderRunsOnlyOnYouTubeAndOnlyListens() {
        val script = webLineAudioScript(on = true, videoId = "abcdefghijk")
        assertTrue(originCheck in script)
        assertTrue("captureStream()" in script)
        assertTrue("recorder.ensure('abcdefghijk')" in script)
        // It never fetches, and never changes the video's sound, speed or time.
        for (forbidden in listOf(
            "fetch(",
            "XMLHttpRequest",
            ".muted =",
            ".volume =",
            "playbackRate =",
            "currentTime =",
            ".play(",
            ".pause(",
        )) {
            assertFalse(forbidden, forbidden in script)
        }
    }

    @Test
    fun anOddVideoIdTurnsTheRecorderOff() {
        assertTrue("if (!false)" in webLineAudioScript(on = true, videoId = "'); alert(1); ('"))
        assertTrue("if (!false)" in webLineAudioScript(on = false, videoId = "abcdefghijk"))
        assertTrue("if (!true)" in webLineAudioScript(on = true, videoId = "abcdefghijk"))
    }

    @Test
    fun aClipIsAskedForTheLineWithItsLeadAndTail() {
        val script = webLineAudioClipScript(audioLine("abcdefghijk", 10_000, 12_500)!!)
        assertTrue(originCheck in script)
        assertTrue("recorder.clip('abcdefghijk', 9.75, 12.9, 12.5)" in script)
    }

    @Test
    fun theRecorderRunsOnlyWhereItsClipsCanGo() {
        val on = StudySettings()
        assertTrue(lineAudioWanted(on, ankiDroidInstalled = true))
        assertFalse(lineAudioWanted(on, ankiDroidInstalled = false))
        assertTrue(lineAudioWanted(on.copy(sendTo = FlashcardApp.ANKICONNECT), ankiDroidInstalled = false))
        assertFalse(lineAudioWanted(on.copy(cardAudio = false), ankiDroidInstalled = true))
        assertFalse(lineAudioWanted(on.copy(ankiDroid = false), ankiDroidInstalled = true))
    }

    @Test
    fun wordSoundNamesNeedNoEscaping() {
        val name = wordAudioName("reluctant", "en")
        assertTrue(name.matches(Regex("word-[0-9a-f]{16}")))
        assertEquals(name, wordAudioName("reluctant", "en"))
        assertFalse(name == wordAudioName("reluctant", "ru"))
    }
}
