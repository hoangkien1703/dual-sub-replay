package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlin.math.abs

/** Pausing through a page script lands late, so a line's pause is asked for this long before its end. */
internal const val STUDY_PAUSE_LEAD_MS = 100L

/** A line end that a tick stepped past is still handled this long after it. */
internal const val STUDY_CATCH_UP_MS = 400L

/** Read first stops this far into the next line, so that line (not the last one) is on screen. */
internal const val STUDY_READ_OFFSET_MS = 20L

/** Skip silence only acts on parts with no subtitle line longer than this. */
internal const val SKIP_SILENCE_MIN_GAP_MS = 1_500L

/** Jump lands this long before the next line, so its first word is not cut. */
internal const val SKIP_SILENCE_JUMP_LEAD_MS = 300L

/** The user's speed comes back this long (of real time) before the next line. */
private const val SKIP_SILENCE_RESTORE_REAL_MS = 250L

/** A seek landing this close to the one the driver asked for is the driver's own. */
private const val OWN_SEEK_TOLERANCE_MS = 1_500L

/** What [StudyPlaybackDriver] asks the page video to do. */
internal sealed interface StudyAction {
    data object Pause : StudyAction

    /** Seeks to [ms] and plays. */
    data class PlayFrom(
        val ms: Long,
    ) : StudyAction

    /** Pauses with the video at [ms]. */
    data class PauseAt(
        val ms: Long,
    ) : StudyAction

    /**
     * Plays a part with no subtitle line at [rate], keeping the user's own speed to come back to.
     * The page brings that speed back by itself after [safetyMs], should no tick ask for it first.
     */
    data class GapSpeed(
        val rate: Float,
        val safetyMs: Long,
    ) : StudyAction

    /** Brings back the user's own speed. */
    data object NormalSpeed : StudyAction
}

/** One playback tick, as the page reports it every 33 ms. */
internal data class StudyTick(
    val videoId: String,
    val timeMs: Long,
    val sessionId: String,
    val paused: Boolean,
    val segments: List<SubtitleSegment>,
    val settings: StudySettings,
    /** Lines with exactly one new word, for [StudyPlayMode.NEW_WORD_LINES]. */
    val newWordLines: Set<Int> = emptySet(),
    /** False in live fallback, while a saved word's clip plays, or for another video. */
    val active: Boolean = true,
)

/**
 * Play modes and skip silence (Settings → Study tools), as decisions on playback ticks. It never
 * acts while the video is paused. A seek it did not ask for (a new page session) starts again from
 * where it lands, so a line the user tapped plays without a pause first; a line's end is handled
 * once, so playing on after its pause goes on to the next line.
 */
internal class StudyPlaybackDriver {
    private class OwnSeek(
        val targetMs: Long,
        /** The line whose end handling starts again when the seek lands (a repeat). */
        val replayLine: Int = -1,
    )

    private var videoId: String? = null
    private var session: String? = null
    private var entered = -1
    private var handled = -1
    private var readStartHandled = -1
    private var repeats = 0
    private var jumpedGap = -1
    private var gapRate: Float? = null
    private var ownSeek: OwnSeek? = null

    fun tick(tick: StudyTick): StudyAction? {
        if (tick.videoId != videoId) {
            val fast = gapRate != null
            reset()
            videoId = tick.videoId
            if (fast) return StudyAction.NormalSpeed
        }
        val seek = session != null && tick.sessionId != session
        session = tick.sessionId
        if (seek) landSeek(tick.timeMs)
        val mode = if (tick.active) tick.settings.effectivePlayMode else StudyPlayMode.NORMAL
        val skip = if (tick.active) tick.settings.skipSilence else SkipSilence.OFF
        if (gapRate != null && (tick.paused || skip.rate == null)) return normalSpeed()
        if (tick.paused || tick.segments.isEmpty() || (mode == StudyPlayMode.NORMAL && skip == SkipSilence.OFF)) return null
        val index = lineAtOrBefore(tick.segments, tick.timeMs)
        return lineEnd(tick, mode, index) ?: readFirstStart(tick, mode, index) ?: enter(tick, index) ?: silence(tick, skip, index)
    }

