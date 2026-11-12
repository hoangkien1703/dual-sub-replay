package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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

/** The video this far from the looped line, with no seek the driver knows of, means it moved elsewhere. */
private const val LOOP_DRIFT_MS = 2_000L

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
    /** The line the loop button repeats ([LineLoop]), or null. */
    val loopLineId: Long? = null,
)

/**
 * The line the loop button on the playing line repeats (Settings → Study tools → Loop button on the
 * playing line, owner request 2026-11-06), or null. The transcript panel and the playback ticks share it.
 */
internal class LineLoop {
    var lineId by mutableStateOf<Long?>(null)

    fun toggle(id: Long) {
        lineId = if (lineId == id) null else id
    }

    /**
     * The loop moved to another line (the learner tapped one or sought there) or ended ([LoopMove.to]
     * null). Only the loop it moved from changes, so a loop just turned on for another line stays.
     */
    fun follow(move: LoopMove) {
        if (lineId == move.from) lineId = move.to
    }
}

/** The loop button's loop went from line [from] to line [to], or ended when [to] is null. */
internal data class LoopMove(
    val from: Long,
    val to: Long?,
)

internal val LocalLineLoop = staticCompositionLocalOf<LineLoop?> { null }

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

    /** The looped line the last tick saw. */
    private var loopLine: Long? = null

    /** The loop's last move: until the transcript panel follows it, a tick asking for [LoopMove.from] means [LoopMove.to]. */
    private var loopMoved: LoopMove? = null

    /** [loopMoved], until [takeLoopMove] passes it on. */
    private var loopMoveToReport: LoopMove? = null

    /**
     * Where the learner moved the video (a tapped line, YouTube's bar), until a tick whose lines
     * reach that far moves the loop there. The lines can be a window that has not caught up yet.
     */
    private var learnerMoveToCheck: Long? = null

    /** True while the loop waits for the lines to reach where the learner moved the video: it does nothing then. */
    private var loopWaiting = false

    /** Where the loop went since the last call (another line, or ended by another video), if anywhere. */
    fun takeLoopMove(): LoopMove? = loopMoveToReport.also { loopMoveToReport = null }

    private fun moveLoop(to: Long?) {
        val from = loopLine ?: return
        if (to == from) return
        LoopMove(from, to).let {
            loopMoved = it
            loopMoveToReport = it
        }
        loopLine = to
    }

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
        learnerMoveToCheck = targetMs
    }

    fun tick(tick: StudyTick): StudyAction? {
        if (tick.videoId != videoId) {
            val fast = gapRate != null
            if (videoId != null) moveLoop(null)
            reset()
            videoId = tick.videoId
            if (fast) return StudyAction.NormalSpeed
        }
        val seek = session != null && tick.sessionId != session
        session = tick.sessionId
        if (seek) landSeek(tick.timeMs)
        val loopId = checkLoop(tick)
        if (!landedOnLearnerTarget(seek, tick.timeMs)) return if (gapRate != null) normalSpeed() else null
        passSeekTargets(tick.timeMs)
        if (loopId != null) {
            if (gapRate != null) return normalSpeed()
            return if (tick.paused) null else loop(tick, loopId)
        }
        val mode = if (tick.active) tick.settings.effectivePlayMode else StudyPlayMode.NORMAL
        val skip = if (tick.active) tick.settings.skipSilence else SkipSilence.OFF
        if (gapRate != null && (tick.paused || skip.rate == null)) return normalSpeed()
        if (tick.paused || tick.segments.isEmpty() || (mode == StudyPlayMode.NORMAL && skip == SkipSilence.OFF)) return null
        val index = lineAtOrBefore(tick.segments, tick.timeMs)
        return lineEnd(tick, mode, index) ?: readFirstStart(tick, mode, index) ?: enter(tick, index) ?: silence(tick, skip, index)
    }

    /**
     * The line the loop button repeats on this tick, or null. The loop stays on until the learner
     * taps its button again (owner request 2026-11-06): tapping another line, or seeking with
     * YouTube's bar, moves it to the line the video went to.
     */
    private fun checkLoop(tick: StudyTick): Long? {
        var asked = tick.loopLineId?.takeIf { tick.active }
        val moved = loopMoved
        if (moved != null && asked == moved.from) asked = moved.to else loopMoved = null
        if (asked != loopLine) {
            // A loop just turned on starts on its own line, whatever the learner did before.
            loopLine = asked
            learnerMoveToCheck = null
        }
        loopWaiting = false
        if (asked == null) return null
        var target = learnerMoveToCheck ?: driftedTo(tick, asked) ?: return asked
        val settled = learnerTarget == null
        if (!linesReach(tick.segments, target) && settled && linesReach(tick.segments, tick.timeMs)) target = tick.timeMs
        if (!linesReach(tick.segments, target)) {
            learnerMoveToCheck = target
            loopWaiting = true
            return asked
        }
        learnerMoveToCheck = null
        moveLoop(loopLineAt(tick.segments, target)?.id)
        return loopLine
    }

    /**
     * Where the video is when it is well away from the looped line without the driver knowing why
     * (a seek it did not see, the end of an ad), so the loop follows it there instead of going back.
     */
    private fun driftedTo(
        tick: StudyTick,
        id: Long,
    ): Long? {
        val line = tick.segments.indexOfFirst { it.id == id }
        // The lines are a window that slides with playback: remember where the looped line is for when it slides out.
        if (line >= 0) loopSpan = id to (tick.segments[line].startMs..lineBoundary(tick.segments, line))
        if (learnerTarget != null || ownSeek?.landed == false) return null
        val span = loopSpan?.takeIf { it.first == id }?.second ?: return null
        val away = tick.timeMs < span.first - LOOP_DRIFT_MS || tick.timeMs > span.last + LOOP_DRIFT_MS
        return tick.timeMs.takeIf { away }
    }

    /** The looped line's start and end, as last seen in the lines. */
    private var loopSpan: Pair<Long, LongRange>? = null

    /** The looped line's end takes the video back to its start, for as long as the loop is on. */
    private fun loop(
        tick: StudyTick,
        id: Long,
    ): StudyAction? {
        val line = tick.segments.indexOfFirst { it.id == id }
        if (loopWaiting || line < 0 || ownSeek?.landed == false) return null
        val segment = tick.segments[line]
        if (tick.timeMs < lineBoundary(tick.segments, line) - STUDY_PAUSE_LEAD_MS) return null
        ownSeek = OwnSeek(segment.startMs, replay = true)
        seekFloor = segment.startMs
        floorLanded = false
        return StudyAction.PlayFrom(segment.startMs)
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
            // A seek of the learner's own (YouTube's bar, double tap) takes the loop along; a tapped line does so on its own.
            if (learnerTarget == null) learnerMoveToCheck = timeMs
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
            StudyPlayMode.PAUSE_AFTER_LINE, StudyPlayMode.LISTEN_FIRST, StudyPlayMode.DICTATION -> StudyAction.Pause
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
 * Whether the play modes, the loop or skip silence steer the video: then the page's time and the
 * lines around it are followed off screen too (screen off, or the app in the background), where
 * they otherwise rest (25kiuo, #81, 2026-11-12).
 */
internal fun studySteersPlayback(
    settings: StudySettings,
    loopLineId: Long?,
): Boolean = settings.effectivePlayMode != StudyPlayMode.NORMAL || loopLineId != null || settings.skipSilence != SkipSilence.OFF

/** Read by the page's playback poll, which lives apart from the runner; see [studySteersPlayback]. */
internal object StudyOffScreen {
    @Volatile var steering = false
}

/**
 * Runs [StudyPlaybackDriver] on the page's playback ticks and carries out its actions through
 * [controller]. Composition keeps [state], [settings] and [newWordLines] current; off screen,
 * where composition rests, [liveState] gives the view model's state.
 */
internal class StudyPlaybackRunner(
    private val controller: YouTubeWebController,
) {
    private val driver = StudyPlaybackDriver()
    private var paused = false

    /** The loop button's line, shared with the transcript panel. */
    val lineLoop = LineLoop()

    /** Dictation's revealed lines and answers, kept while the panel closes or the phone turns. */
    val dictation = DictationState()

    @Volatile var state: DualSubUiState? = null

    @Volatile var settings = StudySettings()

    @Volatile var newWordLines: Set<Int> = emptySet()

    /** The view model's state now, kept current off screen too (composition's [state] waits for the screen). */
    @Volatile var liveState: (() -> DualSubUiState)? = null

    /** Told when [studySteersPlayback] changes, so the lines around the playing time keep coming off screen. */
    @Volatile var onSteeringChanged: ((Boolean) -> Unit)? = null

    private var steering = false

    /** Tells the page poll and [onSteeringChanged] whether the play modes steer the video now. */
    fun updateSteering() {
        val now = studySteersPlayback(settings, if (settings.loopButton) lineLoop.lineId else null)
        StudyOffScreen.steering = now
        if (now != steering) {
            steering = now
            onSteeringChanged?.invoke(now)
        }
    }

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
        val current = liveState?.invoke() ?: state ?: return
        // An ad plays on the same page with its own clock: the play modes and the loop leave it alone.
        val active = !current.liveFallback && current.activeVideoId == videoId && !controller.clipPlaying && !controller.adShowing
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
                loopLineId = if (settings.loopButton) lineLoop.lineId else null,
            )
        val action = driver.tick(tick)
        driver.takeLoopMove()?.let(lineLoop::follow)
        updateSteering()
        when (action) {
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
        if (!settings.loopButton) runner.lineLoop.lineId = null
        runner.state = state
        runner.settings = settings
        runner.newWordLines = newWordLines
        runner.updateSteering()
    }
    return runner
}

/** The line the video is in at [timeMs] (a tapped line's start counts as in it), or else the next one. */
internal fun loopLineAt(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): SubtitleSegment? {
    val index = segments.indexOfLast { it.startMs - LEARNER_SEEK_EARLY_MS <= timeMs }
    return segments.getOrNull(index)?.takeIf { timeMs < it.endMs.coerceAtLeast(it.startMs + 1) } ?: segments.getOrNull(index + 1)
}

/** True when [segments] reach [timeMs]: from a little before the first line to the end of the last. */
private fun linesReach(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Boolean = segments.isNotEmpty() && timeMs in (segments.first().startMs - LOOP_DRIFT_MS)..segments.last().endMs
