package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragRegressionTest {
    @Test
    fun horizontalOverlayDragTracksFingerOneToOne() {
        val parentWidthPx = 1_000
        val overlayWidthPx = 400
        val fingerDeltaPx = 60f
        val travelPx = (parentWidthPx - overlayWidthPx).toFloat()
        val startPosition = 0.5f

        val newPosition = normalizeOverlayHorizontalPosition(
            startPosition + fingerDeltaPx / travelPx,
        )
        val startShiftPx = overlayHorizontalShiftPx(
            overlayHorizontalShiftFraction(startPosition),
            parentWidthPx,
            overlayWidthPx,
        )
        val endShiftPx = overlayHorizontalShiftPx(
            overlayHorizontalShiftFraction(newPosition),
            parentWidthPx,
            overlayWidthPx,
        )

        assertEquals(fingerDeltaPx.toInt(), endShiftPx - startShiftPx)
    }
}
