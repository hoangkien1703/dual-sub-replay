package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AiPartAnswer
import com.kienhoang.dualsubreplay.assistant.AiSubtitleException
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.data.AI_SUBTITLE_PART_MS
import com.kienhoang.dualsubreplay.data.AiSubtitleStore
import com.kienhoang.dualsubreplay.data.AiSubtitleTrack
import com.kienhoang.dualsubreplay.data.ProblemArea
import com.kienhoang.dualsubreplay.data.ProblemLog
import com.kienhoang.dualsubreplay.data.aiSubtitlePartStart
import com.kienhoang.dualsubreplay.data.nextAiSubtitlePart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the no-captions card shows while subtitles are made for [videoId], or after it failed. */
data class AiSubtitleStatus(
    val videoId: String,
    val working: Boolean,
    /** How much of the video has subtitles so far. */
    val doneMs: Long = 0L,
    /** The video's length, when known. */
    val totalMs: Long? = null,
    val problem: AiSubtitleProblem? = null,
)

/** Problems that may pass by themselves, so a part asked while the subtitles already show is asked again later. */
private val PASSING_PROBLEMS = setOf(AiSubtitleProblem.LIMIT, AiSubtitleProblem.BUSY, AiSubtitleProblem.NETWORK)

/** Waits before asking again for a later part, while the parts made so far already show. */
internal val AI_SUBTITLE_RETRY_WAITS_MS = listOf(30_000L, 60_000L, 120_000L)

/**
 * Makes AI subtitles for one video, part by part, from the part that is playing: [onReady] loads
 * them as soon as the first part has lines, and once more when every part is in. Each part is saved
 * to [store] as it comes, so leaving the video and opening it again carries on where it stopped.
 */
internal class AiSubtitleRunner(
    private val scope: CoroutineScope,
    private val store: AiSubtitleStore,
    private val askPart: suspend (videoId: String, startMs: Long, endMs: Long) -> AiPartAnswer,
    private val durationOf: (videoId: String) -> Long?,
    private val onStatus: (AiSubtitleStatus?) -> Unit,
    private val onReady: (videoId: String) -> Unit,
    private val retryWaitsMs: List<Long> = AI_SUBTITLE_RETRY_WAITS_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    /** The video subtitles are being made for, or null. */
    var videoId: String? = null
        private set

    fun isRunning(videoId: String): Boolean = this.videoId == videoId && job?.isActive == true

    /** The no-captions card's button: starts, or carries on after a problem. */
    fun start(
        videoId: String,
        positionMs: Long,
    ) = launch(videoId, positionMs, showing = false)

    /** A loaded AI track: makes its missing parts, unless it is complete or already being made. */
    fun resumeIfUnfinished(
        videoId: String,
        positionMs: Long,
    ) {
        if (!isRunning(videoId)) launch(videoId, positionMs, showing = true)
    }

    /** Waits until the current work ends (tests). */
    internal suspend fun join() {
        job?.join()
    }

    /** Another video opened: the work stops, and the parts made so far stay saved. */
    fun stop() {
        job?.cancel()
        job = null
        if (videoId != null) onStatus(null)
        videoId = null
    }

    private fun launch(
        videoId: String,
        positionMs: Long,
        showing: Boolean,
    ) {
        if (isRunning(videoId)) return
        job?.cancel()
        this.videoId = videoId
        job = scope.launch { run(videoId, positionMs, showing) }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun run(
        videoId: String,
        positionMs: Long,
        showingAtStart: Boolean,
    ) {
        var track = withContext(io) { store.read(videoId) } ?: AiSubtitleTrack(videoId, "", emptyMap())
        if (showingAtStart && track.complete) return
        var endMs = track.endMs ?: durationOf(videoId)
        val first = aiSubtitlePartStart(positionMs).let { start -> if (endMs != null && start >= endMs) 0L else start }
        var showing = showingAtStart && track.cues().isNotEmpty()
        var shownParts = if (showing) track.parts.keys else emptySet()
        var waits = retryWaitsMs.iterator()
        // A loaded unfinished track carries on quietly; the card shows progress only before anything shows.
        if (!showing) onStatus(progress(track, endMs))
        while (true) {
            val start = nextAiSubtitlePart(first, track.parts.keys, endMs) ?: break
            val answer =
                try {
                    askPart(videoId, start, start + AI_SUBTITLE_PART_MS)
                } catch (error: AiSubtitleException) {
                    ProblemLog.record(ProblemArea.CAPTIONS, "AI subtitles ${error.problem.name}")
                    if (showing && error.problem in PASSING_PROBLEMS && waits.hasNext()) {
                        delay(waits.next())
                        continue
                    }
                    if (!showing) onStatus(AiSubtitleStatus(videoId, working = false, problem = error.problem))
                    return
                }
            waits = retryWaitsMs.iterator()
            track =
                when (answer) {
                    AiPartAnswer.PastTheEnd -> {
                        endMs = start
                        track.copy(endMs = start)
                    }
                    is AiPartAnswer.Written -> track.withPart(start, answer.part)
                }
            withContext(io) { store.write(track) }
            if (!showing && track.cues().isNotEmpty()) {
                showing = true
                shownParts = track.parts.keys
                onReady(videoId)
            }
            if (!showingAtStart) onStatus(progress(track, endMs))
        }
        track = track.copy(endMs = endMs, complete = true)
        withContext(io) { store.write(track) }
        if (track.cues().isEmpty()) {
            onStatus(AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.NO_SPEECH))
            return
        }
        onStatus(null)
        if (track.parts.keys != shownParts) onReady(videoId)
    }

    private fun progress(
        track: AiSubtitleTrack,
        endMs: Long?,
    ): AiSubtitleStatus {
        val made = track.parts.keys.size * AI_SUBTITLE_PART_MS
        return AiSubtitleStatus(track.videoId, working = true, doneMs = endMs?.let { made.coerceAtMost(it) } ?: made, totalMs = endMs)
    }
}
