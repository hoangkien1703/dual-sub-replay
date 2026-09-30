package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PronunciationCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun record(
        cache: PronunciationCache,
        word: String,
        language: String = "ja",
    ) = cache.prepare(word, language).also {
        it.writeText("audio of $word")
        cache.markReady()
    }

    @Test
    fun theSameWordReplaysItsRecording() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        val audio = record(cache, "思います")

        assertEquals(audio, cache.lookup("思います", "ja"))
        assertEquals(audio, cache.lookup(" 思います ", "ja"))
        assertNull(cache.lookup("思います", "en"))
    }

    @Test
    fun recordingAnotherWordDeletesThePreviousOne() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        val first = record(cache, "春")
        val second = record(cache, "台湾")

        assertFalse(first.exists())
        assertNull(cache.lookup("春", "ja"))
        assertEquals(second, cache.lookup("台湾", "ja"))
    }

    @Test
    fun choosingAnotherWordDropsTheRecordingButKeepingTheSameWordDoesNot() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        val audio = record(cache, "春")

        cache.keepOnly("春", "ja")
        assertEquals(audio, cache.lookup("春", "ja"))

        cache.keepOnly("春ですね", "ja")
        assertFalse(audio.exists())
        assertNull(cache.lookup("春", "ja"))
    }

    @Test
    fun aWordBeingRecordedBelongsToItsSelectionUntilAnotherIsChosen() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        assertFalse(cache.holds("春", "ja"))

        // Recording has started but not finished: extending the selection to a phrase must stop it.
        cache.prepare("春", "ja")
        assertTrue(cache.holds(" 春 ", "ja"))
        assertFalse(cache.holds("春ですね", "ja"))
        assertFalse(cache.holds("春", "en"))

        cache.keepOnly("春ですね", "ja")
        assertFalse(cache.holds("春", "ja"))
    }

    @Test
    fun anUnfinishedRecordingIsNeverReplayed() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        cache.prepare("春", "ja").writeText("partial")

        assertNull(cache.lookup("春", "ja"))
    }

    @Test
    fun leftoverFilesFromAnEarlierRunAreRemoved() {
        val directory = folder.newFolder("speech")
        val stale = directory.resolve("word-9.wav").apply { writeText("old") }
        val cache = PronunciationCache(directory)

        record(cache, "春")

        assertFalse(stale.exists())
        assertTrue(cache.lookup("春", "ja")!!.exists())
    }
}
