package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotGateTest {
    @Test fun callbacksCannotRewindStateAfterNewerSampleOrNavigation() {
        val gate = PlaybackSnapshotGate()
        val old = gate.request()
        val latest = gate.request()
        assertTrue(gate.accept(latest, "a", "a", "a"))
        assertFalse(gate.accept(old, "a", "a", "a"))
        assertFalse(gate.accept(gate.request(), "a", "b", "a"))
        gate.close()
        assertFalse(gate.accept(gate.request(), "b", "b", "b"))
    }
}
