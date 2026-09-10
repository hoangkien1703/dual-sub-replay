package com.kienhoang.dualsubreplay.ui

internal const val PLAYBACK_CLOCK_STALE_MS = 250L

/** One media clock for all caption surfaces. Android monotonic time is injected for tests. */
internal class CaptionPlaybackClock {
    private var snapshot: WebPlaybackSnapshot? = null
    private var sampledAt = 0L
    private var lastWallSample = 0L
    private var positionMs = 0L
    private val retiredSessions = ArrayDeque<String>()

    fun accept(
        sample: WebPlaybackSnapshot,
        requestedAt: Long,
        receivedAt: Long,
        receivedWallMs: Long,
    ): Boolean {
        val second = sample.currentSecond ?: return false
        val age = receivedWallMs - sample.sampledAtEpochMs
        if (!second.isFinite() || second < 0 || sample.sessionId.isBlank() ||
            !sample.playbackRate.isFinite() || sample.playbackRate !in 0.0..16.0 ||
            age !in 0..PLAYBACK_CLOCK_STALE_MS || receivedAt - requestedAt !in 0..500 ||
            sample.sampledAtEpochMs < lastWallSample || sample.sessionId in retiredSessions
        ) {
            return false
        }
        val previous = snapshot
        if (previous != null && previous.sessionId != sample.sessionId) {
            retiredSessions.addLast(previous.sessionId)
            if (retiredSessions.size > 64) retiredSessions.removeFirst()
        }
        snapshot = sample
        lastWallSample = sample.sampledAtEpochMs
        sampledAt = receivedAt - age
        positionMs = (second * 1000).toLong()
        return true
    }

    fun position(now: Long): Long? {
        val sample = snapshot ?: return null
        val age = now - sampledAt
        // Do not march through words when the renderer stops reporting playback.
        if (age !in 0..PLAYBACK_CLOCK_STALE_MS || sample.seeking) return null
        val advance = if (sample.paused || sample.buffering) 0 else (age * sample.playbackRate).toLong()
        return positionMs + advance
    }

    fun sample(): WebPlaybackSnapshot? = snapshot
}
