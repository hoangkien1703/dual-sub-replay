package com.kienhoang.dualsubreplay.ui

/** Reject callbacks from old pages, stopped effects, and superseded requests. */
internal class PlaybackSnapshotGate {
    private var next = 0L
    private var accepted = 0L
    private var closed = false

    fun request(): Long = ++next

    fun close() {
        closed = true
    }

    fun accept(
        ticket: Long,
        requestedUrl: String?,
        currentUrl: String?,
        snapshotUrl: String,
    ): Boolean {
        if (closed || ticket <= accepted || requestedUrl != currentUrl || snapshotUrl != currentUrl) return false
        accepted = ticket
        return true
    }
}
