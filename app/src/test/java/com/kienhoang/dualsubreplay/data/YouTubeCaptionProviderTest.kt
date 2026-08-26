package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
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
}
