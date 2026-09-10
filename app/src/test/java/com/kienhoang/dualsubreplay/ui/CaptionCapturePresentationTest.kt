package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCapturePresentationTest {
    private val state = DualSubUiState(generatedCaptions = true, wordHighlightEnabled = true)

    @Test
    fun captureSurvivesThePanelToOverlayTransition() {
        val panel = PlayerExperienceMode.TRANSCRIPT_PANEL
        val overlay = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
        assertTrue(shouldCaptureCaptionsForPresentation(state, panel))
        assertTrue(shouldCaptureCaptionsForPresentation(state, overlay))
        assertTrue(shouldCaptureCaptionsForPresentation(state.copy(subtitlePanelVisible = false), overlay))
        assertTrue(shouldCaptureCaptionsForPresentation(state, panel))
    }

    @Test
    fun hidingThePanelWithoutAnOverlayStillStopsCapture() {
        assertFalse(
            shouldCaptureCaptionsForPresentation(
                state.copy(subtitlePanelVisible = false),
                PlayerExperienceMode.TRANSCRIPT_PANEL,
            ),
        )
    }

    @Test
    fun overlayRespectsTheHighlightSwitchAndManualCaptionTiming() {
        val overlay = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
        assertFalse(shouldCaptureCaptionsForPresentation(state.copy(wordHighlightEnabled = false), overlay))
        assertFalse(shouldCaptureCaptionsForPresentation(state.copy(generatedCaptions = false), overlay))
    }

    @Test
    fun liveTranslationRecoveryKeepsCapturingInAnOverlayWithHighlightingOff() {
        assertTrue(
            shouldCaptureCaptionsForPresentation(
                state.copy(subtitlePanelVisible = false, liveFallback = true, wordHighlightEnabled = false),
                PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
            ),
        )
    }
}
