package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression coverage for the August 2026 YouTube caption retrieval incident. */
class YouTubeCaptionProviderTest {
    @Test
    fun acceptsOnlyTrustedHttpsYouTubeCaptionHosts() {
        assertNotNull(trustedYouTubeCaptionUrl("https://www.youtube.com/api/timedtext?v=test"))
        assertNotNull(trustedYouTubeCaptionUrl("https://video.google.youtube.com/api/timedtext"))

        assertNull(trustedYouTubeCaptionUrl("http://www.youtube.com/api/timedtext"))
        assertNull(trustedYouTubeCaptionUrl("https://youtube.com.evil.test/api/timedtext"))
        assertNull(trustedYouTubeCaptionUrl("https://youtube.com@evil.test/api/timedtext"))
        assertNull(trustedYouTubeCaptionUrl("https://youtube.com:444/api/timedtext"))
    }

    @Test
    fun requestsWordTimedFormatsBeforeLegacyFallback() {
        val urls = captionCandidateUrls(
            "https://www.youtube.com/api/timedtext?v=test&lang=en&fmt=srv3",
        )

        assertEquals(listOf("json3", "srv3", null), urls.map { it.queryParameter("fmt") })
        assertEquals(listOf("test", "test", "test"), urls.map { it.queryParameter("v") })
    }

    @Test
    fun extractsCurrentWebClientVersionFromWatchPage() {
        val html = """<script>ytcfg.set({"INNERTUBE_CLIENT_VERSION":"2.20260826.01.00"})</script>"""

        assertEquals("2.20260826.01.00", extractWebInnertubeClientVersion(html))
        assertNull(extractWebInnertubeClientVersion("<html>missing config</html>"))
    }

    @Test
    fun retainsLegacyClientContextsAlongsideNewFallbacks() {
        val clients = youtubePlayerClients("2.20260826.01.00")
        val android = clients.first { it.clientName == "ANDROID" }
        assertEquals("3", android.clientNumber)
        assertEquals("21.26.364", android.clientVersion)
        assertEquals("21.26.4", clients.first { it.clientName == "IOS" }.clientVersion)
        assertEquals("2.20260826.01.00", clients.last { it.clientName == "WEB" }.clientVersion)
    }

    @Test
    fun fallsBackToKnownWebVersionWhenWatchConfigIsUnavailable() {
        val clients = youtubePlayerClients(null)

        assertEquals("2.20260708.00.00", clients.last().clientVersion)
    }

    @Test
    fun capsEachNetworkRequestBelowTheWholeLookupDeadline() {
        val perRequestNanos = TimeUnit.MILLISECONDS.toNanos(YOUTUBE_REQUEST_TIMEOUT_MS)

        assertEquals(
            perRequestNanos,
            boundedYouTubeRequestTimeoutNanos(perRequestNanos * 4),
        )
        assertEquals(
            123L,
            boundedYouTubeRequestTimeoutNanos(123L),
        )
    }

    @Test
    fun rejectsNonPositiveRemainingRequestTime() {
        assertThrows(IllegalArgumentException::class.java) {
            boundedYouTubeRequestTimeoutNanos(0L)
        }
    }

    @Test
    fun readsResponsesAtOrBelowTheLimit() {
        val body = "captions".toByteArray(StandardCharsets.UTF_8)

        assertEquals(
            "captions",
            readUtf8WithLimit(ByteArrayInputStream(body), maxBytes = body.size),
        )
    }

    @Test
    fun rejectsResponsesBeforeReadingPastTheLimit() {
        val body = "oversized".toByteArray(StandardCharsets.UTF_8)

        assertThrows(ResponseLimitExceededException::class.java) {
            readUtf8WithLimit(ByteArrayInputStream(body), maxBytes = body.size - 1)
        }
    }

