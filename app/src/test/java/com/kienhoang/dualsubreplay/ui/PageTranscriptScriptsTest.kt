package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.MAX_PAGE_TRANSCRIPT_LENGTH
import com.kienhoang.dualsubreplay.data.PageCaptionTarget
import com.kienhoang.dualsubreplay.data.PageTranscriptReply
import com.kienhoang.dualsubreplay.data.PageTranslationReply
import com.kienhoang.dualsubreplay.data.PageTranslationTarget
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
                    """"baseUrl":${JSONObject.quote(
                        target.baseUrl,
                    )},"attempt":"7","panelOnly":false,"pageToken":false,"tokenFirst":false};""",
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
    fun theTrackListSaysWhetherThePlayerHasLoadedLikeLingoTubeChecks() {
        val tracks = WEB_PAGE_CAPTION_TRACKS_SCRIPT
        assertTrue(tracks.contains("holder.getAttribute('loading')"))
        assertTrue(tracks.contains("holder.getAttribute('playable')"))
        assertTrue(tracks.contains("ready: (!loading || loading === 'false') && (!playable || playable === 'true')"))
    }

    @Test
    fun aPageTokenIsTriedOnlyWhenAllowedAndStaysInThePage() {
        val script = webPageTranscriptScript(target.copy(pageToken = true))
        assertTrue(script.contains(""""panelOnly":false,"pageToken":true,"tokenFirst":false};"""))
        // Still one fetch: the token only joins the same /api/timedtext address, as the web player sends it.
        assertEquals(1, Regex("fetch\\(").findAll(script).count())
        assertTrue(script.contains("url.searchParams.set('pot', token);"))
        assertTrue(script.contains("url.searchParams.set('c', 'WEB');"))
        assertTrue(script.contains("if (!target.pageToken || job.status !== 'working') return giveUp();"))
        assertTrue(script.contains("tries >= $PAGE_TOKEN_TRIES"))
        // The reply names how far the token got and how the page got the lines, never the token.
        assertTrue(script.contains("token: job.token || null,"))
        assertTrue(script.contains("via: job.via || null"))
        assertFalse(script.contains("token: token"))
    }

    @Test
    fun automaticAsksPlainlyFirstAndOnAsksWithTheTokenFirst() {
        val script = webPageTranscriptScript(target.copy(pageToken = true, tokenFirst = true))
        assertTrue(script.contains(""""pageToken":true,"tokenFirst":true};"""))
        assertTrue(script.contains("state.withToken(job, target, function() { state.plain(job, target, null); });"))
        assertTrue(script.contains("state.plain(job, target, function() { state.withToken(job, target, null); });"))
        // Each way says it got the lines, so Automatic can remember which one YouTube wants.
        assertTrue(script.contains("job.via = 'address';"))
        assertTrue(script.contains("job.via = 'token';"))
        assertTrue(script.contains("if (!job.via) job.via = track.panel ? 'panel' : 'player';"))
    }

    @Test
    fun theReplySaysHowThePageGotTheLines() {
        fun reply(via: String) = JSONObject.quote("""{"status":"done","text":"x","via":"$via"}""")
        assertEquals("token", (parsePageTranscriptReply(reply("token")) as PageTranscriptReply.Done).via)
        assertNull((parsePageTranscriptReply(reply("evil")) as PageTranscriptReply.Done).via)
    }

    @Test
    fun theTokenScriptStaysOnYouTubeAndLoadsBotGuardOnlyFromGoogle() {
        val script = WEB_PAGE_TOKEN_SCRIPT
        assertTrue(script.contains("window.location.protocol !== 'https:'"))
        assertTrue(script.contains("host.endsWith('.youtube.com')"))
        val fetched = Regex("fetch\\('([^']+)'").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(listOf("/youtubei/v1/att/get?prettyPrint=false", "/api/jnn/v1/GenerateIT"), fetched)
        assertTrue(script.contains("url.hostname === 'www.google.com' && url.pathname.indexOf('/js/th/') === 0"))
        assertTrue(script.contains("url.protocol === 'https:'"))
        // It never sends the token anywhere itself.
        assertFalse(script.contains("XMLHttpRequest"))
        assertFalse(script.contains("sendBeacon"))
    }

    @Test
    fun theJobAlsoTriesTheTrackAddressAsYouTubeGaveIt() {
        val script = webPageTranscriptScript(target)
        assertTrue(script.contains("state.download(target.baseUrl, target, false)"))
        assertTrue(script.contains("state.download(target.baseUrl, target, true)"))
        // Only the json3 variant is rewritten; both pass the same /api/timedtext check first.
        assertTrue(script.contains("if (!asGiven) {"))
        // YouTube's empty answer without a token does not finish the job.
        assertTrue(script.contains("job.status === 'working' && state.cues(text)"))
    }

    @Test
    fun youTubesTranslationIsAskedForLikeLingoTubeAndStaysOnYouTube() {
        val script = webPageTranslationScript(PageTranslationTarget("abcdefghijk", "ja", false, "vi", attempt = 3))
        assertTrue(script.contains("window.location.protocol !== 'https:'"))
        assertTrue(script.contains("host.endsWith('.youtube.com')"))
        assertTrue(
            script.contains(
                """const target = {"videoId":"abcdefghijk","languageCode":"ja","generated":false,"translateTo":"vi","attempt":"3"};""",
            ),
        )
        assertTrue(script.contains("const key = '$PAGE_TRANSLATION_STATE_KEY';"))
        // The source address passes the same /api/timedtext check before tlang is added.
        assertEquals(1, Regex("fetch\\(").findAll(script).count())
        assertTrue(script.contains("if (target.translateTo) url.searchParams.set('tlang', target.translateTo);"))
        assertTrue(script.contains("state.download(found.address, found.request, true)"))
        // Otherwise the player itself downloads the translated track, and the captions are switched back.
        assertTrue(script.contains("translationLanguage: { languageCode: target.translateTo"))
        assertTrue(script.contains("state.restoreTrack(job);"))
        assertTrue(script.contains("job.status = 'unavailable';"))
    }

    @Test
    fun theCaptureKeepsYouTubesTranslationsApartFromTheOriginals() {
        val capture = WEB_CAPTION_CAPTURE_SCRIPT
        assertTrue(capture.contains("capture.timedText(value) || capture.timedText(value, true)"))
        assertTrue(capture.contains("!url.searchParams.get('tlang') !== !translated) return null;"))
        assertTrue(capture.contains("capture.translations[translation] = text;"))
        assertTrue(capture.contains("capture.translationOrder.length > 4"))
    }

    @Test
    fun translationRepliesAreReadSafely() {
        fun raw(json: String) = JSONObject.quote(json)

        assertEquals(
            PageTranslationReply.Done("<transcript/>"),
            parsePageTranslationReply(raw("""{"status":"done","text":"<transcript/>"}""")),
        )
        assertEquals(
            PageTranslationReply.Unavailable,
            parsePageTranslationReply(raw("""{"status":"unavailable","step":"not-translatable"}""")),
        )
        assertEquals(PageTranslationReply.Working("no-track"), parsePageTranslationReply(raw("""{"status":"working","step":"no-track"}""")))
        assertEquals(PageTranslationReply.Working(null), parsePageTranslationReply(raw("""{"status":"done","text":""}""")))
        assertEquals(PageTranslationReply.Working(), parsePageTranslationReply(null))
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
        assertEquals(
            PageTranscriptReply.Working("waiting-for-captions", "refused-403"),
            parsePageTranscriptReply(raw("""{"status":"working","step":"waiting-for-captions","token":"refused-403"}""")),
        )
        assertEquals(PageTranscriptReply.Working(null, null), parsePageTranscriptReply(raw("""{"status":"working","token":null}""")))
        assertEquals(PageTranscriptReply.Working(null, null), parsePageTranscriptReply(raw("""{"status":"working","token":"AbC+/="}""")))
        assertEquals(PageTranscriptReply.Working(null), parsePageTranscriptReply(raw("""{"status":"done","text":""}""")))
        assertEquals(PageTranscriptReply.Working(), parsePageTranscriptReply("null"))
        assertEquals(PageTranscriptReply.Working(), parsePageTranscriptReply(null))
        assertNull(decodeScriptString("null"))
        assertEquals("{}", decodeScriptString(raw("{}")))
    }
}
