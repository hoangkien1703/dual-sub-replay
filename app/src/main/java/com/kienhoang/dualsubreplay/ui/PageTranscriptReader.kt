package com.kienhoang.dualsubreplay.ui

import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kienhoang.dualsubreplay.data.PageTranscriptSource
import com.kienhoang.dualsubreplay.data.PageTranslationSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PAGE_TRANSCRIPT_POLL_MS = 600L

/** Once the app stopped waiting, the page only watches for captions the player loads later. */
private const val PAGE_TRANSCRIPT_LATE_POLL_MS = 1_500L

/** A script whose answer has not come back after this long no longer blocks the next one. */
private const val PAGE_TRANSCRIPT_SCRIPT_TIMEOUT_MS = 5_000L

/**
 * While the app waits for the full transcript of the video on the page, runs the page transcript
 * scripts on [webView] and hands their answers to [source]: first the page player's track list,
 * then the chosen track. Keeps watching, more slowly, after the app stopped waiting, so captions the
 * page player loads later still give the full transcript. Does nothing while no request is open.
 */
@Composable
internal fun PageTranscriptReader(
    webView: WebView,
    active: Boolean,
    source: PageTranscriptSource = PageTranscriptSource.Shared,
) {
    LaunchedEffect(webView, active) {
        if (!active) return@LaunchedEffect
        var runningSince = 0L
        var lastRun = 0L
        while (isActive) {
            delay(PAGE_TRANSCRIPT_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            if (runningSince != 0L && now - runningSince < PAGE_TRANSCRIPT_SCRIPT_TIMEOUT_MS) continue
            val url = webView.url.orEmpty()
            if (!isYouTubeWebUrl(url)) continue
            val request = source.pending(browseVideoSelection(url)?.videoId) ?: continue
            if (request.late && now - lastRun < PAGE_TRANSCRIPT_LATE_POLL_MS) continue
            lastRun = now
            val target = request.target
            val started = now
            runningSince = started
            val script = target?.let(::webPageTranscriptScript) ?: WEB_PAGE_CAPTION_TRACKS_SCRIPT
            // Installs the page's token maker first (once per page; later runs return at once).
            if (target?.pageToken == true) webView.evaluateJavascript(WEB_PAGE_TOKEN_SCRIPT, null)
            webView.evaluateJavascript(script) { rawValue ->
                if (runningSince == started) runningSince = 0L
                if (target == null) {
                    decodeScriptString(rawValue)?.let { source.offerTracks(request, it) }
                } else {
                    source.offerReply(request, parsePageTranscriptReply(rawValue))
                }
            }
        }
    }
}

/**
 * While the app waits for YouTube's own translation of the video on the page, runs
 * [webPageTranslationScript] on [webView] and hands its answers to [source]. Does nothing while no
 * request is open.
 */
@Composable
internal fun PageTranslationReader(
    webView: WebView,
    active: Boolean,
    source: PageTranslationSource = PageTranslationSource.Shared,
) {
    LaunchedEffect(webView, active) {
        if (!active) return@LaunchedEffect
        var runningSince = 0L
        while (isActive) {
            delay(PAGE_TRANSCRIPT_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            if (runningSince != 0L && now - runningSince < PAGE_TRANSCRIPT_SCRIPT_TIMEOUT_MS) continue
            val url = webView.url.orEmpty()
            if (!isYouTubeWebUrl(url)) continue
            val request = source.pending(browseVideoSelection(url)?.videoId) ?: continue
            runningSince = now
            webView.evaluateJavascript(webPageTranslationScript(request.target)) { rawValue ->
                if (runningSince == now) runningSince = 0L
                source.offer(request, parsePageTranslationReply(rawValue))
            }
        }
    }
}