    private fun reset() {
        session = null
        forgetLines()
        gapRate = null
        ownSeek = null
    }

    private fun forgetLines() {
        entered = -1
        handled = -1
        readStartHandled = -1
        repeats = 0
        jumpedGap = -1
    }

    private fun landSeek(timeMs: Long) {
        val own = ownSeek?.takeIf { abs(timeMs - it.targetMs) <= OWN_SEEK_TOLERANCE_MS }
        ownSeek = null
        if (own == null) {
            forgetLines()
        } else if (own.replayLine >= 0) {
            handled = -1
        }
    }

    /** The end of the line playing since it was entered: pause, read the next one, or repeat it. */
    private fun lineEnd(
        tick: StudyTick,
        mode: StudyPlayMode,
        index: Int,
    ): StudyAction? {
        val line = entered
        if (mode == StudyPlayMode.NORMAL || line !in tick.segments.indices || handled == line) return null
        val end = lineBoundary(tick.segments, line)
        if (tick.timeMs < end - STUDY_PAUSE_LEAD_MS) return null
        if (index != line && tick.timeMs >= end + STUDY_CATCH_UP_MS) return null
        handled = line
        return when (mode) {
            StudyPlayMode.PAUSE_AFTER_LINE, StudyPlayMode.LISTEN_FIRST -> StudyAction.Pause
            StudyPlayMode.NEW_WORD_LINES -> StudyAction.Pause.takeIf { line in tick.newWordLines }
            StudyPlayMode.READ_FIRST -> readNext(tick.segments, line + 1) ?: StudyAction.Pause
            StudyPlayMode.REPEAT_LINE -> repeat(tick, line)
            StudyPlayMode.NORMAL -> null
        }
    }

    private fun repeat(
        tick: StudyTick,
        line: Int,
    ): StudyAction? {
        if (repeats >= tick.settings.repeatCount - 1) return null
        repeats++
        val start = tick.segments[line].startMs
        ownSeek = OwnSeek(start, replayLine = line)
        return StudyAction.PlayFrom(start)
    }

    private fun readNext(
        segments: List<SubtitleSegment>,
        next: Int,
    ): StudyAction? {
        val segment = segments.getOrNull(next) ?: return null
        readStartHandled = next
        val target = segment.startMs + STUDY_READ_OFFSET_MS
        ownSeek = OwnSeek(target)
        return StudyAction.PauseAt(target)
    }

    /** Read first, before the first line or after a seek into a part with no line: stop at the next line. */
    private fun readFirstStart(
        tick: StudyTick,
        mode: StudyPlayMode,
        index: Int,
    ): StudyAction? {
        if (mode != StudyPlayMode.READ_FIRST) return null
        val next = index + 1
        val segment = tick.segments.getOrNull(next) ?: return null
        val inGap = index < 0 || tick.timeMs >= tick.segments[index].endMs
        if (!inGap || readStartHandled == next || segment.startMs - tick.timeMs > STUDY_PAUSE_LEAD_MS) return null
        return readNext(tick.segments, next)
    }

    private fun enter(
        tick: StudyTick,
        index: Int,
    ): StudyAction? {
        if (index < 0 || index == entered) return null
        if (tick.timeMs >= lineBoundary(tick.segments, index) - STUDY_PAUSE_LEAD_MS) return null
        entered = index
        repeats = 0
        return null
    }

    private fun silence(
        tick: StudyTick,
        skip: SkipSilence,
        index: Int,
    ): StudyAction? {
        val next = tick.segments.getOrNull(index + 1)
        val inGap = index < 0 || tick.timeMs >= tick.segments[index].endMs
        if (next == null || !inGap) return if (gapRate != null) normalSpeed() else null
        val remaining = next.startMs - tick.timeMs
        val rate = skip.rate
        return when {
            skip == SkipSilence.JUMP && remaining > SKIP_SILENCE_MIN_GAP_MS && jumpedGap != index + 1 -> {
                jumpedGap = index + 1
                val target = next.startMs - SKIP_SILENCE_JUMP_LEAD_MS
                ownSeek = OwnSeek(target)
                StudyAction.PlayFrom(target)
            }
            rate == null -> null
            gapRate == null && remaining > SKIP_SILENCE_MIN_GAP_MS -> {
                gapRate = rate
                StudyAction.GapSpeed(rate, (remaining / rate).toLong())
            }
            gapRate != null && remaining <= (SKIP_SILENCE_RESTORE_REAL_MS * (gapRate ?: 1f)).toLong() -> normalSpeed()
            else -> null
        }
    }

