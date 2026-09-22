package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitPanelPositionTest {
    @Test fun storedPositionDefaultsRoundsAndClamps() {
        assertEquals(0.01f, normalizePortraitPanelOffsetFraction(Float.NaN), 0f)
        assertEquals(0.01f, normalizePortraitPanelOffsetFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(-0.2f, normalizePortraitPanelOffsetFraction(-0.8f), 0f)
        assertEquals(0.2f, normalizePortraitPanelOffsetFraction(0.8f), 0f)
        assertEquals(0.13f, normalizePortraitPanelOffsetFraction(0.126f), 0f)
    }

    @Test fun defaultGeometryIsOnePercentLowerThanBottomAlignedBaseline() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                baselineHeightPx = 600f,
                offsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(410f, geometry.topPx, 0f)
        assertEquals(590f, geometry.heightPx, 0f)
    }

    @Test fun movingUpPreservesHeightAndLeavesBottomGap() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                baselineHeightPx = 600f,
                offsetFraction = -0.2f,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(200f, geometry.topPx, 0f)
        assertEquals(600f, geometry.heightPx, 0f)
        assertEquals(800f, geometry.topPx + geometry.heightPx, 0f)
    }

    @Test fun movingDownShortensPanelAtSafeBottom() {
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = 1_000f,
                baselineHeightPx = 600f,
                offsetFraction = 0.2f,
                minimumPanelHeightPx = 192f,
            )

        assertEquals(600f, geometry.topPx, 0f)
        assertEquals(400f, geometry.heightPx, 0f)
        assertEquals(1_000f, geometry.topPx + geometry.heightPx, 0f)
    }

    @Test fun geometryClampsBothEdgesAndKeepsMinimumViewport() {
        val topClamped = portraitPanelGeometry(1_000f, 900f, -0.2f, 192f)
        val bottomClamped = portraitPanelGeometry(300f, 180f, 0.2f, 192f)

        assertEquals(0f, topClamped.topPx, 0f)
        assertEquals(900f, topClamped.heightPx, 0f)
        assertEquals(108f, bottomClamped.topPx, 0f)
        assertEquals(192f, bottomClamped.heightPx, 0f)
    }

    @Test fun tinyAndInvalidContainersNeverProduceNegativeGeometry() {
        val tiny = portraitPanelGeometry(160f, 96f, 0.2f, 192f)
        val invalid = portraitPanelGeometry(Float.NaN, Float.NaN, Float.NaN, Float.NaN)

        assertEquals(PortraitPanelGeometry(0f, 160f), tiny)
        assertEquals(PortraitPanelGeometry(0f, 0f), invalid)
    }

    @Test fun positionLabelExplainsDirection() {
        assertEquals("Position: Center", portraitPanelPositionLabel(0f))
        assertEquals("Position: 1% lower", portraitPanelPositionLabel(DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION))
        assertEquals("Position: 8% higher", portraitPanelPositionLabel(-0.08f))
        assertEquals("Position: 20% lower", portraitPanelPositionLabel(0.2f))
    }
}
