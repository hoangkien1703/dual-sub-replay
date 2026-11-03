package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/**
 * A tick this far before a tapped line's start already counts as there. Earlier ticks still show
 * where the video was: the page can report the new time before its seek event (and new session).
 */
private const val LEARNER_SEEK_EARLY_MS = 50L

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
        /** True for a repeat: the line's end is handled again when the seek lands. */
        val replay: Boolean = false,
    ) {
        var landed = false
    }

    private var videoId: String? = null
    private var session: String? = null

    // Lines are kept by [SubtitleSegment.id], not by index: the segments are a window that slides
    // with playback (also back, after a repeat), so an index can name another line a tick later.
    private var entered: Long? = null
    private var handled: Long? = null
    private var readStartHandled: Long? = null

    /** The line Repeat each line is counting, and how many times it has played again. */
    private var repeatedLine: Long? = null
    private var repeats = 0
    private var jumpedGap: Long? = null
    private var gapRate: Float? = null
    private var ownSeek: OwnSeek? = null

    /** Where a line the learner tapped starts, until playback lands there. */
    private var learnerTarget: Long? = null

    /**
     * Where the last seek (a repeat or a tapped line) went, until playback reaches it. A seek can
     * land a little early, in the line before; that line is not entered, or its end would take the
     * video back to it.
     */
    private var seekFloor: Long? = null

    /** True once the seek behind [seekFloor] has landed; ticks before that still show the old place. */
    private var floorLanded = false

    /**
     * The learner tapped a line (or the AI or the remote asked for one): the line playing before is
     * done with, and no tick counts until playback reaches [targetMs]. Without this, a tick that
     * already shows the next line under the old session ended the old line, and Repeat each line
     * took the video back to it.
     */
    fun learnerSeek(targetMs: Long) {
        ownSeek = null
        forgetLines()
        learnerTarget = targetMs
        seekFloor = targetMs
        floorLanded = false
    }

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
        if (!landedOnLearnerTarget(seek, tick.timeMs)) return if (gapRate != null) normalSpeed() else null
        passSeekTargets(tick.timeMs)
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
        learnerTarget = null
        seekFloor = null
    }

    /**
     * A seek of the driver's own stays known until playback is well past it, because the page can
     * report one seek as two sessions; the second must not start the line's count again.
     */
    private fun passSeekTargets(timeMs: Long) {
        ownSeek?.let { if (it.landed && timeMs > it.targetMs + OWN_SEEK_TOLERANCE_MS) ownSeek = null }
        seekFloor?.let { if (floorLanded && timeMs >= it) seekFloor = null }
    }

    /** False while a tapped line's seek has not landed yet; the tick that lands starts afresh. */
    private fun landedOnLearnerTarget(
        seek: Boolean,
        timeMs: Long,
    ): Boolean {
        val target = learnerTarget ?: return true
        if (!seek && timeMs !in (target - LEARNER_SEEK_EARLY_MS)..(target + OWN_SEEK_TOLERANCE_MS)) return false
        learnerTarget = null
        floorLanded = true
        forgetLines()
        return true
    }

    private fun forgetLines() {
        entered = null
        handled = null
        readStartHandled = null
        repeatedLine = null
        repeats = 0
        jumpedGap = null
    }

    private fun landSeek(timeMs: Long) {
        val own = ownSeek?.takeIf { abs(timeMs - it.targetMs) <= OWN_SEEK_TOLERANCE_MS }
        if (own == null) {
            ownSeek = null
            if (seekFloor?.let { abs(timeMs - it) > OWN_SEEK_TOLERANCE_MS } == true) seekFloor = null
            forgetLines()
        } else {
            own.landed = true
            floorLanded = true
            if (own.replay) handled = null
        }
    }

    /** The end of the line playing since it was entered: pause, read the next one, or repeat it. */
    private fun lineEnd(
        tick: StudyTick,
        mode: StudyPlayMode,
        index: Int,
    ): StudyAction? {
        val id = entered
        if (mode == StudyPlayMode.NORMAL || id == null || handled == id) return null
        val line = tick.segments.indexOfFirst { it.id == id }
        if (line < 0) return null
        val end = lineBoundary(tick.segments, line)
        if (tick.timeMs < end - STUDY_PAUSE_LEAD_MS) return null
        if (index != line && tick.timeMs >= end + STUDY_CATCH_UP_MS) return null
        handled = id
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
        val segment = tick.segments[line]
        if (segment.id != repeatedLine) {
            repeatedLine = segment.id
            repeats = 0
        }
        val count = tick.settings.repeatCount
        if (count != STUDY_REPEAT_FOREVER && repeats >= count - 1) return null
        repeats++
        val start = segment.startMs
        ownSeek = OwnSeek(start, replay = true)
        seekFloor = start
        floorLanded = false
        return StudyAction.PlayFrom(start)
    }

    private fun readNext(
        segments: List<SubtitleSegment>,
        next: Int,
    ): StudyAction? {
        val segment = segments.getOrNull(next) ?: return null
        readStartHandled = segment.id
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
        if (!inGap || readStartHandled == segment.id || segment.startMs - tick.timeMs > STUDY_PAUSE_LEAD_MS) return null
        return readNext(tick.segments, next)
    }

    private fun enter(
        tick: StudyTick,
        index: Int,
    ): StudyAction? {
        val segment = tick.segments.getOrNull(index) ?: return null
        if (segment.id == entered) return null
        if (seekFloor?.let { segment.startMs + LEARNER_SEEK_EARLY_MS < it } == true) return null
        if (tick.timeMs >= lineBoundary(tick.segments, index) - STUDY_PAUSE_LEAD_MS) return null
        entered = segment.id
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
            skip == SkipSilence.JUMP && remaining > SKIP_SILENCE_MIN_GAP_MS && jumpedGap != next.id -> {
                jumpedGap = next.id
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

    /** A line the learner tapped: see [StudyPlaybackDriver.learnerSeek]. */
    fun onLearnerSeek(second: Float) {
        driver.learnerSeek((second * MS_PER_SECOND).toLong())
    }

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
            is StudyAction.PlayFrom -> controller.replayFrom(action.ms / MS_PER_SECOND, byPlayMode = true)
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
    DisposableEffect(controller, runner) {
        val listener: (Float) -> Unit = runner::onLearnerSeek
        controller.learnerSeekListener = listener
        onDispose { if (controller.learnerSeekListener === listener) controller.learnerSeekListener = null }
    }
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
