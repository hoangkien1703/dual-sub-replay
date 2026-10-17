package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.ui.parsePageCaptionTracks
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCaptionSourceTest {
    private val pageResponse =
        """
        {"videoDetails":{"videoId":"abcdefghijk","title":"Hello","shortDescription":""},
         "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
           {"baseUrl":"https://m.youtube.com/api/timedtext?v=abcdefghijk&lang=en&kind=asr","languageCode":"en","kind":"asr",
            "name":{"simpleText":"English"}}],
           "audioTracks":[],"defaultAudioTrackIndex":0}}}
        """.trimIndent()

    @Test
    fun thePageCaptionRequestGivesItsTokenAndClientFields() {
        val parameters =
            pageTimedTextParameters(
                "https://m.youtube.com/api/timedtext?v=abcdefghijk&lang=en&pot=TOKEN&c=MWEB&cver=2.2026&fmt=json3&tlang=fr",
            )
        assertEquals("abcdefghijk" to mapOf("pot" to "TOKEN", "c" to "MWEB", "cver" to "2.2026"), parameters)
    }

    @Test
    fun onlyTrustedCaptionRequestsWithATokenCount() {
        assertNull(pageTimedTextParameters("https://evil.example/api/timedtext?v=abcdefghijk&pot=TOKEN"))
        assertNull(pageTimedTextParameters("http://m.youtube.com/api/timedtext?v=abcdefghijk&pot=TOKEN"))
        assertNull(pageTimedTextParameters("https://m.youtube.com/api/timedtext?v=abcdefghijk&lang=en"))
        assertNull(pageTimedTextParameters("https://m.youtube.com/watch?v=abcdefghijk&pot=TOKEN"))
    }

    @Test
    fun pageFieldsAreAddedWithoutReplacingTheTrackAddress() {
        val url =
            withPageTimedTextParameters(
                "https://m.youtube.com/api/timedtext?v=abcdefghijk&lang=en&c=WEB",
                mapOf("pot" to "TOKEN", "c" to "MWEB"),
            )
        assertEquals("https://m.youtube.com/api/timedtext?v=abcdefghijk&lang=en&c=WEB&pot=TOKEN", url)
    }

    @Test
    fun theTrackListBelongsToTheVideoThePagePlays() {
        val source = PageCaptionSource()
        source.recordPlayerResponse(pageResponse)
        source.recordTimedTextRequest("https://m.youtube.com/api/timedtext?v=abcdefghijk&pot=TOKEN")
        assertTrue(source.hasTracks("abcdefghijk"))
        assertFalse(source.hasTracks("zyxwvutsrqp"))
        assertNull(source.playerResponse("zyxwvutsrqp"))
        assertEquals(mapOf("pot" to "TOKEN"), source.timedTextParameters("abcdefghijk"))
        assertEquals(emptyMap<String, String>(), source.timedTextParameters("zyxwvutsrqp"))
        source.recordPlayerResponse("not json")
        assertTrue(source.hasTracks("abcdefghijk"))
    }

    @Test
    fun thePageScriptResultIsDecodedFromItsJavascriptString() {
        assertEquals("{\"a\":1}", parsePageCaptionTracks("\"{\\\"a\\\":1}\""))
        assertNull(parsePageCaptionTracks("null"))
        assertNull(parsePageCaptionTracks(null))
    }

    @Test
    fun aVideoThePageAlreadyPlaysLoadsWithoutAskingYouTubesPlayer() {
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body =
                        if (request.url.encodedPath == "/api/timedtext") {
                            """{"events":[{"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"hello there"}]}]}"""
                        } else {
                            """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val source = PageCaptionSource()
        source.recordPlayerResponse(pageResponse)
        source.recordTimedTextRequest("https://m.youtube.com/api/timedtext?v=abcdefghijk&pot=TOKEN&c=MWEB")
        source.userAgent = "PageAgent"
        source.cookies = { "VISITOR_INFO1_LIVE=x" }

        val result = runBlocking { YouTubeCaptionProvider(client, source).fetch("abcdefghijk", emptyList()) }

        assertEquals("en", result.languageCode)
        assertEquals("hello there", result.cues.single().text)
        val download = requests.single()
        assertEquals("/api/timedtext", download.url.encodedPath)
        assertEquals("TOKEN", download.url.queryParameter("pot"))
        assertEquals("MWEB", download.url.queryParameter("c"))
        assertEquals("PageAgent", download.header("User-Agent"))
        assertEquals("VISITOR_INFO1_LIVE=x", download.header("Cookie"))
    }
}
