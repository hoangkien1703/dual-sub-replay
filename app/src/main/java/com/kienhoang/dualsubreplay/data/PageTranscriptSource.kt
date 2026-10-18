package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

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
    /** Each request is its own attempt on the page, so a retry starts again instead of reusing a result. */
    val attempt: Long = 0,
)

/** What the page's transcript script reported on one poll. */
internal sealed interface PageTranscriptReply {
    /** Still working; [step] is `page-address`, `no-player` or `waiting-for-captions`. */
    data class Working(
        val step: String? = null,
    ) : PageTranscriptReply

    /** The page has the document; [languageCode] and [generated] name the track it came from. */
    data class Done(
        val text: String,
        val languageCode: String? = null,
        val generated: Boolean? = null,
    ) : PageTranscriptReply
}

/** Why the page gave no full transcript, shown beside live subtitles. */
enum class PageTranscriptProblem {
    /** The page never answered for this video (not on screen, or the page player had no details yet). */
    NO_ANSWER,

    /** The page player lists no caption track for this video. */
    NO_TRACKS,

    /** The page has no player to switch captions on with. */
    NO_PLAYER,

    /** The page player has not downloaded the captions yet; the page keeps watching for them. */
    WAITING_FOR_CAPTIONS,

    /** The page got a document, but it held no readable captions. */
    UNREADABLE,
}

/**
 * Hands the full transcript over from the YouTube page in the app's WebView. YouTube often refuses
 * the app's own caption requests with its bot check, while the page, with its cookies and its
 * proof-of-origin token, plays the same video. The provider asks for a video; the page side reads
 * the page player's track list, picks the track the provider's own rules would pick, and takes the
 * document the page player downloads (see `webPageTranscriptScript`). Only one request is open at a
 * time. When the provider stops waiting, the page keeps watching the same video, and a document
 * that arrives later is kept and announced on [lateTranscripts] so the app can load it.
 */
class PageTranscriptSource {
    internal class Request(
        val videoId: String,
        val choice: CaptionLanguageChoice,
        val attempt: Long,
    ) {
        internal val document = CompletableDeferred<PageDocument?>()

        @Volatile var target: PageCaptionTarget? = null
            internal set

        @Volatile internal var languages: List<CaptionLanguage> = emptyList()

        @Volatile internal var step: String? = null

        /** Set once the provider stopped waiting and the page only watches for captions. */
        @Volatile internal var late = false
    }

    internal class PageDocument(
        val target: PageCaptionTarget,
        val languages: List<CaptionLanguage>,
        val text: String,
    )

    private val attempts = AtomicLong()

    @Volatile private var current: Request? = null

    /** A document that arrived after the provider stopped waiting, kept for the next request. */
    @Volatile private var ready: Pair<Request, PageDocument>? = null

    @Volatile private var problem: Pair<String, PageTranscriptProblem>? = null

    private val late = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Video ids whose full transcript the page got after the provider stopped waiting. */
    val lateTranscripts: SharedFlow<String> = late.asSharedFlow()

    /** The page's transcript of [videoId], or null when the page cannot get it within [timeoutMs]. */
    internal suspend fun transcript(
        videoId: String,
        choice: CaptionLanguageChoice,
        timeoutMs: Long = PAGE_TRANSCRIPT_TIMEOUT_MS,
    ): CaptionTrackResult? {
        val request = Request(videoId, choice, attempts.incrementAndGet())
        val kept =
            synchronized(this) {
                current?.document?.complete(null)
                current = request
                problem = null
                ready?.takeIf { (from, _) -> from.videoId == videoId && from.choice == choice }?.second.also { ready = null }
            }
        if (kept != null) request.document.complete(null)
        var waiting = true
        try {
            var document = kept ?: withTimeoutOrNull(timeoutMs) { request.document.await() }
            // An answer that came in just as the wait ended is still taken.
            if (document == null && !watchLater(request)) document = request.document.await()
            waiting = false
            if (document == null) return null
            val cues = runCatching { CaptionDocumentParser.parse(document.text) }.getOrDefault(emptyList())
            if (cues.isEmpty()) {
                fail(request, PageTranscriptProblem.UNREADABLE)
                return null
            }
            return CaptionTrackResult(document.target.languageCode, document.target.generated, cues, document.languages)
        } finally {
            // Cancelled (another source won, or the video changed): the page stops for this request.
            synchronized(this) { if (waiting && current === request) current = null }
        }
    }

    /** Why the page gave no transcript for [videoId] the last time it was asked, if it gave none. */
    fun problem(videoId: String): PageTranscriptProblem? = problem?.takeIf { it.first == videoId }?.second

    /** The open request for the video now on the page, if any; a late one only watches. */
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
            fail(request, PageTranscriptProblem.NO_TRACKS)
            request.document.complete(null)
            return
        }
        request.languages = availableLanguages(tracks)
        request.target =
            PageCaptionTarget(request.videoId, track.optString("languageCode", "en"), isGeneratedTrack(track), baseUrl, request.attempt)
    }

    /** Takes the page's answer for the request's track; parsing happens on the provider's side. */
    internal fun offerReply(
        request: Request,
        reply: PageTranscriptReply,
    ) {
        if (request !== current) return
        val target = request.target ?: return
        when (reply) {
            is PageTranscriptReply.Working -> reply.step?.let { request.step = it }
            is PageTranscriptReply.Done -> {
                if (reply.text.length > MAX_PAGE_TRANSCRIPT_LENGTH) return
                // The page may have found the same language as another kind (auto-generated or not).
                val track =
                    target.copy(
                        languageCode = reply.languageCode ?: target.languageCode,
                        generated = reply.generated ?: target.generated,
                    )
                val document = PageDocument(track, request.languages, reply.text)
                val announce =
                    synchronized(this) {
                        if (current !== request) return
                        if (request.late) {
                            current = null
                            ready = request to document
                        }
                        request.late
                    }
                request.document.complete(if (announce) null else document)
                if (announce) late.tryEmit(request.videoId)
            }
        }
    }

    /** Leaves [request] open so the page keeps watching; false when the page answered meanwhile. */
    private fun watchLater(request: Request): Boolean {
        val reason =
            when {
                request.target == null -> PageTranscriptProblem.NO_ANSWER
                request.step == "no-player" -> PageTranscriptProblem.NO_PLAYER
                else -> PageTranscriptProblem.WAITING_FOR_CAPTIONS
            }
        synchronized(this) {
            if (current !== request || request.document.isCompleted) return false
            request.late = true
        }
        fail(request, reason)
        return true
    }

    private fun fail(
        request: Request,
        reason: PageTranscriptProblem,
    ) {
        problem = request.videoId to reason
        // Fixed categories only, like the provider's: never page text or addresses in logcat.
        android.util.Log.w("CaptionRecovery", "page: ${reason.name.lowercase()}")
    }

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageTranscriptSource()
    }
}