    @Test
    fun autoModePicksSpokenLanguageOverUploadedTranslation() {
        val renderer = captionRenderer(manual("ar"), generated("en"))

        assertEquals("a.en", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun autoModePrefersCreatorTrackInSpokenLanguage() {
        val renderer = captionRenderer(manual("ar"), manual("en"), generated("en"))

        assertEquals(".en", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun unavailablePreferenceFallsBackToSpokenLanguage() {
        val renderer = captionRenderer(manual("ar"), generated("en"))

        assertEquals("a.en", selectCaptionTrack(renderer, listOf("vi"))?.optString("vssId"))
    }

    @Test
    fun explicitPreferenceStillWinsOverSpokenLanguage() {
        val renderer = captionRenderer(manual("ar"), generated("en"))

        assertEquals(".ar", selectCaptionTrack(renderer, listOf("ar"))?.optString("vssId"))
    }

    @Test
    fun defaultAudioTrackDecidesBetweenSeveralGeneratedTracks() {
        val renderer =
            captionRenderer(generated("es"), generated("en-US"), manual("ar"))
                .put(
                    "audioTracks",
                    JSONArray()
                        .put(JSONObject().put("captionTrackIndices", JSONArray().put(0).put(2)))
                        .put(JSONObject().put("captionTrackIndices", JSONArray().put(1).put(2))),
                ).put("defaultAudioTrackIndex", 1)

        assertEquals("en", spokenCaptionLanguage(renderer))
        assertEquals("a.en-US", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun defaultCaptionTrackIsSpokenLanguageWhenAutoCaptionsAreOff() {
        val renderer =
            captionRenderer(manual("ar"), manual("en"))
                .put("audioTracks", JSONArray().put(JSONObject().put("defaultCaptionTrackIndex", 1)))

        assertEquals(".en", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun autoGeneratedTrackBeatsMislabelledAudioAndEnglishDefault() {
        // Shape of a real Japanese learning video: audio tagged en-US, English default caption.
        val renderer =
            captionRenderer(manual("ar"), manual("en"), manual("ja"), generated("ja"))
                .put(
                    "audioTracks",
                    JSONArray().put(
                        JSONObject()
                            .put("audioTrackId", "en-US.4")
                            .put("defaultCaptionTrackIndex", 1)
                            .put("captionTrackIndices", JSONArray(listOf(0, 1, 2, 3))),
                    ),
                )

        assertEquals("ja", spokenCaptionLanguage(renderer))
        assertEquals(".ja", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun audioTrackLanguageBeatsEnglishDefaultWithoutAutoCaptions() {
        val renderer =
            captionRenderer(manual("en"), manual("ja"))
                .put(
                    "audioTracks",
                    JSONArray().put(JSONObject().put("audioTrackId", "ja.4").put("defaultCaptionTrackIndex", 0)),
                )

        assertEquals(".ja", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun keepsFirstCreatorTrackWithoutAnyLanguageSignal() {
        val renderer = captionRenderer(manual("ar"), manual("en"))

        assertNull(spokenCaptionLanguage(renderer))
        assertEquals(".ar", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
    }

    @Test
    fun japaneseTitleBeatsAnEnglishDefaultCaption() {
        // The owner's screenshot: only creator-written English and Japanese tracks, English default.
        val renderer =
            captionRenderer(manual("en"), manual("ja"))
                .put("audioTracks", JSONArray().put(JSONObject().put("defaultCaptionTrackIndex", 0)))
        val details = JSONObject().put("title", "昔の人は江戸から京都まで歩いて行った")

        assertEquals("ja", spokenCaptionLanguage(renderer, details))
        assertEquals(".ja", selectCaptionTrack(renderer, emptyList(), details)?.optString("vssId"))
    }

    @Test
    fun titleScriptBeatsMislabelledAudioButNotTheSpeechRecognizer() {
        val details = JSONObject().put("title", "東京の夜を歩く")
        val mislabelled =
            captionRenderer(manual("en"), manual("ja"))
                .put("audioTracks", JSONArray().put(JSONObject().put("audioTrackId", "en-US.4")))
        val recognized = captionRenderer(manual("en"), manual("ja"), generated("en"))

        assertEquals("ja", spokenCaptionLanguage(mislabelled, details))
        assertEquals("en", spokenCaptionLanguage(recognized, details))
    }

    @Test
    fun titleScriptCountsOnlyForALanguageWithATrack() {
        val renderer =
            captionRenderer(manual("en"), manual("es"))
                .put("audioTracks", JSONArray().put(JSONObject().put("defaultCaptionTrackIndex", 0)))

        assertEquals("en", spokenCaptionLanguage(renderer, JSONObject().put("title", "日本語の動画")))
    }

    @Test
    fun aMostlyJapaneseDescriptionHelpsWhenTheTitleIsEnglish() {
        val renderer = captionRenderer(manual("en"), manual("ja"))
        val japanese = JSONObject().put("title", "Walk vlog").put("shortDescription", "今日は江戸から京都まで歩いてみました。")
        val mixed = JSONObject().put("title", "Japan trip").put("shortDescription", "My trip to Tokyo 東京 and Kyoto 京都, with lots of fun")

        assertEquals("ja", spokenCaptionLanguage(renderer, japanese))
        assertNull(spokenCaptionLanguage(renderer, mixed))
    }

    @Test
    fun learningLanguageDecidesOnlyWhenTheVideoGivesNoBetterSign() {
        val noSignal =
            captionRenderer(manual("en"), manual("ja"))
                .put("audioTracks", JSONArray().put(JSONObject().put("defaultCaptionTrackIndex", 0)))
        val labelledAudio =
            captionRenderer(manual("en"), manual("ja"))
                .put("audioTracks", JSONArray().put(JSONObject().put("audioTrackId", "en.4")))

        assertEquals(".ja", selectCaptionTrack(noSignal, emptyList(), null, "ja")?.optString("vssId"))
        assertEquals(".en", selectCaptionTrack(noSignal, emptyList(), null, "ko")?.optString("vssId"))
        assertEquals(".en", selectCaptionTrack(labelledAudio, emptyList(), null, "ja")?.optString("vssId"))
    }

    @Test
    fun textScriptLanguagesReadsNonLatinScriptsOnly() {
        assertEquals(listOf("ja"), textScriptLanguages("【京都】Walking Vlog ひとり旅"))
        assertEquals(listOf("ko"), textScriptLanguages("서울 여행 브이로그"))
        assertEquals(listOf("zh", "ja"), textScriptLanguages("北京旅行"))
        assertEquals(emptyList<String>(), textScriptLanguages("Learn Japanese in 10 minutes"))
        assertEquals(emptyList<String>(), textScriptLanguages("Tokyo 東京 and Kyoto 京都 trip", mustDominate = true))
    }

    private fun captionRenderer(vararg tracks: JSONObject): JSONObject {
        val list = JSONArray()
        tracks.forEach { list.put(it) }
        return JSONObject().put("captionTracks", list)
    }

    private fun manual(language: String): JSONObject = JSONObject().put("languageCode", language).put("vssId", ".$language")

    private fun generated(language: String): JSONObject =
        JSONObject().put("languageCode", language).put("vssId", "a.$language").put("kind", "asr")
}
