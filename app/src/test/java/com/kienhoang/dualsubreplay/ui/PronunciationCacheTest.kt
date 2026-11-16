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
        cache.markReady(word, language)
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
    fun theWordsOfALineAreKeptTogether() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        val first = record(cache, "春")
        val second = record(cache, "台湾")

        assertEquals(first, cache.lookup("春", "ja"))
        assertEquals(second, cache.lookup("台湾", "ja"))
    }

    @Test
    fun theOldestRecordingIsDeletedOnceTheCacheIsFull() {
        val cache = PronunciationCache(folder.newFolder("speech"), maxEntries = 2)
        val first = record(cache, "一")
        val second = record(cache, "二")
        // Hearing the first word again makes the second the oldest.
        cache.lookup("一", "ja")
        val third = record(cache, "三")

        assertFalse(second.exists())
        assertNull(cache.lookup("二", "ja"))
        assertEquals(first, cache.lookup("一", "ja"))
        assertEquals(third, cache.lookup("三", "ja"))
    }

    @Test
    fun aWordBeingRecordedIsHeldButNeverReplayedUntilReady() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        assertFalse(cache.holds("春", "ja"))

        cache.prepare("春", "ja").writeText("partial")
        assertTrue(cache.holds(" 春 ", "ja"))
        assertFalse(cache.holds("春", "en"))
        assertNull(cache.lookup("春", "ja"))

        cache.remove("春", "ja")
        assertFalse(cache.holds("春", "ja"))
    }

    @Test
    fun recordingAWordAgainReplacesItsFile() {
        val cache = PronunciationCache(folder.newFolder("speech"))
        val first = record(cache, "春")
        val again = record(cache, "春")

        assertFalse(first.exists())
        assertEquals(again, cache.lookup("春", "ja"))
    }

    @Test
    fun leftoverFilesFromAnEarlierRunAreRemoved() {
        val directory = folder.newFolder("speech")
        val stale = directory.resolve("word-9.wav").apply { writeText("old") }
        val cache = PronunciationCache(directory)

        record(cache, "春")
        record(cache, "夏")

        assertFalse(stale.exists())
        assertTrue(cache.lookup("春", "ja")!!.exists())
        assertTrue(cache.lookup("夏", "ja")!!.exists())
    }
}
