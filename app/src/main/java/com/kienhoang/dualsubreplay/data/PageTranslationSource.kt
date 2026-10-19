package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** How long a video waits for YouTube's own translation before it translates on the device. */
internal const val PAGE_TRANSLATION_TIMEOUT_MS = 15_000L

/** YouTube's translation of one caption track, asked for on the page (see `webPageTranslationScript`). */
internal data class PageTranslationTarget(
    val videoId: String,
    val languageCode: String,
    val generated: Boolean,
    /** The language YouTube translates into, as the app names it (YouTube's own code is found on the page). */
    val translateTo: String,
    /** Each request is its own attempt on the page. */
    val attempt: Long = 0,
)

/** What the page's translation script reported on one poll. */
internal sealed interface PageTranslationReply {
    /** Still working; [step] is `no-track`, `page-address`, `no-player` or `waiting-for-captions`. */
    data class Working(
        val step: String? = null,
    ) : PageTranslationReply

    /** YouTube's translated captions, as YouTube sent them. */
    data class Done(
        val text: String,
    ) : PageTranslationReply

    /** YouTube does not translate this track into that language. */
    data object Unavailable : PageTranslationReply
}

/**
 * Hands YouTube's own translation of a caption track over from the YouTube page, the way LingoTube
 * gets its second line: when Google Translate refuses the phone (as on some mobile networks), YouTube's
 * captions still come through the page that plays the video. The translator asks for a track; the page
 * side runs `webPageTranslationScript` until it has the document. Only one request is open at a time,
 * and the last translation is kept, so translating the same video again does not ask the page twice.
 */
class PageTranslationSource {
    internal class Request(
        val target: PageTranslationTarget,
    ) {
        internal val document = CompletableDeferred<String?>()

        @Volatile internal var step: String? = null
    }

    private val attempts = AtomicLong()

    @Volatile private var current: Request? = null

    @Volatile private var kept: Pair<PageTranslationTarget, List<RawCaptionCue>>? = null

    /** YouTube's translation of the track as cues, or null when the page cannot get it within [timeoutMs]. */
    internal suspend fun translation(
        videoId: String,
        languageCode: String,
        generated: Boolean,
        translateTo: String,
        timeoutMs: Long = PAGE_TRANSLATION_TIMEOUT_MS,
    ): List<RawCaptionCue>? {
        val target = PageTranslationTarget(videoId, languageCode, generated, translateTo)
        kept?.takeIf { it.first == target }?.let { return it.second }
        val request = Request(target.copy(attempt = attempts.incrementAndGet()))
        synchronized(this) {
            current?.document?.complete(null)
            current = request
        }
        try {
            val text = withTimeoutOrNull(timeoutMs) { request.document.await() }
            val cues = text?.let { runCatching { CaptionDocumentParser.parse(it) }.getOrDefault(emptyList()) }.orEmpty()
            if (cues.isEmpty()) {
                // Fixed categories only: never page text or addresses in logcat.
                android.util.Log.w(
                    "CaptionRecovery",
                    "youtube translation: ${if (text == null) request.step ?: "no-answer" else "unreadable"}",
                )
                return null
            }
            kept = target to cues
            return cues
        } finally {
            synchronized(this) { if (current === request) current = null }
        }
    }

    /** The open request for the video now on the page, if any. */
    internal fun pending(pageVideoId: String?): Request? = current?.takeIf { it.target.videoId == pageVideoId && !it.document.isCompleted }

    /** Takes the page's answer for [request]; answers for a replaced request are ignored. */
    internal fun offer(
        request: Request,
        reply: PageTranslationReply,
    ) {
        if (request !== current) return
        when (reply) {
            is PageTranslationReply.Working -> reply.step?.let { request.step = it }
            is PageTranslationReply.Done -> if (reply.text.length <= MAX_PAGE_TRANSCRIPT_LENGTH) request.document.complete(reply.text)
            PageTranslationReply.Unavailable -> {
                request.step = "not-translatable"
                request.document.complete(null)
            }
        }
    }

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageTranslationSource()
    }
}
