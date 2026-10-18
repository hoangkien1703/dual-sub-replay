package com.kienhoang.dualsubreplay.ui

import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kienhoang.dualsubreplay.data.PageTranscriptSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PAGE_TRANSCRIPT_POLL_MS = 600L

/** A script whose answer has not come back after this long no longer blocks the next one. */
private const val PAGE_TRANSCRIPT_SCRIPT_TIMEOUT_MS = 5_000L

/**
 * While the app waits for the full transcript of the video on the page, runs the page transcript
 * scripts on [webView] and hands their answers to [source]: first the page player's track list,
 * then the download of the chosen track. Does nothing while no request is open.
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
        while (isActive) {
            delay(PAGE_TRANSCRIPT_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            if (runningSince != 0L && now - runningSince < PAGE_TRANSCRIPT_SCRIPT_TIMEOUT_MS) continue
            val url = webView.url.orEmpty()
            if (!isYouTubeWebUrl(url)) continue
            val request = source.pending(browseVideoSelection(url)?.videoId) ?: continue
            val target = request.target
            val started = now
            runningSince = started
            val script = target?.let(::webPageTranscriptScript) ?: WEB_PAGE_CAPTION_TRACKS_SCRIPT
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
