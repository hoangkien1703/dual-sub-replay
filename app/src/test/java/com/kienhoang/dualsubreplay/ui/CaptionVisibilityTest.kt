package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.*
import org.junit.Test

class CaptionVisibilityTest {
    @Test fun allNineCombinationsShareOverlayPolicyWhilePlayingAndPaused() {
        for (original in CaptionVisibility.entries) {
            for (translation in CaptionVisibility.entries) {
                for (paused in listOf(false, true)) {
                    val state =
                        DualSubUiState(
                            activeVideoId = "dQw4w9WgXcQ",
                            currentIndex = 0,
                            segments = listOf(SubtitleSegment(0, 0, 1000, "original", "translation")),
                            originalVisibility = original,
                            translatedVisibility = translation,
                            playbackPaused = paused,
                        )
                    val overlay = learningOverlayContent(state)!!
                    assertEquals(original.visible(paused), overlay.originalText != null)
                    assertEquals(translation.visible(paused), overlay.translatedText != null)
                    assertEquals(state.showOriginal(), overlay.originalText != null)
                    assertEquals(state.showTranslation(), overlay.translatedText != null)
                }
            }
        }
    }

    @Test fun missingAndUnknownPreferencesPreserveBothTracks() {
        assertEquals(CaptionVisibility.ALWAYS, storedCaptionVisibility(null))
        assertEquals(CaptionVisibility.ALWAYS, storedCaptionVisibility("future"))
    }

    @Test fun bufferingIsNotPausedAndOldSnapshotsDefaultToPlaying() {
        val snapshot =
            parseWebPlaybackSnapshot(
                org.json.JSONObject.quote("{\"url\":\"https://m.youtube.com\",\"currentSecond\":1,\"controlsVisible\":true}"),
            )!!
        assertFalse(snapshot.paused)
        assertFalse(CaptionVisibility.PAUSED.visible(snapshot.paused))
    }
}
