package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiSubtitlesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun reply(
        answer: String,
        finishReason: String = "STOP",
    ): String =
        JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("text", "thinking about it").put("thought", true))
                                    .put(JSONObject().put("text", answer)),
                            ),
                        ).put("finishReason", finishReason),
                ),
            ).toString()

    private fun lines(vararg lines: Triple<Any, Any, String>): String =
        JSONObject()
            .put("language", "es")
            .put(
                "lines",
                JSONArray().apply {
                    lines.forEach { (start, end, text) -> put(JSONObject().put("start", start).put("end", end).put("text", text)) }
                },
            ).toString()

    @Test
    fun timesInEveryShapeGeminiWrites() {
        assertEquals(21_464L, parseAiSubtitleTime("00:21.464"))
        assertEquals(3_723_500L, parseAiSubtitleTime("1:02:03.5"))
        // gemini-2.5-flash wrote the milliseconds after a colon (2026-11-08).
        assertEquals(360_237L, parseAiSubtitleTime("06:00:237"))
        assertEquals(3_723_000L, parseAiSubtitleTime("01:02:03"))
        assertEquals(12_500L, parseAiSubtitleTime("12.5"))
        assertEquals(12_500L, parseAiSubtitleTime(12.5))
        assertEquals(1_250L, parseAiSubtitleTime("0:01,25"))
        assertNull(parseAiSubtitleTime("1:75.000"))
        assertNull(parseAiSubtitleTime("soon"))
        assertNull(parseAiSubtitleTime(null))
        assertNull(parseAiSubtitleTime(-3))
    }

    @Test
    fun readsTheAnswerWithoutThinkingAndKeepsVideoTimes() {
        val part =
            parseAiSubtitleReply(
                reply(lines(Triple("05:01.000", "05:03.500", "  Hola,   ¿qué tal? "), Triple("05:04.000", "05:05.000", "Bien."))),
                partStartMs = 300_000,
                partEndMs = 600_000,
            )
        assertEquals("es", part.language)
        assertEquals(
            listOf(RawCaptionCue(301_000, 303_500, "Hola, ¿qué tal?"), RawCaptionCue(304_000, 305_000, "Bien.")),
            part.cues,
        )
    }

    @Test
    fun timesCountedFromThePartAreMovedIntoTheVideo() {
        // Flash-Lite answered a part from 2:00 with times from 0:01.89 (2026-11-08).
        val part =
            parseAiSubtitleReply(
                reply(lines(Triple("00:01.890", "00:03.250", "Ay, qué horror."), Triple("00:47.960", "00:50.550", "Sonríe."))),
                partStartMs = 120_000,
                partEndMs = 420_000,
            )
        assertEquals(listOf(121_890L, 167_960L), part.cues.map { it.startMs })
    }

    @Test
    fun dropsEmptyAndImpossibleLinesAndFixesBackwardEnds() {
        val part =
            parseAiSubtitleReply(
                reply(
                    lines(
                        Triple("00:10.000", "00:09.000", "Backwards end"),
                        Triple("00:11.000", "00:12.000", "   "),
                        Triple("later", "00:13.000", "No start"),
                        Triple("00:11.500", "00:13.000", "Fine"),
                        Triple("09:00.000", "09:01.000", "Far outside the part"),
                    ),
                ),
                partStartMs = 0,
                partEndMs = 300_000,
            )
        assertEquals(listOf(RawCaptionCue(10_000, 11_500, "Backwards end"), RawCaptionCue(11_500, 13_000, "Fine")), part.cues)
    }

    @Test
    fun aCodeFenceOrABareListStillReads() {
        val fenced = parseAiSubtitleReply(reply("```json\n" + lines(Triple("0:01.0", "0:02.0", "Uno")) + "\n```"), 0, 300_000)
        assertEquals(1, fenced.cues.size)
        val bare = parseAiSubtitleReply(reply("""[{"start":"0:01.0","end":"0:02.0","text":"Uno"}]"""), 0, 300_000)
        assertEquals("", bare.language)
        assertEquals(1, bare.cues.size)
    }

    @Test
    fun aRefusedOrBrokenReplySaysWhich() {
        val refused =
            assertThrows(AiSubtitleReplyException::class.java) {
                parseAiSubtitleReply(reply("", finishReason = "RECITATION"), 0, 300_000)
            }
        assertTrue(refused.refused)
        val cut =
            assertThrows(AiSubtitleReplyException::class.java) {
                parseAiSubtitleReply(reply("""{"language":"es","lines":[{"start":"0:01"""), 0, 300_000)
            }
        assertFalse(cut.refused)
    }

    @Test
    fun theRequestNamesTheVideoAndThePart() {
        val body = JSONObject(aiSubtitleRequestBody("yjsAuMiLc_A", 300_000, 600_000))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val video = parts.getJSONObject(0)
        assertEquals("https://www.youtube.com/watch?v=yjsAuMiLc_A", video.getJSONObject("file_data").getString("file_uri"))
        assertEquals("300s", video.getJSONObject("video_metadata").getString("start_offset"))
        assertEquals("600s", video.getJSONObject("video_metadata").getString("end_offset"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("Do not translate"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals("MEDIA_RESOLUTION_LOW", config.getString("mediaResolution"))
        // No thinking setting: Flash-Lite answers 400 to some of them.
        assertFalse(config.has("thinkingConfig"))
    }

    @Test
    fun partsStartWhereTheVideoPlaysAndWrapAround() {
        assertEquals(300_000L, aiSubtitlePartStart(420_000))
        assertEquals(0L, aiSubtitlePartStart(-5))
        val order = mutableListOf<Long>()
        val done = mutableSetOf<Long>()
        while (true) {
            val next = nextAiSubtitlePart(firstStartMs = 600_000, done = done, endMs = 1_100_000) ?: break
            order += next
            done += next
        }
        assertEquals(listOf(600_000L, 900_000L, 0L, 300_000L), order)
    }

    @Test
    fun anUnknownLengthGoesForwardUntilTheEndIsFound() {
        assertEquals(300_000L, nextAiSubtitlePart(0, setOf(0L), endMs = null))
        // Gemini said the part from 10:00 is past the end.
        assertNull(nextAiSubtitlePart(0, setOf(0L, 300_000L), endMs = 600_000))
        assertEquals(0L, nextAiSubtitlePart(300_000, setOf(300_000L), endMs = 600_000))
    }

    @Test
    fun linesWrittenTwiceWherePartsMeetAreKeptOnce() {
        val track =
            AiSubtitleTrack(
                "yjsAuMiLc_A",
                "es",
                mapOf(
                    300_000L to listOf(RawCaptionCue(298_000, 302_000, "Second part, crossing back")),
                    0L to listOf(RawCaptionCue(1_000, 2_000, "Start"), RawCaptionCue(297_000, 301_500, "Crossing the edge")),
                ),
            )
        assertEquals(listOf("Start", "Crossing the edge"), track.cues().map { it.text })
        val result = track.asCaptionTrack("es")
        assertEquals(AI_SUBTITLE_TRACK_ID, result.trackId)
        assertFalse(result.isGenerated)
        assertFalse(result.lyrics)
        // The same lines saved as free song lyrics say so, for the header's "(song lyrics)".
        val lyrics = track.asCaptionTrack("es", SONG_LYRICS_TRACK_ID)
        assertEquals(SONG_LYRICS_TRACK_ID, lyrics.trackId)
        assertTrue(lyrics.lyrics)
    }

    @Test
    fun theStoreKeepsTracksAndForgetsBadFilesAndOldVideos() {
        val store = AiSubtitleStore(folder.root, maxEntries = 2)
        val track =
            AiSubtitleTrack("yjsAuMiLc_A", "es", mapOf(0L to listOf(RawCaptionCue(1_000, 2_000, "Hola"))), endMs = 170_000, complete = true)
        store.write(track)
        assertEquals(track, store.read("yjsAuMiLc_A"))
        assertNull(store.read("../../etc/x"))

        folder.newFile("brokenVideo.json").writeText("{not json")
        assertNull(store.read("brokenVideo"))

        store.write(track.copy(videoId = "bbbbbbbbbbb"))
        java.io.File(folder.root, "yjsAuMiLc_A.json").setLastModified(1_000_000)
        java.io.File(folder.root, "bbbbbbbbbbb.json").setLastModified(2_000_000)
        store.write(track.copy(videoId = "ccccccccccc"))
        val kept =
            folder.root
                .listFiles()!!
                .map { it.name }
                .filter { it.endsWith(".json") }
                .toSet()
        assertEquals(setOf("bbbbbbbbbbb.json", "ccccccccccc.json"), kept)
    }

    @Test
    fun songLyricsAndTheAiTranscriptAreSavedApartAndPickedEachOnItsOwn() {
        // Owner pick 2026-11-25: the Original language list switches between free lyrics and AI, and back.
        val ai = AiSubtitleStore(folder.newFolder("ai"))
        val lyrics = AiSubtitleStore(folder.newFolder("lyrics"))
        val saved = SavedTranscripts(ai, lyrics)
        val id = "dQw4w9WgXcQ"
        val realLine = mapOf(0L to listOf(RawCaptionCue(18_000, 21_000, "Real line")))
        val song = AiSubtitleTrack(id, "en", realLine, complete = true, lyrics = true)
        // Saved by v1.9.19 with the AI transcripts: it is the song lyrics, and moves over.
        ai.write(song)
        assertNull(saved.aiTranscript(id))
        assertEquals(SONG_LYRICS_TRACK_ID to song, saved.forPick(id, null))
        assertEquals(song, lyrics.read(id))
        assertNull(saved.forPick(id, AI_SUBTITLE_TRACK_ID))

        val misheard = mapOf(0L to listOf(RawCaptionCue(18_000, 21_000, "Misheard line")))
        val gemini = AiSubtitleTrack(id, "en", misheard, complete = true)
        ai.write(gemini)
        assertEquals(AI_SUBTITLE_TRACK_ID to gemini, saved.forPick(id, AI_SUBTITLE_TRACK_ID))
        assertEquals(SONG_LYRICS_TRACK_ID to song, saved.forPick(id, SONG_LYRICS_TRACK_ID))
        // Auto takes the AI transcript; one of YouTube's tracks takes neither.
        assertEquals(AI_SUBTITLE_TRACK_ID, saved.forPick(id, null)?.first)
        assertNull(saved.forPick(id, "a.en"))

        // A playlist's songs with Gemini writing the rest is the AI transcript, even before its first part.
        val songs = listOf(LyricChapter(0, 200_000, listOf(RawCaptionCue(1_000, 2_000, "Song one"))))
        val playlist = AiSubtitleTrack("bbbbbbbbbbb", "en", emptyMap(), endMs = 900_000, lyricChapters = songs)
        ai.write(playlist)
        assertEquals(AI_SUBTITLE_TRACK_ID to playlist, saved.forPick("bbbbbbbbbbb", null))
        assertNull(saved.songLyrics("bbbbbbbbbbb"))
    }

    @Test
    fun anUnfinishedTrackRoundTrips() {
        val track = AiSubtitleTrack("yjsAuMiLc_A", "", mapOf(300_000L to emptyList()), endMs = null, complete = false)
        assertEquals(track, decodeAiSubtitleTrack(encodeAiSubtitleTrack(track)))
        assertNull(decodeAiSubtitleTrack("""{"version":99}"""))
    }

    @Test
    fun youTubesOwnTracksAreKeptWithAnAiTranscript() {
        val youtube =
            listOf(CaptionLanguage("en", "English (auto-generated)", "a.en", generated = true), CaptionLanguage("es", "Spanish", ".es"))
        val track =
            AiSubtitleTrack("dQw4w9WgXcQ", "en", mapOf(0L to listOf(RawCaptionCue(18_000, 21_000, "Line"))), youtubeTracks = youtube)
        val decoded = decodeAiSubtitleTrack(encodeAiSubtitleTrack(track))!!
        assertEquals(youtube, decoded.youtubeTracks)
        assertEquals(youtube, decoded.asCaptionTrack("en").availableLanguages)
        assertEquals(AI_SUBTITLE_TRACK_ID, decoded.asCaptionTrack("en").trackId)
        // A file saved before the AI transcript existed has no YouTube tracks.
        val old = encodeAiSubtitleTrack(track.copy(youtubeTracks = emptyList())).replace(",\"youtubeTracks\":[]", "")
        assertEquals(emptyList<CaptionLanguage>(), decodeAiSubtitleTrack(old)!!.youtubeTracks)
    }

    @Test
    fun linesInAnotherLanguagesLettersAreNotTaken() {
        // Kien's Chinese song (2026-11-17): Gemini named zh-Hans and copied the Vietnamese subtitles on screen.
        val copied =
            """{"language":"zh-Hans","lines":[{"start":"0:20","end":"0:23","text":"chúng ta đều như nhau cả"},""" +
                """{"start":"0:24","end":"0:27","text":"Đoạn đường tương lai sẽ chẳng thiếu gì mưa gió"}]}"""
        val wrong = assertThrows(AiSubtitleReplyException::class.java) { parseAiSubtitleReply(reply(copied), 0, 300_000) }
        assertTrue(wrong.refused)
        val chinese = """{"language":"zh-Hans","lines":[{"start":"0:20","end":"0:23","text":"换种活法 自己变得快乐"}]}"""
        assertEquals("zh-Hans", parseAiSubtitleReply(reply(chinese), 0, 300_000).language)
        // English words in a K-pop line, and a language written in Latin letters, are fine.
        assertTrue(writtenInLanguage("ko", listOf(RawCaptionCue(0, 1_000, "Baby I love you 너를 사랑해"))))
        assertTrue(writtenInLanguage("vi", listOf(RawCaptionCue(0, 1_000, "chúng ta đều như nhau cả"))))
        assertFalse(writtenInLanguage("ja", listOf(RawCaptionCue(0, 1_000, "kimi no na wa"))))
    }

    @Test
    fun savedLinesInAnotherLanguagesLettersAreAskedAgain() {
        val wrong = listOf(RawCaptionCue(20_000, 23_000, "chúng ta đều như nhau cả"))
        val right = listOf(RawCaptionCue(320_000, 323_000, "换种活法 自己变得快乐"))
        val track = AiSubtitleTrack("aaaaaaaaaaa", "zh-Hans", mapOf(0L to wrong, 300_000L to right), endMs = 400_000, complete = true)
        // Saved before the check: the copied part is dropped, so the track is unfinished.
        val old = encodeAiSubtitleTrack(track).replace(",\"scriptChecked\":true", "")
        val decoded = decodeAiSubtitleTrack(old)!!
        assertEquals(setOf(300_000L), decoded.parts.keys)
        assertFalse(decoded.complete)
        // Saved after it: kept as written, so a part the check would drop is never asked again and again.
        assertEquals(track, decodeAiSubtitleTrack(encodeAiSubtitleTrack(track)))
    }

    @Test
    fun thePromptAsksForSungLinesToo() {
        val body = aiSubtitleRequestBody("dQw4w9WgXcQ", 0, 300_000)
        assertTrue(body.contains("spoken or sung"))
        assertTrue(body.contains("Leave out music without words"))
        assertTrue(body.contains("never copy text shown on screen"))
    }

    @Test
    fun aVideoIsCaptionlessWhenTheDownloadOrThePagePlayerFindsNoTrack() {
        val none = NoPublicCaptionsException("This video does not provide a public caption track.")
        // A music video the page plays while the app's download answers UNPLAYABLE (owner's phone, 2026-11-08).
        val unplayable = CaptionUnavailableException("Last failure: Web: Player returned UNPLAYABLE: Video unavailable")
        assertTrue(meansNoCaptionsAtAll(none, null))
        assertTrue(meansNoCaptionsAtAll(CaptionUnavailableException("wrapped", none), PageTranscriptProblem.NO_ANSWER))
        assertTrue(meansNoCaptionsAtAll(unplayable, PageTranscriptProblem.NO_TRACKS))
        assertFalse(meansNoCaptionsAtAll(unplayable, PageTranscriptProblem.WAITING_FOR_CAPTIONS))
        assertFalse(meansNoCaptionsAtAll(unplayable, PageTranscriptProblem.NO_PLAYER))
        assertFalse(meansNoCaptionsAtAll(unplayable, null))
    }
}
