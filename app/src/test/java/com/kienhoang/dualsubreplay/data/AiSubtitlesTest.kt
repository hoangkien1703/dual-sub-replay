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
    fun anUnfinishedTrackRoundTrips() {
        val track = AiSubtitleTrack("yjsAuMiLc_A", "", mapOf(300_000L to emptyList()), endMs = null, complete = false)
        assertEquals(track, decodeAiSubtitleTrack(encodeAiSubtitleTrack(track)))
        assertNull(decodeAiSubtitleTrack("""{"version":99}"""))
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
