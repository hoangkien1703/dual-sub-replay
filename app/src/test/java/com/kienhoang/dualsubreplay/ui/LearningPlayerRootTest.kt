package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun portraitOverlayPositionScalesWithTypicalPhoneWidthsAndStaysBounded() {
        assertEquals(104, portraitLearningOverlayTopPaddingDp(0))
        assertEquals(165, portraitLearningOverlayTopPaddingDp(393))
        assertEquals(280, portraitLearningOverlayTopPaddingDp(1_000))
    }
}
