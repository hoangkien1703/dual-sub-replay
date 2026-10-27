package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitPanelPositionTest {
    @Test fun storedPositionDefaultsRoundsAndClamps() {
        assertEquals(0f, normalizePortraitPanelOffsetFraction(Float.NaN), 0f)
        assertEquals(0f, normalizePortraitPanelOffsetFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-0.2f, normalizePortraitPanelOffsetFraction(-0.8f), 0f)
        assertEquals(0.2f, normalizePortraitPanelOffsetFraction(0.8f), 0f)
        assertEquals(0.13f, normalizePortraitPanelOffsetFraction(0.126f), 0f)
    }

    @Test fun defaultPanelStartsOnTheVideoBottomAndReachesTheScreenBottom() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                fittedTopPx = 400f,
                offsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(400f, geometry.topPx, 0f)
        assertEquals(600f, geometry.heightPx, 0f)
    }

    @Test fun movingUpMakesThePanelTallerWithItsBottomInPlace() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                fittedTopPx = 400f,
                offsetFraction = -0.1f,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(300f, geometry.topPx, 0f)
        assertEquals(700f, geometry.heightPx, 0f)
        assertEquals(1_000f, geometry.topPx + geometry.heightPx, 0f)
    }

    @Test fun movingDownMakesThePanelShorterWithItsBottomInPlace() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                fittedTopPx = 400f,
                offsetFraction = 0.2f,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(600f, geometry.topPx, 0f)
        assertEquals(400f, geometry.heightPx, 0f)
        assertEquals(1_000f, geometry.topPx + geometry.heightPx, 0f)
    }

    @Test fun geometryClampsBothEdgesAndKeepsMinimumViewport() {
        val topClamped = portraitPanelGeometry(1_000f, 100f, -0.2f, 192f)
        val bottomClamped = portraitPanelGeometry(1_000f, 900f, 0.2f, 192f)

        assertEquals(0f, topClamped.topPx, 0f)
        assertEquals(1_000f, topClamped.heightPx, 0f)
        assertEquals(808f, bottomClamped.topPx, 0f)
        assertEquals(192f, bottomClamped.heightPx, 0f)
    }

    @Test fun tinyAndInvalidContainersNeverProduceNegativeGeometry() {
        val tiny = portraitPanelGeometry(160f, 96f, 0.2f, 192f)
        val invalid = portraitPanelGeometry(Float.NaN, Float.NaN, Float.NaN, Float.NaN)

        assertEquals(PortraitPanelGeometry(0f, 160f), tiny)
        assertEquals(PortraitPanelGeometry(0f, 0f), invalid)
    }

    @Test fun fittedTopUsesThePlayerThePageReported() {
        // A 1,080 x 2,000 px area whose page is 360 x 666.7 CSS px, with the player ending at 44%.
        val frame = PagePlayerFrame(bottomFraction = 0.44f, viewportAspect = 0.54f)

        assertEquals(880f, fittedPortraitPanelTopPx(1_080f, 2_000f, frame, 144f), 0.01f)
    }

    @Test fun fittedTopEstimatesA16By9PlayerUntilThePageReportsOne() {
        // Phone: 48 dp header (144 px) plus 1,080 * 9 / 16.
        assertEquals(751.5f, fittedPortraitPanelTopPx(1_080f, 2_000f, null, 144f), 0.01f)
        // Tablet in portrait: 1,600 x 2,400 px at 2x.
        assertEquals(996f, fittedPortraitPanelTopPx(1_600f, 2_400f, null, 96f), 0.01f)
    }

    @Test fun fittedTopIgnoresAPlayerMeasuredInAnotherScreenShape() {
        // Read while the phone was sideways; the portrait estimate is used until the next reading.
        val landscape = PagePlayerFrame(bottomFraction = 1f, viewportAspect = 2.2f)

        assertEquals(751.5f, fittedPortraitPanelTopPx(1_080f, 2_000f, landscape, 144f), 0.01f)
    }

    @Test fun positionLabelExplainsDirection() {
        assertEquals(
            PortraitPanelPositionText(R.string.settings_portrait_panel_position_center, 0),
            portraitPanelPositionText(0f),
        )
        assertEquals(
            PortraitPanelPositionText(R.string.settings_portrait_panel_position_center, 0),
            portraitPanelPositionText(DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION),
        )
        assertEquals(
            PortraitPanelPositionText(R.string.settings_portrait_panel_position_higher_percent, 8),
            portraitPanelPositionText(-0.08f),
        )
        assertEquals(
            PortraitPanelPositionText(R.string.settings_portrait_panel_position_lower_percent, 20),
            portraitPanelPositionText(0.2f),
        )
    }
}
