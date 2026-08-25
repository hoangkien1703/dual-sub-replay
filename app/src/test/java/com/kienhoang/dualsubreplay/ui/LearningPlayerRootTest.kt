package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPlayerRootTest {
    @Test
    fun storedPlayerModeFallsBackToTranscriptForUnknownValues() {
        assertEquals(
            PlayerExperienceMode.TRANSCRIPT_PANEL,
            storedPlayerExperienceMode(null),
        )
        assertEquals(
            PlayerExperienceMode.TRANSCRIPT_PANEL,
            storedPlayerExperienceMode("unknown"),
        )
        assertEquals(
            PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
            storedPlayerExperienceMode("scroll_friendly_overlay"),
        )
    }

    @Test
    fun overlayUsesTheActiveDualSubtitle() {
        val state = DualSubUiState(
            activeVideoId = "dQw4w9WgXcQ",
            segments = listOf(
                SubtitleSegment(1, 1_000, 2_000, "First line", "Dòng đầu tiên"),
                SubtitleSegment(2, 2_000, 3_000, "Second line", null),
            ),
            currentIndex = 1,
        )

        assertEquals(
            LearningOverlayContent(
                originalText = "Second line",
                translatedText = "Translating…",
                statusText = null,
                segment = SubtitleSegment(2, 2_000, 3_000, "Second line", null),
            ),
            learningOverlayContent(state),
        )
    }

    @Test
    fun overlayWordHighlightFollowsTheToggle() {
        val words = listOf(
            com.kienhoang.dualsubreplay.data.SubtitleWord("Second", 2_000, 2_400),
            com.kienhoang.dualsubreplay.data.SubtitleWord("line", 2_400, 3_000),
        )
        val segment = SubtitleSegment(2, 2_000, 3_000, "Second line", null, words = words)
        val baseState = DualSubUiState(
            activeVideoId = "dQw4w9WgXcQ",
            segments = listOf(segment),
            currentIndex = 0,
            activeWordIndex = 1,
        )

        assertEquals(
            1,
            learningOverlayContent(baseState)?.activeWordIndex,
        )
        assertEquals(
            -1,
            learningOverlayContent(baseState.copy(wordHighlightEnabled = false))?.activeWordIndex,
        )
    }

    @Test
    fun overlayShowsLoadingOrErrorStatusWhenNoCaptionIsActive() {
        assertEquals(
            LearningOverlayContent(null, null, "Loading captions…"),
            learningOverlayContent(
                DualSubUiState(
                    activeVideoId = "dQw4w9WgXcQ",
                    statusMessage = "Loading captions…",
                ),
            ),
        )
        assertEquals(
            LearningOverlayContent(null, null, "Captions unavailable"),
            learningOverlayContent(
                DualSubUiState(
                    activeVideoId = "dQw4w9WgXcQ",
                    statusMessage = "Loading captions…",
                    errorMessage = "Captions unavailable",
                ),
            ),
        )
        assertNull(learningOverlayContent(DualSubUiState()))
    }

    @Test
    fun portraitOverlayDefaultsNearTheBottomAndStaysBounded() {
        assertEquals(84, portraitLearningOverlayTopPaddingDp(0))
        assertEquals(181, portraitLearningOverlayTopPaddingDp(393))
        assertEquals(320, portraitLearningOverlayTopPaddingDp(1_000))
        assertTrue(
            portraitLearningOverlayTopPaddingDp(393, 1f) >
                portraitLearningOverlayTopPaddingDp(393, 0f),
        )
    }

    @Test
    fun overlayPositionSliderMapsHigherToLargerBottomPadding() {
        assertEquals(180, overlayBottomPaddingDp(0f))
        assertEquals(42, overlayBottomPaddingDp(DEFAULT_OVERLAY_VERTICAL_POSITION))
        assertEquals(20, overlayBottomPaddingDp(1f))
        assertEquals(DEFAULT_OVERLAY_VERTICAL_POSITION, normalizeOverlayVerticalPosition(Float.NaN))
    }

    @Test
    fun automaticLandscapeOverlayOnlyOverridesTranscriptMode() {
        assertTrue(
            shouldUseAutomaticLandscapeOverlay(
                PlayerExperienceMode.TRANSCRIPT_PANEL,
                autoLandscape = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
        assertFalse(
            shouldUseAutomaticLandscapeOverlay(
                PlayerExperienceMode.TRANSCRIPT_PANEL,
                autoLandscape = true,
                orientation = Configuration.ORIENTATION_PORTRAIT,
            ),
        )
        assertFalse(
            shouldUseAutomaticLandscapeOverlay(
                PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
                autoLandscape = true,
                orientation = Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
    }

    @Test
    fun overlayDragAndPlayerControlAvoidanceStayBounded() {
        assertEquals(1f, overlayPositionAfterDrag(0.8f, 100f, 100f))
        assertEquals(0f, overlayPositionAfterDrag(0.2f, -100f, 100f))
        assertEquals(0.5f, overlayPositionAfterDrag(0.5f, Float.NaN, 100f))
        assertEquals(PLAYER_CONTROLS_AVOIDANCE_LIFT_DP, playerControlsAvoidanceLiftDp(true, true))
        assertEquals(0, playerControlsAvoidanceLiftDp(true, false))
        assertEquals(0, playerControlsAvoidanceLiftDp(false, true))
    }

    @Test
    fun horizontalOverlayPositionNormalizesAndStaysCenteredByDefault() {
        assertEquals(DEFAULT_OVERLAY_HORIZONTAL_POSITION, normalizeOverlayHorizontalPosition(Float.NaN))
        assertEquals(0f, normalizeOverlayHorizontalPosition(-3f))
        assertEquals(1f, normalizeOverlayHorizontalPosition(7f))
        assertEquals(-1f, overlayHorizontalShiftFraction(0f))
        assertEquals(0f, overlayHorizontalShiftFraction(0.5f))
        assertEquals(1f, overlayHorizontalShiftFraction(1f))
    }

    @Test
    fun horizontalOverlayShiftKeepsTheBoxFullyOnScreen() {
        // Degenerate sizes never shift the box.
        assertEquals(0, overlayHorizontalShiftPx(1f, 0, 0))
        assertEquals(0, overlayHorizontalShiftPx(1f, 400, 400))
        val halfFreeSpace = (800 - 300) / 2
        assertEquals(halfFreeSpace, overlayHorizontalShiftPx(1f, 800, 300))
        assertEquals(-halfFreeSpace, overlayHorizontalShiftPx(-1f, 800, 300))
        assertEquals(0, overlayHorizontalShiftPx(0f, 800, 300))
        // Out-of-range fractions are clamped instead of pushing the box off screen.
        assertEquals(halfFreeSpace, overlayHorizontalShiftPx(9f, 800, 300))
    }

    @Test
    fun fullscreenLandscapeStartsNearTheTopButKeepsDraggedPositions() {
        assertEquals(
            FULLSCREEN_LANDSCAPE_DEFAULT_OVERLAY_VERTICAL_POSITION,
            fullscreenOverlayVerticalPosition(
                DEFAULT_OVERLAY_VERTICAL_POSITION,
                Configuration.ORIENTATION_LANDSCAPE,
            ),
            0f,
        )
        assertEquals(
            DEFAULT_OVERLAY_VERTICAL_POSITION,
            fullscreenOverlayVerticalPosition(
                DEFAULT_OVERLAY_VERTICAL_POSITION,
                Configuration.ORIENTATION_PORTRAIT,
            ),
            0f,
        )
        assertEquals(
            0.37f,
            fullscreenOverlayVerticalPosition(0.37f, Configuration.ORIENTATION_LANDSCAPE),
            0f,
        )
        assertTrue(
            FULLSCREEN_LANDSCAPE_DEFAULT_OVERLAY_VERTICAL_POSITION <
                DEFAULT_OVERLAY_VERTICAL_POSITION,
        )
    }

    @Test
    fun fullscreenOverlayCanTravelFromTopToBottom() {
        val screenHeight = 800
        val top = fullscreenOverlayBottomPaddingDp(0f, screenHeight)
        val bottom = fullscreenOverlayBottomPaddingDp(1f, screenHeight)
        assertTrue("position 0 should reach the top", top >= screenHeight - FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP - 60)
        assertEquals(0, bottom)
        assertTrue(
            fullscreenOverlayBottomPaddingDp(0.4f, screenHeight) >
                fullscreenOverlayBottomPaddingDp(0.6f, screenHeight),
        )
        // Player-control avoidance lifts the box but clamps at the top edge.
        assertTrue(fullscreenOverlayBottomPaddingDp(1f, screenHeight, PLAYER_CONTROLS_AVOIDANCE_LIFT_DP) >= 0)
        assertEquals(
            fullscreenOverlayDragTravelDp(screenHeight),
            fullscreenOverlayBottomPaddingWithControlsDp(
                position = 0f,
                screenHeightDp = screenHeight,
                controlsLiftDp = PLAYER_CONTROLS_AVOIDANCE_LIFT_DP,
            ),
        )
    }

    @Test
    fun fullscreenDragTravelMatchesVisibleVerticalTravel() {
        assertEquals(712, fullscreenOverlayDragTravelDp(800))
        assertEquals(152, fullscreenOverlayDragTravelDp(240))
    }

    @Test
    fun captionSuppressionIsOriginCheckedAndReversible() {
        val hidden = webCaptionVisibilityScript(hidden = true)
        val restored = webCaptionVisibilityScript(hidden = false)

        assertTrue(hidden.contains("window.location.protocol !== 'https:'"))
        assertTrue(hidden.contains("host.endsWith('.youtube.com')"))
        assertTrue(hidden.contains(YOUTUBE_CAPTION_STYLE_ID))
        assertTrue(hidden.contains("visibility: hidden !important"))
        assertTrue(restored.contains("existing.remove()"))
    }
}
