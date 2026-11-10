package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.io.path.createTempDirectory

/** Pictures on Anki cards (spec docs/specs/2026-11-10-anki-card-picture-and-front.md). */
class LinePictureTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun word(
        videoId: String? = "abcdefghijk",
        startMs: Long = 10_000,
        endMs: Long = 70_000,
    ) = SavedWord(
        id = "1",
        word = "cat",
        reading = null,
        wordLanguage = "en",
        meaningLanguage = "vi",
        meaning = "mèo",
        sentence = "The cat sleeps.",
        translatedSentence = null,
        videoId = videoId,
        startMs = startMs,
        endMs = endMs,
        translated = false,
    )

    @Test
    fun anyVideoLineGetsAPictureAlsoOneTooLongToRecord() {
        assertEquals(AudioLine("abcdefghijk", 10_000, 70_000), pictureLineOf(word()))
        assertNull(audioLineOf(word()))
        assertNull(pictureLineOf(word(videoId = null)))
        assertNull(pictureLineOf(word(startMs = 5_000, endMs = 5_000)))
        assertEquals("abcdefghijk-10000-70000.jpg", AudioLine("abcdefghijk", 10_000, 70_000).pictureName)
    }

    @Test
    fun aStillIsTakenWhileTheLineIsOnScreenOrJustEnded() {
        val line = AudioLine("abcdefghijk", 10_000, 12_000)
        assertFalse(linePictureTime(line, 9_999))
        assertTrue(linePictureTime(line, 10_000))
        assertTrue(linePictureTime(line, 12_000 + LINE_AUDIO_TAIL_MS))
        assertFalse(linePictureTime(line, 12_001 + LINE_AUDIO_TAIL_MS))
    }

    @Test
    fun thePageAnswerIsAJpegDataUrlOrNothing() {
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
        assertArrayEquals(jpeg, parseLinePicture("\"$url\""))
        assertNull(parseLinePicture(null))
        assertNull(parseLinePicture("null"))
        assertNull(parseLinePicture("\"data:image/png;base64,${Base64.getEncoder().encodeToString(jpeg)}\""))
        assertNull(parseLinePicture("\"data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))}\""))
        assertNull(parseLinePicture("\"data:image/jpeg;base64,%%%\""))
    }

    @Test
    fun theThumbnailComesOnlyFromYouTubesImageHost() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", videoThumbnailUrl("abcdefghijk"))
        assertNull(videoThumbnailUrl("../../evil"))
    }

    @Test
    fun theStoreKeepsJpegsAndDropsOldOnesNoWordNeeds() {
        val directory = createTempDirectory("line-pictures").toFile()
        val store = LinePictureStore(directory)
        val kept = AudioLine("abcdefghijk", 1_000, 2_000)
        val gone = AudioLine("abcdefghijk", 3_000, 4_000)
        assertNull(store.save(kept, byteArrayOf(1, 2, 3, 4)))
        assertNotNull(store.save(kept, jpeg))
        assertNotNull(store.save(gone, jpeg))
        assertNotNull(store.saveThumbnail("abcdefghijk", jpeg))
        assertArrayEquals(jpeg, store.file(kept)!!.readBytes())
        val later = System.currentTimeMillis() + LINE_AUDIO_UNUSED_MS + 1_000
        store.prune(setOf(kept), later)
        assertNotNull(store.file(kept))
        assertNotNull(store.thumbnail("abcdefghijk"))
        assertNull(store.file(gone))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
