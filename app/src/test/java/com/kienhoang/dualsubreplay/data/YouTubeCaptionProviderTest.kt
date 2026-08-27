package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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
    fun triesUpdatedAndroidThenIosThenTvThenWebClients() {
        val clients = youtubePlayerClients("2.20260826.01.00")

        assertEquals(listOf("ANDROID", "IOS", "TVHTML5", "WEB"), clients.map { it.clientName })
        assertEquals(listOf("3", "5", "7", "1"), clients.map { it.clientNumber })
        assertEquals("21.26.364", clients[0].clientVersion)
        assertEquals("21.26.4", clients[1].clientVersion)
        assertEquals("7.20260707.07.00", clients[2].clientVersion)
        assertEquals("2.20260826.01.00", clients[3].clientVersion)
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
    fun prefersTheOriginalDirectAdaptiveAudioTrack() {
        val dubbed = audioFormat(
            url = "https://dubbed.googlevideo.com/videoplayback?sig=dubbed",
            bitrate = 32_000L,
            isDefault = false,
        )
        val original = audioFormat(
            url = "https://original.googlevideo.com/videoplayback?sig=original",
            bitrate = 48_000L,
            isDefault = true,
        )
        val root = playerResponse(adaptiveFormats = JSONArray().put(dubbed).put(original))

        val stream = audioStreamFromPlayerResponse(root, "abcdefghijk", "test-agent")

        assertEquals("https://original.googlevideo.com/videoplayback?sig=original", stream?.url)
        assertEquals("test-agent", stream?.userAgent)
    }

    @Test
    fun fallsBackToProgressiveMp4WhenSabrOmitsAdaptiveUrls() {
        val progressive = JSONObject()
            .put("url", "https://progressive.googlevideo.com/videoplayback?sig=progressive")
            .put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
            .put("bitrate", 600_000L)
            .put("audioQuality", "AUDIO_QUALITY_LOW")
        val root = playerResponse(
            adaptiveFormats = JSONArray().put(
                JSONObject()
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 48_000L),
            ),
            formats = JSONArray().put(progressive),
        )

        val stream = audioStreamFromPlayerResponse(root, "abcdefghijk", "test-agent")

        assertEquals("https://progressive.googlevideo.com/videoplayback?sig=progressive", stream?.url)
        assertEquals("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", stream?.mimeType)
    }

    private fun audioFormat(url: String, bitrate: Long, isDefault: Boolean): JSONObject =
        JSONObject()
            .put("url", url)
            .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
            .put("bitrate", bitrate)
            .put("audioTrack", JSONObject().put("audioIsDefault", isDefault))

    private fun playerResponse(
        adaptiveFormats: JSONArray,
        formats: JSONArray = JSONArray(),
    ): JSONObject = JSONObject()
        .put("videoDetails", JSONObject().put("videoId", "abcdefghijk"))
        .put(
            "streamingData",
            JSONObject()
                .put("adaptiveFormats", adaptiveFormats)
                .put("formats", formats),
        )
}
