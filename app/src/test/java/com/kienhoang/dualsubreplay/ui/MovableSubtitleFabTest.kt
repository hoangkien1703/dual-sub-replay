package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MovableSubtitleFabTest {
    @Test fun positionsNormalizeAndRejectInvalidValues() {
        assertEquals(0f, normalizeControlPosition(-3f), 0f)
        assertEquals(1f, normalizeControlPosition(3f), 0f)
        assertEquals(0.4f, normalizeControlPosition(0.4f), 0f)
        assertEquals(1f, normalizeControlPosition(Float.NaN), 0f)
    }

    @Test fun dragUsesAvailableTravelAndClampsAtEdges() {
        assertEquals(0.75f, controlPositionAfterDrag(0.5f, 25f, 100f), 0.0001f)
        assertEquals(1f, controlPositionAfterDrag(0.9f, 50f, 100f), 0f)
        assertEquals(0f, controlPositionAfterDrag(0.1f, -50f, 100f), 0f)
        assertEquals(0.5f, controlPositionAfterDrag(0.5f, 20f, 0f), 0f)
    }

    @Test fun offsetKeepsTheWholeControlInsideMargins() {
        assertEquals(16, controlOffsetPx(0f, 400, 40, 16))
        assertEquals(344, controlOffsetPx(1f, 400, 40, 16))
        assertEquals(180, controlOffsetPx(0.5f, 400, 40, 16))
        assertEquals(16, controlOffsetPx(1f, 20, 40, 16))
    }
}
