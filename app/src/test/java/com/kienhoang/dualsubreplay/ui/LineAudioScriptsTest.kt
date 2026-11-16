package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AudioLine
import com.kienhoang.dualsubreplay.data.CardFront
import com.kienhoang.dualsubreplay.data.CardOptions
import com.kienhoang.dualsubreplay.data.CardPlace
import com.kienhoang.dualsubreplay.data.CardReadAloud
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
    fun theRecorderWakesWhenThePageVideoStartsPlaying() {
        val script = webLineAudioScript(on = true, videoId = "abcdefghijk")
        // A replay after a long pause must not wait for the app's next check (lion737, #81, 2026-11-14).
        assertTrue("document.addEventListener('play', wake, true);" in script)
        assertTrue("document.addEventListener('playing', wake, true);" in script)
        assertTrue("document.removeEventListener('play', wake, true);" in script)
        assertTrue("state.context.state !== 'running' && state.context.state !== 'closed'" in script)
    }

    @Test
    fun theRecorderFollowsTheNewestTrackWhenTheVideoSourceChanges() {
        val script = webLineAudioScript(on = true, videoId = "abcdefghijk")
        // After an ad or another video in the same element, the old track only gives silence (#81, 2026-11-14).
        assertTrue("state.stream.addEventListener('addtrack', follow);" in script)
        assertTrue("tracks[tracks.length - 1]" in script)
        assertTrue("else follow();" in script)
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
        assertFalse(lineAudioWanted(on.copy(cardSentenceSound = CardPlace.OFF), ankiDroidInstalled = true))
        // The word's voice alone needs no recording.
        assertTrue(lineAudioWanted(on.copy(cardWordSound = CardPlace.OFF), ankiDroidInstalled = true))
        assertFalse(lineAudioWanted(on.copy(ankiDroid = false), ankiDroidInstalled = true))
    }

    @Test
    fun aStillIsTakenOnlyOnYouTubeFromTheLinesVideoAndOnlyDrawn() {
        val script = webLinePictureScript(AudioLine("abcdefghijk", 10_000, 12_500))
        assertTrue(originCheck in script)
        assertTrue("pageId !== 'abcdefghijk'" in script)
        assertTrue("video.currentTime < 10.0 || video.currentTime > 12.9" in script)
        assertTrue(".ad-showing" in script)
        assertTrue("toDataURL('image/jpeg'" in script)
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
        // An odd id never reaches the script, and then no still matches.
        val odd = webLinePictureScript(AudioLine("'); alert(1); ('", 10_000, 12_500))
        assertFalse("alert" in odd)
        assertTrue("if ('' === ''" in odd)
    }

    @Test
    fun stillsAreTakenOnlyWhereTheyCanGo() {
        // Off until the learner turns it on (Kien, 2026-11-10).
        assertFalse(linePictureWanted(StudySettings(), ankiDroidInstalled = true))
        val on = StudySettings(cardPicture = CardPlace.BACK)
        assertTrue(linePictureWanted(on, ankiDroidInstalled = true))
        assertFalse(linePictureWanted(on, ankiDroidInstalled = false))
        assertTrue(linePictureWanted(on.copy(sendTo = FlashcardApp.ANKICONNECT), ankiDroidInstalled = false))
        assertFalse(linePictureWanted(on.copy(cardPicture = CardPlace.OFF), ankiDroidInstalled = true))
        assertTrue(linePictureWanted(on.copy(cardSentenceSound = CardPlace.OFF), ankiDroidInstalled = true))
        assertEquals(
            CardOptions(CardPlace.FRONT, CardPlace.BACK, CardPlace.OFF, CardFront.WORD, readAloud = CardReadAloud()),
            on.copy(cardPicture = CardPlace.OFF, cardFront = CardFront.WORD).cardOptions,
        )
    }

    @Test
    fun wordSoundNamesNeedNoEscaping() {
        val name = wordAudioName("reluctant", "en")
        assertTrue(name.matches(Regex("word-[0-9a-f]{16}")))
        assertEquals(name, wordAudioName("reluctant", "en"))
        assertFalse(name == wordAudioName("reluctant", "ru"))
    }
}
