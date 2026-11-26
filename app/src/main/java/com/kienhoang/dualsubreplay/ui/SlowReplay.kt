package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

/**
 * Slow replay on a second tap (25kiuo, Discussion #81, 2026-11-21: "slow it down once to hear the
 * sounds, then back to normal"). Settings → Study tools, off by default. No snail button on every
 * line, which would crowd the transcript: tapping a line replays it as always, and tapping it again
 * while that replay still plays plays it once more at the [SlowReplaySpeed] picked in Settings. The
 * learner's own speed comes back when the line has played, or at once on a tap of another line.
 *
 * SLOW_REPLAY_GRACE_MS: a second tap still counts this long after the line's (slowed) length, for a
 * finger that is a little late.
 */
private const val SLOW_REPLAY_GRACE_MS = 1_000L

/** The page brings the speed back this long after the slowed line should have ended. */
private const val SLOW_REPLAY_SAFETY_EXTRA_MS = 400L

/** Remembers the last tapped line, to tell a second tap on it while it replays. */
internal class SlowReplayTaps {
    private var lastLine: Long? = null
    private var lastAtMs = 0L

    /**
     * True when [lineId], [lineMs] long, was tapped again at [nowMs] while its last replay still plays
     * (counted at the slowest speed, the longest a replay of it can last).
     */
    fun tap(
        lineId: Long,
        lineMs: Long,
        nowMs: Long,
    ): Boolean {
        val again = lastLine == lineId && nowMs - lastAtMs in 0..slowedLineMs(lineMs, SlowReplaySpeed.HALF.rate) + SLOW_REPLAY_GRACE_MS
        lastLine = lineId
        lastAtMs = nowMs
        return again
    }
}

/** How long a line [lineMs] long plays at [rate]. */
internal fun slowedLineMs(
    lineMs: Long,
    rate: Float,
): Long = (lineMs.coerceAtLeast(0L) / rate).toLong()

/** Replays [segment] from its start; once at [speed] when [slowReplay] is on and this is a second tap on it. */
internal fun replayLine(
    segment: SubtitleSegment,
    controller: YouTubeWebController,
    taps: SlowReplayTaps,
    slowReplay: Boolean,
    speed: SlowReplaySpeed,
    nowMs: Long,
) {
    val lineMs = segment.endMs - segment.startMs
    val slow = slowReplay && taps.tap(segment.id, lineMs, nowMs)
    controller.replayFrom(segment.startMs / 1_000f)
    if (slow) {
        controller.setSlowReplay(speed.rate, slowedLineMs(lineMs, speed.rate) + SLOW_REPLAY_SAFETY_EXTRA_MS)
    } else if (slowReplay) {
        // A tap on another line (or a first tap) plays at the learner's own speed again.
        controller.setSlowReplay(null, 0L)
    }
}