    private fun normalSpeed(): StudyAction {
        gapRate = null
        return StudyAction.NormalSpeed
    }
}

/** The last line starting at or before [timeMs] (it may have ended), or -1 before the first line. */
internal fun lineAtOrBefore(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = segments.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (segments[middle].startMs <= timeMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return candidate
}

/** Where line [index] stops being the line heard: its end, or the next line's start when that comes first. */
internal fun lineBoundary(
    segments: List<SubtitleSegment>,
    index: Int,
): Long {
    val segment = segments[index]
    val next = segments.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
    return minOf(segment.endMs, next).coerceAtLeast(segment.startMs)
}

/**
 * Runs [StudyPlaybackDriver] on the page's playback ticks and carries out its actions through
 * [controller]. Composition keeps [state], [settings] and [newWordLines] current.
 */
internal class StudyPlaybackRunner(
    private val controller: YouTubeWebController,
) {
    private val driver = StudyPlaybackDriver()
    private var paused = false

    @Volatile var state: DualSubUiState? = null

    @Volatile var settings = StudySettings()

    @Volatile var newWordLines: Set<Int> = emptySet()

    fun onPaused(paused: Boolean) {
        this.paused = paused
    }

    /** The page's paused tick for [next] (the view model), then for the play modes. */
    fun afterPaused(next: (String, Boolean) -> Unit): (String, Boolean) -> Unit =
        { videoId, paused ->
            next(videoId, paused)
            onPaused(paused)
        }

    /** The page's playback tick for [next] (the view model), then for the play modes. */
    fun afterSecond(next: (String, Float, LiveCaptionSample?, String) -> Unit): (String, Float, LiveCaptionSample?, String) -> Unit =
        { videoId, second, caption, sessionId ->
            next(videoId, second, caption, sessionId)
            onSecond(videoId, second, sessionId)
        }

    fun onSecond(
        videoId: String,
        second: Float,
        sessionId: String,
    ) {
        val current = state ?: return
        val active = !current.liveFallback && current.activeVideoId == videoId && !controller.clipPlaying
        val tick =
            StudyTick(
                videoId = videoId,
                timeMs = (second * MS_PER_SECOND).toLong(),
                sessionId = sessionId,
                paused = paused,
                segments = current.segments,
                settings = settings,
                newWordLines = newWordLines,
                active = active,
            )
        when (val action = driver.tick(tick)) {
            StudyAction.Pause -> controller.pause()
            is StudyAction.PlayFrom -> controller.replayFrom(action.ms / MS_PER_SECOND)
            is StudyAction.PauseAt -> controller.seekPaused(action.ms / MS_PER_SECOND)
            is StudyAction.GapSpeed -> controller.setGapSpeed(action.rate, action.safetyMs)
            StudyAction.NormalSpeed -> controller.setGapSpeed(null, 0L)
            null -> Unit
        }
    }
}

private const val MS_PER_SECOND = 1_000f

/** The play modes and skip silence for the page video, kept current with [state] and the settings. */
@Composable
internal fun rememberStudyPlaybackRunner(
    controller: YouTubeWebController,
    state: DualSubUiState,
): StudyPlaybackRunner {
    val runner = remember(controller) { StudyPlaybackRunner(controller) }
    val settings by rememberStudySettings()
    val comprehension = LocalStudyTools.current?.comprehension
    val newWordLines = comprehension?.oneNewWordLines.orEmpty()
    SideEffect {
        runner.state = state
        runner.settings = settings
        runner.newWordLines = newWordLines
    }
    return runner
}
