package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** How long the provider waits for the page before relying on its own download alone. */
internal const val PAGE_TRANSCRIPT_TIMEOUT_MS = 25_000L

/**
 * How long the page may list no caption track before the provider stops waiting for it. The player
 * can fill in its track list, or download captions, a few seconds after the video's details appear.
 */
internal const val PAGE_NO_TRACKS_GRACE_MS = 8_000L

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
    /** The page listed no track: [languageCode] is a guess, and the page goes to its transcript panel. */
    val panelOnly: Boolean = false,
    /** Settings → Study tools: the page may mint its own proof-of-origin token for the track's address. */
    val pageToken: Boolean = false,
    /** The page asks with its token first and without it only if that fails (On, or Automatic once a token was needed). */
    val tokenFirst: Boolean = false,
)

/**
 * Settings → Study tools → Load captions with a page token (beta). [AUTO] asks with a token only
 * when the plain request comes back empty and, once a token worked, asks with it first on later
 * videos until a plain request works again; [ON] always asks with it first, as Migaku does.
 */
enum class PageTokenMode(
    val storageValue: String,
) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
}

/** The stored mode; the beta's first switch kept a Boolean, where on meant [PageTokenMode.ON]. */
internal fun storedPageTokenMode(value: Any?): PageTokenMode =
    when (value) {
        true -> PageTokenMode.ON
        is String -> PageTokenMode.entries.firstOrNull { it.storageValue == value } ?: PageTokenMode.AUTO
        else -> PageTokenMode.AUTO
    }

/**
 * Whether Automatic should ask with the token first from now on, after a document came [via] the
 * plain address (`address`) or the token (`token`); null when nothing changes.
 */
internal fun learnedTokenFirst(
    mode: PageTokenMode,
    remembered: Boolean,
    via: String?,
): Boolean? =
    when {
        mode != PageTokenMode.AUTO -> null
        via == "token" && !remembered -> true
        via == "address" && remembered -> false
        else -> null
    }

/** What the page's transcript script reported on one poll. */
internal sealed interface PageTranscriptReply {
    /**
     * Still working; [step] is `page-address`, `no-player` or `waiting-for-captions`. [token] says how
     * the page's own proof-of-origin token went (`minting`, `sent`, `empty`, or what failed), when used.
     */
    data class Working(
        val step: String? = null,
        val token: String? = null,
    ) : PageTranscriptReply

    /**
     * The page has the document; [languageCode] and [generated] name the track it came from. With
     * [panel], [text] came from YouTube's transcript panel (see [parseTranscriptPanel]).
     */
    data class Done(
        val text: String,
        val languageCode: String? = null,
        val generated: Boolean? = null,
        val panel: Boolean = false,
        /** How the page got it: `address`, `token`, `player` (the player's own download) or `panel`. */
        val via: String? = null,
    ) : PageTranscriptReply
}

/** Why the page gave no full transcript, shown beside live subtitles. */
enum class PageTranscriptProblem {
    /** The page never answered for this video (not on screen, or the page player had no details yet). */
    NO_ANSWER,

    /** Neither the page player's details, its captions menu nor its downloads list a caption track. */
    NO_TRACKS,

    /** The page has no player to switch captions on with, or its player never finished loading. */
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
 * the page player's track list (its details, else its captions menu and the captions it already
 * downloaded), picks the track the provider's own rules would pick, and takes the document the page
 * player downloads, or, as the last resort, the lines of YouTube's own transcript panel (see
 * `webPageTranscriptScript`). Only one request is open at a time. When the provider stops waiting,
 * the page keeps watching the same video, and a document that arrives later is kept and announced
 * on [lateTranscripts] so the app can load it.
 */
