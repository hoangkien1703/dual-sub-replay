package com.kienhoang.dualsubreplay.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The sleep timer's choices in the gear popup: minutes, or the end of the playing video. */
internal sealed interface SleepTimerChoice {
    data class Minutes(
        val minutes: Int,
    ) : SleepTimerChoice

    data object EndOfVideo : SleepTimerChoice
}

internal val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60)

/** A running sleep timer: what was picked and, for minutes, when it ends (on [SleepTimerRunner]'s clock). */
internal data class SleepTimerRun(
    val choice: SleepTimerChoice,
    val endsAtMs: Long? = null,
)

private const val SLEEP_TIMER_CHECK_MS = 30_000L

/** Within this much of the video's end, End of video counts as reached. */
private const val END_OF_VIDEO_MARGIN_MS = 1_000L

/**
 * The sleep timer (25kiuo, Discussion #81, 2026-11-15): pauses the page video when the time is up,
 * or at the end of the playing video (also when the next video starts). It works in the background
 * and with the screen off, since the playback service keeps the app running while a video plays.
 * It is not saved: it ends with the app.
 */
internal class SleepTimerRunner(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val playback: StateFlow<WebVideoPlayback?>,
    private val pause: () -> Unit,
) {
    private val mutableRun = MutableStateFlow<SleepTimerRun?>(null)
    val run: StateFlow<SleepTimerRun?> = mutableRun.asStateFlow()
    private var job: Job? = null

    /** Starts [choice], replacing a running timer; null turns the timer off. */
    fun set(choice: SleepTimerChoice?) {
        job?.cancel()
        job = null
        mutableRun.value = null
        when (choice) {
            null -> Unit
            is SleepTimerChoice.Minutes -> {
                val endsAt = now() + choice.minutes * 60_000L
                mutableRun.value = SleepTimerRun(choice, endsAt)
                job =
                    scope.launch {
                        // Checked against the clock that counts deep sleep, so a phone that slept still ends on time.
                        while (now() < endsAt) delay((endsAt - now()).coerceAtMost(SLEEP_TIMER_CHECK_MS))
                        fire()
                    }
            }
            SleepTimerChoice.EndOfVideo -> {
                mutableRun.value = SleepTimerRun(choice)
                val videoId = playback.value?.videoId
                job = scope.launch { playback.first { reachedEnd(videoId, it) }.also { fire() } }
            }
        }
    }

    /** Minutes left, rounded up, for the popup; null when no minutes timer runs. */
    fun minutesLeft(): Int? {
        val endsAt = mutableRun.value?.endsAtMs ?: return null
        return ((endsAt - now()).coerceAtLeast(0) + 59_999).div(60_000).toInt()
    }

    private fun fire() {
        mutableRun.value = null
        job = null
        pause()
    }
}

/** End of video is reached near the end of [videoId], or once another video plays. */
internal fun reachedEnd(
    videoId: String?,
    playback: WebVideoPlayback?,
): Boolean {
    if (playback == null) return false
    if (videoId != null && playback.videoId != videoId) return true
    val duration = playback.durationMs ?: return false
    return duration > 0 && playback.positionMs >= duration - END_OF_VIDEO_MARGIN_MS
}

/** The app's one sleep timer, pausing the page video through [WebVideoBridge]. */
internal object SleepTimer {
    private val runner =
        SleepTimerRunner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            now = android.os.SystemClock::elapsedRealtime,
            playback = WebVideoBridge.playback,
            pause = { WebVideoBridge.send(WebVideoCommand.Pause) },
        )

    val run: StateFlow<SleepTimerRun?> get() = runner.run

    fun set(choice: SleepTimerChoice?) = runner.set(choice)

    fun minutesLeft(): Int? = runner.minutesLeft()
}
