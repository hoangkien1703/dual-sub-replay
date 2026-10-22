package com.kienhoang.dualsubreplay.ui

/**
 * How often the YouTube page is asked for its playback snapshot, and how often the karaoke clock
 * ticks, depending on what the page shows. A playing video keeps the 100 ms reads the word
 * highlight needs; a paused video, a watch page whose video has not started and pages without a
 * video are read less often, because nothing on them moves (owner request 2026-10-22, see
 * docs/specs/2026-10-22-lighter-cpu-use.md).
 */
internal const val PLAYBACK_POLL_PLAYING_MS = 100L
internal const val PLAYBACK_POLL_PAUSED_MS = 250L
internal const val PLAYBACK_POLL_NO_VIDEO_MS = 500L

/** The karaoke clock moves the highlighted word between snapshots, at about 30 frames a second. */
internal const val PLAYBACK_TICK_PLAYING_MS = 33L

/** Paused, the position does not move; the clock only needs to notice when playing starts again. */
internal const val PLAYBACK_TICK_IDLE_MS = 100L

internal fun playbackPollIntervalMs(
    watchPage: Boolean,
    latest: WebPlaybackSnapshot?,
): Long =
    when {
        !watchPage -> PLAYBACK_POLL_NO_VIDEO_MS
        latest.isPlaying() -> PLAYBACK_POLL_PLAYING_MS
        else -> PLAYBACK_POLL_PAUSED_MS
    }

internal fun playbackTickIntervalMs(latest: WebPlaybackSnapshot?): Long =
    if (latest.isPlaying()) PLAYBACK_TICK_PLAYING_MS else PLAYBACK_TICK_IDLE_MS

/** Buffering counts as playing: the video goes on by itself once data arrives. */
private fun WebPlaybackSnapshot?.isPlaying(): Boolean = this != null && currentSecond != null && !paused