class PageTranscriptSource(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    internal class Request(
        val videoId: String,
        val choice: CaptionLanguageChoice,
        val attempt: Long,
    ) {
        internal val document = CompletableDeferred<PageDocument?>()

        /** Completed when the provider should stop waiting early; the page keeps watching. */
        internal val stopWaiting = CompletableDeferred<Unit>()

        @Volatile var target: PageCaptionTarget? = null
            internal set

        @Volatile internal var languages: List<CaptionLanguage> = emptyList()

        /** The id of the track [target] names, so the result can say which track it is. */
        @Volatile internal var trackId: String = ""

        @Volatile internal var step: String? = null

        /** When the page first listed no track for this video, or null while it has not. */
        @Volatile internal var noTracksSince: Long? = null

        /** Set while the page's player says it is still loading and lists no track yet. */
        @Volatile internal var loading = false

        /** What the page last said about its track sources, in English, for the problem's detail. */
        @Volatile internal var found: String? = null

        /** Set once the provider stopped waiting and the page only watches for captions. */
        @Volatile internal var late = false

        /** How the page's own proof-of-origin token went, for the problem's detail. */
        @Volatile internal var token: String? = null
    }

    internal class PageDocument(
        val target: PageCaptionTarget,
        val languages: List<CaptionLanguage>,
        val text: String,
        val panel: Boolean = false,
        val trackId: String = "",
    )

    private val attempts = AtomicLong()

    @Volatile private var current: Request? = null

    /** A document that arrived after the provider stopped waiting, kept for the next request. */
    @Volatile private var ready: Pair<Request, PageDocument>? = null

    @Volatile private var problem: Pair<String, PageTranscriptProblem>? = null

    @Volatile private var problemDetail: String? = null

    private val late = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Settings → Study tools → Load captions with a page token (beta); targets made from now on follow it. */
    @Volatile var pageTokenMode: PageTokenMode = PageTokenMode.AUTO

    /** Automatic's memory that YouTube wanted a token: later videos then ask with it first. */
    @Volatile var tokenRemembered: Boolean = false

    /** Told when Automatic learns that a token is needed (true) or no longer needed (false), to keep it. */
    @Volatile var onTokenLearned: ((Boolean) -> Unit)? = null

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
            var document =
                kept ?: withTimeoutOrNull(timeoutMs) {
                    select {
                        request.document.onAwait { it }
                        request.stopWaiting.onAwait { null }
                    }
                }
            // An answer that came in just as the wait ended is still taken.
            if (document == null && !watchLater(request)) document = request.document.await()
            waiting = false
            if (document == null) return null
            val cues =
                runCatching {
                    if (document.panel) parseTranscriptPanel(document.text) else CaptionDocumentParser.parse(document.text)
                }.getOrDefault(emptyList())
            if (cues.isEmpty()) {
                fail(request, PageTranscriptProblem.UNREADABLE)
                return null
            }
            val language =
                if (document.target.panelOnly) {
                    // The guess gives way to the lines' own writing system, when it names one.
                    textScriptLanguages(cues.joinToString(" ") { it.text }, mustDominate = true).firstOrNull()
                        ?: document.target.languageCode
                } else {
                    document.target.languageCode
                }
            return CaptionTrackResult(language, document.target.generated, cues, document.languages, document.trackId)
        } finally {
            // Cancelled (another source won, or the video changed): the page stops for this request.
            synchronized(this) { if (waiting && current === request) current = null }
        }
    }

    /** Why the page gave no transcript for [videoId] the last time it was asked, if it gave none. */
    fun problem(videoId: String): PageTranscriptProblem? = problem?.takeIf { it.first == videoId }?.second

    /**
     * English diagnostic text on where the page looked for [videoId]'s captions, beside [problem]
     * (for example "player details 0 tracks, captions menu 0, downloaded 0, playability LOGIN_REQUIRED").
     */
    fun problemDetail(videoId: String): String? = problemDetail?.takeIf { problem?.first == videoId }

    /** The open request for the video now on the page, if any; a late one only watches. */
    internal fun pending(pageVideoId: String?): Request? = current?.takeIf { it.videoId == pageVideoId && !it.document.isCompleted }

    /**
     * Picks the track to fetch from the page player's caption details (see
     * `WEB_PAGE_CAPTION_TRACKS_SCRIPT`). A track without a trusted address is still taken: the page
     * then waits for the player's own download of it. A page that lists no track for
     * [PAGE_NO_TRACKS_GRACE_MS], counted only once its player has finished loading, stops the
     * provider's wait and gets a [PageCaptionTarget.panelOnly] target: the page keeps watching for
     * captions the player downloads and opens its transcript panel, so either still gives a late
     * full transcript.
     */
    internal fun offerTracks(
        request: Request,
        json: String,
    ) {
        if (request !== current || request.target != null) return
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val details = root.optJSONObject("videoDetails")
        if (details?.optString("videoId") != request.videoId) return
        request.found = pageTrackSources(root)
        val renderer = root.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
        val tracks = renderer?.optJSONArray("captionTracks")
        val track =
            renderer?.let {
                selectCaptionTrack(
                    it,
                    request.choice.preferred,
                    details,
                    request.choice.learning,
                    request.choice.track,
                )
            }
        if (tracks == null || track == null) {
            // Like LingoTube, an empty list counts only once the page's player has finished loading.
            request.loading = !root.optBoolean("ready", true)
            if (request.loading) {
                request.noTracksSince = null
                return
            }
            val since = request.noTracksSince ?: clock().also { request.noTracksSince = it }
            if (clock() - since < PAGE_NO_TRACKS_GRACE_MS) return
            // Last resort: YouTube's own transcript panel, in the language the lines turn out to be.
            val guess = request.choice.preferred.firstOrNull() ?: request.choice.learning ?: "en"
            request.target = PageCaptionTarget(request.videoId, guess, false, "", request.attempt, panelOnly = true)
            request.stopWaiting.complete(Unit)
            return
        }
        val baseUrl = track.optString("baseUrl").takeIf { trustedYouTubeCaptionUrl(it) != null }.orEmpty()
        request.languages = availableLanguages(tracks)
        request.trackId = captionTrackId(track)
        // A remote switch can turn page tokens off for everyone while YouTube's token changes break them.
        val mode = if (RemoteSwitchState.switches.value.pageTokenOff) PageTokenMode.OFF else pageTokenMode
        val pageToken = mode != PageTokenMode.OFF && baseUrl.isNotEmpty()
        request.target =
            PageCaptionTarget(
                request.videoId,
                track.optString("languageCode", "en"),
                isGeneratedTrack(track),
                baseUrl,
                request.attempt,
                pageToken = pageToken,
                tokenFirst = pageToken && (mode == PageTokenMode.ON || tokenRemembered),
            )
    }

    /** Takes the page's answer for the request's track; parsing happens on the provider's side. */
    internal fun offerReply(
        request: Request,
        reply: PageTranscriptReply,
    ) {
        if (request !== current) return
        val target = request.target ?: return
        when (reply) {
            is PageTranscriptReply.Working -> {
                reply.step?.let { request.step = it }
                reply.token?.let { request.token = it }
            }
            is PageTranscriptReply.Done -> {
                if (reply.text.length > MAX_PAGE_TRANSCRIPT_LENGTH) return
                learnedTokenFirst(pageTokenMode, tokenRemembered, reply.via)?.let { needed ->
                    tokenRemembered = needed
                    onTokenLearned?.invoke(needed)
                }
                // The page may have found the same language as another kind (auto-generated or not).
                val track =
                    target.copy(
                        languageCode = reply.languageCode ?: target.languageCode,
                        generated = reply.generated ?: target.generated,
                    )
                // Another kind of the same language is another track, whose id the page did not say.
                val trackId =
                    request.trackId
                        .takeIf { track.languageCode == target.languageCode && track.generated == target.generated }
                        .orEmpty()
                val document = PageDocument(track, request.languages, reply.text, reply.panel, trackId)
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
                request.target?.panelOnly == true -> PageTranscriptProblem.NO_TRACKS
                request.target == null && request.loading -> PageTranscriptProblem.NO_PLAYER
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
        problemDetail =
            listOfNotNull(request.found, request.step?.let { "step $it" }, request.token?.let { "page token $it" })
                .joinToString(", ")
                .ifBlank { null }
        problem = request.videoId to reason
        // Fixed categories only, like the provider's: never page text or addresses in logcat.
        android.util.Log.w("CaptionRecovery", "page: ${reason.name.lowercase()}")
    }

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageTranscriptSource()
    }
}

/**
 * Where the page found caption tracks, from the `found` counts of `WEB_PAGE_CAPTION_TRACKS_SCRIPT`,
 * as English diagnostic text; numbers and YouTube's fixed playability status only, never page text.
 */
internal fun pageTrackSources(root: JSONObject): String? {
    val found = root.optJSONObject("found") ?: return null
    val count = { key: String -> found.optInt(key).coerceIn(0, 999) }
    val playability = found.optString("playability").takeIf { it.matches(Regex("[A-Z_]{1,30}")) && it != "OK" }
    return listOfNotNull(
        "player details ${count("details")} tracks",
        "captions menu ${count("menu")}",
        "downloaded ${count("downloaded")}",
        playability?.let { "playability $it" },
    ).joinToString(", ")
}
