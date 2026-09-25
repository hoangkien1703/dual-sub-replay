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
    fun keepsFirstCreatorTrackWithoutAnyLanguageSignal() {
        val renderer = captionRenderer(manual("ar"), manual("en"))

        assertNull(spokenCaptionLanguage(renderer))
        assertEquals(".ar", selectCaptionTrack(renderer, emptyList())?.optString("vssId"))
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
