package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun everyTrackIsItsOwnChoiceAndTheLoadedOneIsSelected() {
        val tracks =
            listOf(
                CaptionLanguage("ja", "日本語 (自動生成)", "a.ja", generated = true),
                CaptionLanguage("ja", "日本語", ".ja"),
            )
        val choices = sourceLanguageChoices(tracks, "Auto (recommended)")

        assertEquals(listOf("auto", "track:a.ja", "track:.ja"), choices.map { it.code })
        assertEquals(tracks[0], pickedCaptionTrack("track:a.ja", tracks))
        assertNull(pickedCaptionTrack("track:.en", tracks))
        assertNull(pickedCaptionTrack("ja", tracks))

        val loaded = DualSubUiState(sourcePreference = "ja", availableSourceLanguages = tracks, loadedTrackId = "a.ja")
        assertEquals("track:a.ja", sourceChoiceSelection(loaded))
        assertEquals("日本語 (自動生成)", sourceLanguageLabel(sourceChoiceSelection(loaded), choices, "Auto", java.util.Locale.JAPANESE))
        assertEquals("auto", sourceChoiceSelection(loaded.copy(sourcePreference = "auto")))
        // While the next transcript loads, the chosen language stands in.
        assertEquals("ja", sourceChoiceSelection(loaded.copy(availableSourceLanguages = emptyList())))
    }

    @Test fun theAiTranscriptIsTheLastChoiceAndShowsAsChosenWhileLoaded() {
        val tracks = listOf(CaptionLanguage("en", "English (auto-generated)", "a.en", generated = true))
        val choices = sourceLanguageChoices(tracks, "Auto (recommended)", "AI transcript (Gemini)")
        assertEquals(listOf("auto", "track:a.en", AI_TRANSCRIPT_CHOICE), choices.map { it.code })
        assertEquals(listOf("auto", "track:a.en"), sourceLanguageChoices(tracks, "Auto").map { it.code })

        val aiLoaded = DualSubUiState(sourcePreference = "auto", availableSourceLanguages = tracks, loadedTrackId = "ai")
        assertEquals(AI_TRANSCRIPT_CHOICE, sourceChoiceSelection(aiLoaded))
        assertEquals(
            "AI transcript (Gemini)",
            sourceLanguageLabel(sourceChoiceSelection(aiLoaded), choices, "Auto", java.util.Locale.ENGLISH),
        )
        // The AI choice is not one of YouTube's tracks, so the assistant's setting change cannot pick it.
        assertNull(pickedCaptionTrack(AI_TRANSCRIPT_CHOICE, tracks))
    }

    @Test fun aSongOffersItsFreeLyricsBeforeTheAiTranscriptEachShownAsChosenWhileLoaded() {
        // Owner pick 2026-11-25: free lyrics and the AI transcript are separate choices, so each can be picked back.
        val tracks = listOf(CaptionLanguage("en", "English (auto-generated)", "a.en", generated = true))
        val choices = sourceLanguageChoices(tracks, "Auto", "AI transcript (Gemini)", "Song lyrics (LRCLIB)")
        assertEquals(listOf("auto", "track:a.en", SONG_LYRICS_CHOICE, AI_TRANSCRIPT_CHOICE), choices.map { it.code })

        val lyricsLoaded = DualSubUiState(sourcePreference = "auto", availableSourceLanguages = tracks, loadedTrackId = "lyrics")
        assertEquals(SONG_LYRICS_CHOICE, sourceChoiceSelection(lyricsLoaded))
        assertEquals("Song lyrics (LRCLIB)", sourceLanguageLabel(SONG_LYRICS_CHOICE, choices, "Auto", java.util.Locale.ENGLISH))
        assertEquals(AI_TRANSCRIPT_CHOICE, sourceChoiceSelection(lyricsLoaded.copy(loadedTrackId = "ai")))
        assertNull(pickedCaptionTrack(SONG_LYRICS_CHOICE, tracks))
    }

    private fun availableLanguages() =
        listOf(
            CaptionLanguage("en", "English"),
            CaptionLanguage("es", "Spanish"),
        )
}
