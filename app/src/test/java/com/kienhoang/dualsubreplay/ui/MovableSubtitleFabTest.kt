package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovableSubtitleFabTest {
    @Test fun positionsNormalizeAndRejectInvalidValues() {
        assertEquals(0f, normalizeControlPosition(-3f), 0f)
        assertEquals(1f, normalizeControlPosition(3f), 0f)
        assertEquals(0.4f, normalizeControlPosition(0.4f), 0f)
        assertEquals(1f, normalizeControlPosition(Float.NaN), 0f)
    }

    @Test fun defaultCcStaysHigherWithoutOccupyingFullscreenTopControls() {
        assertEquals(0.72f, DEFAULT_COLLAPSED_CC_VERTICAL_POSITION, 0f)
        assertEquals(
            FULLSCREEN_LANDSCAPE_DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
            collapsedCcVerticalPositionForContext(
                DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                isFullscreen = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
            0f,
        )
        assertEquals(
            DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
            collapsedCcVerticalPositionForContext(
                DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                isFullscreen = false,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
            0f,
        )
        assertEquals(
            0.44f,
            collapsedCcVerticalPositionForContext(
                0.44f,
                isFullscreen = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
            0f,
        )
        assertTrue(
            FULLSCREEN_LANDSCAPE_DEFAULT_COLLAPSED_CC_VERTICAL_POSITION <
                DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
        )
        assertTrue(
            "fullscreen CC default must stay below YouTube's top-right controls",
            FULLSCREEN_LANDSCAPE_DEFAULT_COLLAPSED_CC_VERTICAL_POSITION >= 0.40f,
        )
    }

    @Test fun offsetKeepsTheWholeControlInsideMargins() {
        assertEquals(16, controlOffsetPx(0f, 400, 40, 16))
        assertEquals(344, controlOffsetPx(1f, 400, 40, 16))
        assertEquals(180, controlOffsetPx(0.5f, 400, 40, 16))
        assertEquals(16, controlOffsetPx(1f, 20, 40, 16))
    }

    @Test fun pixelOffsetRoundTripsToNormalizedPosition() {
        assertEquals(0f, controlPositionFromOffsetPx(16f, 400, 40, 16), 0f)
        assertEquals(0.5f, controlPositionFromOffsetPx(180f, 400, 40, 16), 0.0001f)
        assertEquals(1f, controlPositionFromOffsetPx(344f, 400, 40, 16), 0f)
        assertEquals(0f, controlPositionFromOffsetPx(Float.NaN, 400, 40, 16), 0f)
    }

    @Test fun portraitPanelStartsNearEstimatedVideoBottom() {
        val fraction = portraitSubtitlePanelHeightFraction(400, 900)
        assertEquals(0.6805f, fraction, 0.01f)
        assertEquals(0.60f, portraitSubtitlePanelHeightFraction(800, 600), 0f)
    }
}
