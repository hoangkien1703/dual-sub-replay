package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontScaleTest {
    @Test
    fun textSizeGoesUpTo200Percent() {
        assertEquals(2f, MAX_FONT_SCALE)
        assertEquals(1.75f, normalizeFontScale(1.75f))
        assertEquals(MAX_FONT_SCALE, normalizeFontScale(3f))
        assertEquals(MIN_FONT_SCALE, normalizeFontScale(0.5f))
        assertEquals(1f, normalizeFontScale(Float.NaN))
    }

    @Test
    fun fullscreenEstimateGrowsWithLargeTextOnly() {
        assertEquals(FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP, fullscreenOverlayEstimatedHeightDp(1f))
        assertEquals(FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP, fullscreenOverlayEstimatedHeightDp(MIN_FONT_SCALE))
        assertEquals(FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP * 2, fullscreenOverlayEstimatedHeightDp(MAX_FONT_SCALE))
        // A stored value from outside the range cannot grow the estimate further.
        assertEquals(fullscreenOverlayEstimatedHeightDp(MAX_FONT_SCALE), fullscreenOverlayEstimatedHeightDp(5f))
    }

    @Test
    fun fullscreenOverlayStaysOnScreenAt200PercentOnALandscapePhone() {
        val screenHeight = 360
        // One original line (21sp) and one translated line (18sp) plus 8dp vertical padding and the 2dp gap.
        val singleLineBoxHeight = (16 + 2 + (21 + 18) * MAX_FONT_SCALE).toInt()
        assertTrue(fullscreenOverlayEstimatedHeightDp(MAX_FONT_SCALE) >= singleLineBoxHeight)

        val top = fullscreenOverlayBottomPaddingDp(0f, screenHeight, fontScale = MAX_FONT_SCALE)
        assertEquals(screenHeight - fullscreenOverlayEstimatedHeightDp(MAX_FONT_SCALE), top)
        assertEquals(0, fullscreenOverlayBottomPaddingDp(1f, screenHeight, fontScale = MAX_FONT_SCALE))
        assertEquals(
            fullscreenOverlayDragTravelDp(screenHeight, MAX_FONT_SCALE),
            fullscreenOverlayBottomPaddingWithControlsDp(
                position = 0f,
                screenHeightDp = screenHeight,
                controlsLiftDp = PLAYER_CONTROLS_AVOIDANCE_LIFT_DP,
                fontScale = MAX_FONT_SCALE,
            ),
        )
    }
}
