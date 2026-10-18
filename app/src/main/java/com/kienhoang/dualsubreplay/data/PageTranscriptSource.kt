package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** How long the provider waits for the page before relying on its own download alone. */
internal const val PAGE_TRANSCRIPT_TIMEOUT_MS = 25_000L

/** The same 8 MiB limit as the provider's own downloads, counted in characters. */
internal const val MAX_PAGE_TRANSCRIPT_LENGTH = 8 * 1024 * 1024

/** The caption track the page is asked to fetch, picked from the page player's own track list. */
internal data class PageCaptionTarget(
    val videoId: String,
    val languageCode: String,
    val generated: Boolean,
    val baseUrl: String,
)

/** What the page's transcript script reported on one poll. */
internal sealed interface PageTranscriptReply {
    data object Working : PageTranscriptReply

    data class Done(
        val text: String,
    ) : PageTranscriptReply

    data class Failed(
        val reason: String,
    ) : PageTranscriptReply
}

/**
 * Hands the full transcript over from the YouTube page in the app's WebView. YouTube often refuses
 * the app's own caption requests with its bot check, while the page, with its cookies and its
 * proof-of-origin token, plays the same video. The provider asks for a video; the page side reads
 * the page player's track list, picks the track the provider's own rules would pick, and has the
 * page download it (see `webPageTranscriptScript`). Only one request is open at a time; one for a
 * video that is not on the page simply waits until it times out.
 */
class PageTranscriptSource {
    internal class Request(
        val videoId: String,
        val choice: CaptionLanguageChoice,
    ) {
        internal val document = CompletableDeferred<PageDocument?>()

        @Volatile var target: PageCaptionTarget? = null
            internal set

        @Volatile internal var languages: List<CaptionLanguage> = emptyList()
    }

    internal class PageDocument(
        val target: PageCaptionTarget,
        val languages: List<CaptionLanguage>,
        val text: String,
    )

    @Volatile private var current: Request? = null

    /** The page's transcript of [videoId], or null when the page cannot get it within [timeoutMs]. */
    internal suspend fun transcript(
        videoId: String,
        choice: CaptionLanguageChoice,
        timeoutMs: Long = PAGE_TRANSCRIPT_TIMEOUT_MS,
    ): CaptionTrackResult? {
        val request = Request(videoId, choice)
        synchronized(this) {
            current?.document?.complete(null)
            current = request
        }
        try {
            val document = withTimeoutOrNull(timeoutMs) { request.document.await() } ?: return null
            val cues = runCatching { CaptionDocumentParser.parse(document.text) }.getOrDefault(emptyList())
            if (cues.isEmpty()) {
                reportPageFailure("empty-document")
                return null
            }
            return CaptionTrackResult(document.target.languageCode, document.target.generated, cues, document.languages)
        } finally {
            synchronized(this) { if (current === request) current = null }
        }
    }

    /** The open request for the video now on the page, if any. */
    internal fun pending(pageVideoId: String?): Request? = current?.takeIf { it.videoId == pageVideoId && !it.document.isCompleted }

    /**
     * Picks the track to fetch from the page player's caption details (see
     * `WEB_PAGE_CAPTION_TRACKS_SCRIPT`). A page that has the video but no track ends the request.
     */
    internal fun offerTracks(
        request: Request,
        json: String,
    ) {
        if (request !== current || request.target != null) return
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val details = root.optJSONObject("videoDetails")
        if (details?.optString("videoId") != request.videoId) return
        val renderer = root.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
        val tracks = renderer?.optJSONArray("captionTracks")
        val track = renderer?.let { selectCaptionTrack(it, request.choice.preferred, details, request.choice.learning) }
        val baseUrl = track?.optString("baseUrl").orEmpty()
        if (tracks == null || track == null || trustedYouTubeCaptionUrl(baseUrl) == null) {
            reportPageFailure("no-tracks")
            request.document.complete(null)
            return
        }
        request.languages = availableLanguages(tracks)
        request.target = PageCaptionTarget(request.videoId, track.optString("languageCode", "en"), isGeneratedTrack(track), baseUrl)
    }

    /** Takes the page's answer for the request's track; parsing happens on the provider's side. */
    internal fun offerReply(
        request: Request,
        reply: PageTranscriptReply,
    ) {
        if (request !== current) return
        val target = request.target ?: return
        when (reply) {
            PageTranscriptReply.Working -> Unit
            is PageTranscriptReply.Failed -> {
                reportPageFailure(reply.reason)
                request.document.complete(null)
            }
            is PageTranscriptReply.Done ->
                request.document.complete(
                    PageDocument(target, request.languages, reply.text).takeIf { reply.text.length <= MAX_PAGE_TRANSCRIPT_LENGTH },
                )
        }
    }

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageTranscriptSource()
    }
}

/** Fixed categories only, like the provider's: never page text or addresses in logcat. */
private fun reportPageFailure(reason: String) {
    val category = reason.takeIf { it.matches(Regex("[a-z0-9-]{1,40}")) } ?: "other"
    android.util.Log.w("CaptionRecovery", "page: $category")
}
