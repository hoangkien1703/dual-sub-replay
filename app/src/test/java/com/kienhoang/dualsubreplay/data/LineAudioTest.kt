package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory

class LineAudioTest {
    private fun word(
        videoId: String? = "abcdefghijk",
        startMs: Long = 10_000,
        endMs: Long = 12_500,
    ) = SavedWord(
        id = "1",
        word = "reluctant",
        reading = null,
        wordLanguage = "en",
        meaningLanguage = "az",
        meaning = "istəksiz",
        sentence = "She was really reluctant to say yes.",
        translatedSentence = null,
        videoId = videoId,
        startMs = startMs,
        endMs = endMs,
        translated = true,
    )

    @Test
    fun aClipIsOneLineOfOneVideoWithALeadAndATail() {
        val line = audioLineOf(word())!!
        assertEquals("abcdefghijk-10000-12500.m4a", line.fileName)
        assertEquals(10_000 - LINE_AUDIO_LEAD_MS, line.windowStartMs)
        assertEquals(12_500 + LINE_AUDIO_TAIL_MS, line.windowEndMs)
        // The lead never goes before the video's start.
        assertEquals(0L, audioLine("abcdefghijk", 100, 900)!!.windowStartMs)
    }

    @Test
    fun wordsWithoutAUsableLineGetNoClip() {
        assertNull(audioLineOf(word(videoId = null)))
        assertNull(audioLineOf(word(videoId = "../../etc/x")))
        assertNull(audioLineOf(word(startMs = 5_000, endMs = 5_000)))
        assertNull(audioLineOf(word(startMs = 0, endMs = LINE_AUDIO_MAX_MS + 1)))
    }

    @Test
    fun aLineReplayedForItsSoundStartsBeforeTheLeadAndEndsAfterTheTail() {
        val clip = soundReplayClip(word())!!
        val line = audioLineOf(word())!!
        assertEquals(line.windowStartMs - LINE_AUDIO_REPLAY_EARLY_MS, clip.startMs)
        assertEquals(line.windowEndMs, clip.endMs)
        // Paused where the replay stops, the whole line with its tail is there to cut.
        assertTrue(lineAudioReady(line, clip.endMs, paused = true))
        assertTrue(lineAudioReady(line, clip.endMs))
        assertEquals(0L, soundReplayClip(word(startMs = 300, endMs = 2_000))!!.startMs)
        assertNull(soundReplayClip(word(videoId = null)))
    }

    @Test
    fun aLineIsCutOnceItHasPlayedAndWhileThePageStillHoldsIt() {
        val line = audioLine("abcdefghijk", 10_000, 12_500)!!
        assertFalse(lineAudioReady(line, 11_000))
        assertFalse(lineAudioReady(line, line.windowEndMs - 1))
        assertTrue(lineAudioReady(line, line.windowEndMs))
        assertTrue(lineAudioReady(line, line.windowEndMs + 30_000))
        assertFalse(lineAudioReady(line, line.windowEndMs + LINE_AUDIO_KEPT_MS))
        // Paused at the line's end (Pause after each line): the tail never plays, so the line is ready without it.
        assertFalse(lineAudioReady(line, 12_499, paused = true))
        assertTrue(lineAudioReady(line, 12_500, paused = true))
    }

    @Test
    fun thePagesAnswerBecomesSamples() {
        val bytes = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val text = JSONObject().put("rate", 24_000).put("pcm", Base64.getEncoder().encodeToString(bytes)).toString()
        val pcm = parseLinePcm(JSONObject.quote(text))!!
        assertEquals(24_000, pcm.sampleRate)
        assertEquals(listOf<Short>(1, Short.MAX_VALUE, Short.MIN_VALUE), pcm.samples.toList())
        assertNull(parseLinePcm("null"))
        assertNull(parseLinePcm(null))
        assertNull(parseLinePcm(JSONObject.quote("""{"rate":1,"pcm":"AAAA"}""")))
        assertNull(parseLinePcm(JSONObject.quote("""{"rate":24000,"pcm":"***"}""")))
        assertNull(parseLinePcm(JSONObject.quote("not json")))
    }

    @Test
    fun waitingCardsSurviveAsTextAndOldOnesGo() {
        val line = audioLine("abcdefghijk", 1_000, 2_000)!!
        val first = PendingCardAudio(PENDING_ANKIDROID, 7, line, ANKI_SENTENCE_AUDIO_FIELD, "", 100)
        val second = PendingCardAudio(PENDING_ANKICONNECT, 8, line, "Back", "text<br>", 200)
        assertEquals(listOf(first, second), parsePendingCardAudio(pendingCardAudioJson(listOf(first, second))))
        assertEquals(emptyList<PendingCardAudio>(), parsePendingCardAudio("not json"))
        assertEquals(
            emptyList<PendingCardAudio>(),
            parsePendingCardAudio("""[{"app":"other","note":1,"video":"abcdefghijk","start":0,"end":1,"field":"x"}]"""),
        )
        // The same note waits once; entries older than two weeks are dropped.
        val again = first.copy(addedAtMs = 300)
        assertEquals(listOf(second, again), withPendingCardAudio(listOf(first, second), again, 300))
        assertEquals(emptyList<PendingCardAudio>(), withPendingCardAudio(listOf(first), null, 100 + PENDING_AUDIO_MAX_AGE_MS))
        val many = (1..PENDING_AUDIO_MAX + 5).map { first.copy(noteId = it.toLong()) }
        assertEquals(PENDING_AUDIO_MAX, withPendingCardAudio(many, null, 100).size)
    }

    @Test
    fun unusedClipsGoAfterADay() {
        val directory = createTempDirectory("line-audio").toFile()
        val store = LineAudioStore(directory)
        val kept = audioLine("abcdefghijk", 1_000, 2_000)!!
        val unused = audioLine("abcdefghijk", 3_000, 4_000)!!
        val fresh = audioLine("abcdefghijk", 5_000, 6_000)!!
        val now = 10 * LINE_AUDIO_UNUSED_MS
        listOf(kept, unused, fresh).forEach { line ->
            File(directory, line.fileName).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(if (line == fresh) now else now - LINE_AUDIO_UNUSED_MS - 1)
            }
        }
        assertTrue(store.has(kept))
        store.prune(setOf(kept), now)
        assertTrue(store.has(kept))
        assertFalse(store.has(unused))
        assertTrue(store.has(fresh))
    }

    @Test
    fun aSilentClipNeverGoesOnACard() {
        assertFalse(audible(LinePcm(24_000, ShortArray(24_000))))
        assertFalse(audible(LinePcm(24_000, ShortArray(24_000) { if (it % 2 == 0) 40 else -40 })))
        assertTrue(audible(LinePcm(24_000, ShortArray(24_000) { if (it == 12_000) -900 else 0 })))
    }

    @Test
    fun aClipIsLentAsAudioMp4SoAnkiDroidKeepsItsM4aName() {
        assertEquals("audio/mp4", cardMediaType("abcdefghijk-10000-12500.m4a"))
        assertEquals("audio/mp4", cardMediaType("CLIP.M4A"))
        // The word's sound and the picture keep Android's own types.
        assertNull(cardMediaType("word.wav"))
        assertNull(cardMediaType("word.mp3"))
        assertNull(cardMediaType("abcdefghijk-10000-12500.jpg"))
        assertNull(cardMediaType(null))
    }
}
