package com.kienhoang.dualsubreplay.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the August/September 2026 device-only caption fallback failures. */
class YouTubeCaptionFallbackTest {
    @Test
    fun extractsEmbeddedPlayerResponseWithoutBeingConfusedByBracesInsideStrings() {
        val html = """
            <html><script>
            var ytInitialPlayerResponse = {
              "videoDetails":{"videoId":"abc123"},
              "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{
                "languageCode":"en",
                "baseUrl":"https://www.youtube.com/api/timedtext?v=abc123&note={still-a-string}",
                "name":{"simpleText":"English"}
              }]}}
            };
            </script></html>
        """.trimIndent()

        val player = extractInitialPlayerResponse(html)
        val tracks = player
            ?.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")

        assertEquals("abc123", player?.optJSONObject("videoDetails")?.optString("videoId"))
        assertEquals(1, tracks?.length())
        assertEquals("en", tracks?.optJSONObject(0)?.optString("languageCode"))
    }

    @Test
    fun embeddedPlayerResponseReturnsNullWhenMissingOrTruncated() {
        assertNull(extractInitialPlayerResponse("<html>no player response</html>"))
        assertNull(extractInitialPlayerResponse("var ytInitialPlayerResponse = {\"videoDetails\":{"))
    }

    @Test
    fun ipv4IsPreferredButIpv6IsKeptAsFallback() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.0.2.1")

        val ordered = preferIpv4Addresses(listOf(ipv6, ipv4))

        assertTrue(ordered[0] is Inet4Address)
        assertTrue(ordered[1] is Inet6Address)
        assertEquals(setOf(ipv4, ipv6), ordered.toSet())
    }

    @Test
    fun playerApiUsesGoogleapisBeforeYoutubeHostFallback() {
        assertEquals(
            listOf(
                "https://youtubei.googleapis.com/youtubei/v1/player?key=test-key",
                "https://www.youtube.com/youtubei/v1/player?key=test-key",
            ),
            playerApiUrls("test-key"),
        )
    }

    @Test
    fun currentFallbackOrderStartsWithClientsThatStillExposeLoggedOutPlayerData() {
        val clients = youtubePlayerClients("2.20260708.00.00")

        assertEquals("VISIONOS", clients.first().clientName)
        assertTrue(clients.any { it.clientName == "ANDROID_VR" && it.clientNumber == "28" })
        assertTrue(
            clients.any {
                it.clientName == "WEB_EMBEDDED_PLAYER" &&
                    it.clientNumber == "56" &&
                    it.embedUrl == "https://www.reddit.com/"
            },
        )
    }

    @Test
    fun embeddedClientAddsThirdPartyEmbedContext() {
        val embedded = youtubePlayerClients("2.20260708.00.00")
            .first { it.clientName == "WEB_EMBEDDED_PLAYER" }

        val body = playerRequestBody("abc123", embedded)

        assertEquals("abc123", body.getString("videoId"))
        assertEquals(
            "https://www.reddit.com/",
            body.getJSONObject("context").getJSONObject("thirdParty").getString("embedUrl"),
        )
    }
}
