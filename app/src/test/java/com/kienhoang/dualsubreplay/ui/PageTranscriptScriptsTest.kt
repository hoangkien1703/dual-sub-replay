package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.MAX_PAGE_TRANSCRIPT_LENGTH
import com.kienhoang.dualsubreplay.data.PageCaptionTarget
import com.kienhoang.dualsubreplay.data.PageTranscriptReply
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranscriptScriptsTest {
    @Test
    fun pageTranscriptScriptsStayOnYouTubeAndFetchOnlyTimedText() {
        val target =
            PageCaptionTarget("abcdefghijk", "zh-Hans", generated = true, baseUrl = "https://www.youtube.com/api/timedtext?v=abcdefghijk")
        val script = webPageTranscriptScript(target)

        listOf(WEB_PAGE_CAPTION_TRACKS_SCRIPT, script).forEach {
            assertTrue(it.contains("window.location.protocol !== 'https:'"))
            assertTrue(it.contains("host.endsWith('.youtube.com')"))
        }
        assertTrue(
            script.contains(
                """const target = {"videoId":"abcdefghijk","languageCode":"zh-Hans","generated":true,""" +
                    """"baseUrl":${JSONObject.quote(target.baseUrl)}};""",
            ),
        )
        // Every address the page fetches passes the same YouTube-host, /api/timedtext check.
        assertTrue(script.contains("url.pathname !== '/api/timedtext'"))
        assertTrue(script.contains("urlHost.endsWith('.youtube.com')"))
        assertEquals(1, Regex("fetch\\(").findAll(script).count())
        assertTrue(script.contains("const url = state.timedText(address);"))
        assertTrue(script.contains("credentials: 'include'"))
        assertTrue(script.contains("text.length <= ${MAX_PAGE_TRANSCRIPT_LENGTH}"))
        // Captions the app switched on are switched back.
        assertTrue(script.contains("player.setOption('captions', 'track', job.previous || {})"))
        assertTrue(WEB_PAGE_CAPTION_TRACKS_SCRIPT.contains("getPlayerResponse()"))
        assertFalse(WEB_PAGE_CAPTION_TRACKS_SCRIPT.contains("setOption"))
    }

    @Test
    fun pageTranscriptRepliesAreReadSafely() {
        fun raw(json: String) = JSONObject.quote(json)

        assertEquals(PageTranscriptReply.Done("{}"), parsePageTranscriptReply(raw("""{"status":"done","text":"{}"}""")))
        assertEquals(
            PageTranscriptReply.Failed("no-page-request"),
            parsePageTranscriptReply(raw("""{"status":"failed","reason":"no-page-request"}""")),
        )
        assertEquals(PageTranscriptReply.Failed("empty"), parsePageTranscriptReply(raw("""{"status":"done","text":""}""")))
        assertEquals(PageTranscriptReply.Working, parsePageTranscriptReply(raw("""{"status":"working"}""")))
        assertEquals(PageTranscriptReply.Working, parsePageTranscriptReply("null"))
        assertEquals(PageTranscriptReply.Working, parsePageTranscriptReply(null))
        assertNull(decodeScriptString("null"))
        assertEquals("{}", decodeScriptString(raw("{}")))
    }
}
