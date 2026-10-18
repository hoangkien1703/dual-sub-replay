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
    private val target =
        PageCaptionTarget(
            "abcdefghijk",
            "zh-Hans",
            generated = true,
            baseUrl = "https://www.youtube.com/api/timedtext?v=abcdefghijk",
            attempt = 7,
        )

    @Test
    fun pageTranscriptScriptsStayOnYouTubeAndFetchOnlyTimedText() {
        val script = webPageTranscriptScript(target)

        listOf(WEB_PAGE_CAPTION_TRACKS_SCRIPT, WEB_CAPTION_CAPTURE_SCRIPT, script).forEach {
            assertTrue(it.contains("window.location.protocol !== 'https:'"))
            assertTrue(it.contains("host.endsWith('.youtube.com')"))
        }
        assertTrue(
            script.contains(
                """const target = {"videoId":"abcdefghijk","languageCode":"zh-Hans","generated":true,""" +
                    """"baseUrl":${JSONObject.quote(target.baseUrl)},"attempt":"7","panelOnly":false};""",
            ),
        )
        // Every address the page fetches or keeps passes the same YouTube-host, /api/timedtext check.
        assertTrue(WEB_CAPTION_CAPTURE_SCRIPT.contains("url.pathname !== '/api/timedtext'"))
        assertTrue(WEB_CAPTION_CAPTURE_SCRIPT.contains("urlHost.endsWith('.youtube.com')"))
        assertEquals(1, Regex("fetch\\(").findAll(script).count())
        assertTrue(script.contains("const url = capture.timedText(address);"))
        assertTrue(script.contains("credentials: 'include'"))
        assertTrue(script.contains("text.length <= $MAX_PAGE_TRANSCRIPT_LENGTH"))
        // The capture script runs inside the job too, for WebViews without document-start scripts.
        assertTrue(script.contains(WEB_CAPTION_CAPTURE_SCRIPT))
        // Captions the app switched on are switched back, unless someone changed them since.
        assertTrue(script.contains("if (!active || active.languageCode !== job.shownLanguage) return;"))
        assertTrue(script.contains("player.setOption('captions', 'track', job.previous || {})"))
        assertTrue(WEB_PAGE_CAPTION_TRACKS_SCRIPT.contains("getPlayerResponse()"))
        assertFalse(WEB_PAGE_CAPTION_TRACKS_SCRIPT.contains("setOption"))
    }

    @Test
    fun theTrackListAlsoReadsThePlayersMenuAndDownloadsWithoutFetching() {
        val tracks = WEB_PAGE_CAPTION_TRACKS_SCRIPT
        // The capture is installed here too (declared first, so it is never inlined as "null").
        assertTrue(tracks.contains(WEB_CAPTION_CAPTURE_SCRIPT))
        assertTrue(tracks.contains("player.getOption('captions', 'tracklist', { includeAsr: true })"))
        assertTrue(tracks.contains("data.video_id === details.videoId"))
        assertTrue(tracks.contains("track.videoId === details.videoId"))
        // Other sources are used only when the player's details list no track.
        assertTrue(tracks.contains("if (!tracks.length) {"))
        assertFalse(tracks.contains("fetch("))
        assertFalse(tracks.contains("loadModule"))
    }

    @Test
    fun theCaptureOnlyReadsRepliesAndNeverChangesThem() {
        val capture = WEB_CAPTION_CAPTURE_SCRIPT
        // Installed once per page.
        assertTrue(capture.contains("if (window[key]) return;"))
        // The page's request goes out unchanged; only a clone of the reply is read.
        assertTrue(capture.contains("return open.apply(this, arguments);"))
        assertTrue(capture.contains("return send.apply(this, arguments);"))
        assertTrue(capture.contains("const reply = pageFetch.apply(window, arguments);"))
        assertTrue(capture.contains("response.clone().text()"))
        assertTrue(capture.contains("return reply;"))
        // Auto-translated tracks and oversized replies are skipped, and only eight tracks are kept.
        assertTrue(capture.contains("url.searchParams.get('tlang')"))
        assertTrue(capture.contains("text.length > $MAX_PAGE_TRANSCRIPT_LENGTH"))
        assertTrue(capture.contains("capture.order.length > 8"))
        assertFalse(capture.contains("fetch("))
    }

    @Test
    fun eachAttemptIsItsOwnJobSoARetryStartsAgain() {
        val script = webPageTranscriptScript(target)
        assertTrue(script.contains("let job = state.jobs[target.attempt];"))
        assertTrue(script.contains("if (id === target.attempt) return;"))
        assertTrue(script.contains("delete state.jobs[id];"))
        // A job never gives up by itself: the app decides how long it waits.
        assertFalse(script.contains("'failed'"))
        assertTrue(script.contains("elapsed > $PAGE_CAPTION_SWITCH_MS"))
    }

    @Test
    fun theTranscriptPanelIsTheLastResortAndOnlyReadOrClicked() {
        val script = webPageTranscriptScript(target)
        // Captions first; the panel only after the caption window, or at once for a page without tracks.
        assertTrue(script.contains("if (target.panelOnly || elapsed > $PAGE_CAPTION_SWITCH_MS) {"))
        // The panel's own reply is kept like caption replies, only from a YouTube host.
        assertTrue(WEB_CAPTION_CAPTURE_SCRIPT.contains("url.pathname !== '/youtubei/v1/get_transcript'"))
        assertTrue(WEB_CAPTION_CAPTURE_SCRIPT.contains("capture.transcriptOrder.length > 4"))
        // Opening is bounded, and a pressed button gets time before it is pressed again.
        assertTrue(script.contains("(job.panelTries || 0) >= $PANEL_OPEN_TRIES"))
        assertTrue(script.contains("(job.panelOpened ? 8000 : 2500)"))
        // Only the transcript panel's own close button is pressed afterwards.
        assertTrue(script.contains("const close = panel && panel.querySelector("))
        assertEquals(1, Regex("fetch\\(").findAll(script).count())
        assertTrue(
            parsePageTranscriptReply(JSONObject.quote("""{"status":"done","text":"{}","panel":true}"""))
                .let { it is PageTranscriptReply.Done && it.panel },
        )
    }

    @Test
    fun pageTranscriptRepliesAreReadSafely() {
        fun raw(json: String) = JSONObject.quote(json)

        assertEquals(
            PageTranscriptReply.Done("{}", "ja", true),
            parsePageTranscriptReply(raw("""{"status":"done","text":"{}","languageCode":"ja","generated":true}""")),
        )
        assertEquals(
            PageTranscriptReply.Done("{}", null, false),
            parsePageTranscriptReply(raw("""{"status":"done","text":"{}","languageCode":"<b>","generated":false}""")),
        )
        assertEquals(
            PageTranscriptReply.Working("waiting-for-captions"),
            parsePageTranscriptReply(raw("""{"status":"working","step":"waiting-for-captions"}""")),
        )
        assertEquals(PageTranscriptReply.Working(null), parsePageTranscriptReply(raw("""{"status":"working","step":"Bad step!"}""")))
        assertEquals(PageTranscriptReply.Working(null), parsePageTranscriptReply(raw("""{"status":"done","text":""}""")))
        assertEquals(PageTranscriptReply.Working(), parsePageTranscriptReply("null"))
        assertEquals(PageTranscriptReply.Working(), parsePageTranscriptReply(null))
        assertNull(decodeScriptString("null"))
        assertEquals("{}", decodeScriptString(raw("{}")))
    }
}
