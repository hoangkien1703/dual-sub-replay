package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for issue #20: changing the subtitle language in the
 * middle of a video must always take effect instead of being silently dropped.
 */
class SourcePreferenceTest {

    @Test fun acceptsAutoAlways() {
        assertTrue(shouldAcceptSourcePreference("auto", emptyList()))
        assertTrue(shouldAcceptSourcePreference("auto", availableLanguages()))
    }

    @Test fun acceptsKnownLanguageWhileTrackListIsLoaded() {
        assertTrue(shouldAcceptSourcePreference("es", availableLanguages()))
    }

    @Test fun rejectsLanguageMissingFromLoadedTrackList() {
        assertFalse(shouldAcceptSourcePreference("ja", availableLanguages()))
    }

    @Test fun acceptsAnyValidLanguageWhileLoadIsStillInFlight() {
        // Mid-video language changes happen while captions are loading or the
        // previous load failed; the track list is unknown, never block the pick.
        assertTrue(shouldAcceptSourcePreference("fr", emptyList()))
    }

    @Test fun normalizesRegionCodesWhenMatching() {
        assertTrue(shouldAcceptSourcePreference("pt-BR", listOf(CaptionLanguage("pt", "Portuguese"))))
        assertTrue(shouldAcceptSourcePreference("PT", listOf(CaptionLanguage("pt-BR", "Portuguese"))))
    }

    @Test fun playbackClockOnlyResetsForDifferentVideos() {
        assertTrue(shouldResetPlaybackClock(previousVideoId = null, newVideoId = "abc123"))
        assertTrue(shouldResetPlaybackClock(previousVideoId = "old", newVideoId = "new"))
        assertFalse(shouldResetPlaybackClock(previousVideoId = "same", newVideoId = "same"))
    }

    private fun availableLanguages() = listOf(
        CaptionLanguage("en", "English"),
        CaptionLanguage("es", "Spanish"),
    )
}
