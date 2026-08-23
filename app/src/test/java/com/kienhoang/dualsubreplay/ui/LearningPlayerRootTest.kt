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
            ),
            learningOverlayContent(state),
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
