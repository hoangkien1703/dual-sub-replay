package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeSplitViewTest {

    @Test fun usesSplitWhenEnabledVisibleAndLandscape() {
        assertTrue(
            shouldUseLandscapeSplit(
                splitEnabled = true,
                subtitlePanelVisible = true,
                hasActiveVideo = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
    }

    @Test fun keepsStackedLayoutInPortrait() {
        assertFalse(
            shouldUseLandscapeSplit(
                splitEnabled = true,
                subtitlePanelVisible = true,
                hasActiveVideo = true,
                orientation = Configuration.ORIENTATION_PORTRAIT,
            ),
        )
    }

    @Test fun requiresEveryConditionToHold() {
        assertFalse(
            shouldUseLandscapeSplit(
                splitEnabled = false,
                subtitlePanelVisible = true,
                hasActiveVideo = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
        assertFalse(
            shouldUseLandscapeSplit(
                splitEnabled = true,
                subtitlePanelVisible = false,
                hasActiveVideo = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
        assertFalse(
            shouldUseLandscapeSplit(
                splitEnabled = true,
                subtitlePanelVisible = true,
                hasActiveVideo = false,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
    }

    @Test fun undefinedOrientationNeverSplits() {
        assertFalse(
            shouldUseLandscapeSplit(
                splitEnabled = true,
                subtitlePanelVisible = true,
                hasActiveVideo = true,
                orientation = Configuration.ORIENTATION_UNDEFINED,
            ),
        )
    }

    @Test fun collapseNeedsDistanceOrFastSwipe() {
        assertFalse(
            shouldCollapseSidePanel(
                dragOffsetPx = 0f,
                velocityPxPerSecond = 0f,
                minimumDistancePx = 100f,
            ),
        )
        assertTrue(
            shouldCollapseSidePanel(
                dragOffsetPx = 120f,
                velocityPxPerSecond = 0f,
                minimumDistancePx = 100f,
            ),
        )
        assertTrue(
            shouldCollapseSidePanel(
                dragOffsetPx = 40f,
                velocityPxPerSecond = SIDE_PANEL_SWIPE_VELOCITY_THRESHOLD + 1f,
                minimumDistancePx = 100f,
            ),
        )
    }

    @Test fun slowShortDragsKeepThePanelOpen() {
        assertFalse(
            shouldCollapseSidePanel(
                dragOffsetPx = 40f,
                velocityPxPerSecond = 500f,
                minimumDistancePx = 100f,
            ),
        )
    }
}
