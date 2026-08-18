package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

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
