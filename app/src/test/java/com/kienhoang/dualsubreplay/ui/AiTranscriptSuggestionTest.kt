package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.playback.WebVideoPlayback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the panel offers "Make AI transcript" for a song (owner pick 2026-11-17). */
class AiTranscriptSuggestionTest {
    private fun video(
        title: String,
        channel: String = "",
        music: Boolean = false,
    ) = WebVideoPlayback(
        "dQw4w9WgXcQ",
        title,
        paused = false,
        positionMs = 0,
        durationMs = 218_000,
        playbackRate = 1f,
        channel = channel,
        music = music,
    )

    private val autoEnglish = CaptionLanguage("en", "English (auto-generated)", "a.en", generated = true)

    private val songWithAutoCaptions =
        DualSubUiState(
            activeVideoId = "dQw4w9WgXcQ",
            resolvedSourceLanguage = "en",
            generatedCaptions = true,
            loadedTrackId = "a.en",
            availableSourceLanguages = listOf(autoEnglish),
            segments = listOf(SubtitleSegment(1, 0, 2_000, "Everybody")),
        )

    @Test
    fun songsAreFoundByCategoryTitleOrChannel() {
        assertTrue(looksLikeSong(video("Some song", music = true)))
        assertTrue(looksLikeSong(video("Rachel Gardner - Fun and Games (Lyrics)")))
        assertTrue(looksLikeSong(video("Artist - Song [Official Music Video]")))
        assertTrue(looksLikeSong(video("아티스트 - 노래 MV")))
        assertTrue(looksLikeSong(video("周杰伦 - 晴天 歌词")))
        assertTrue(looksLikeSong(video("Song", channel = "Artist - Topic")))
        assertTrue(looksLikeSong(video("Song", channel = "ArtistVEVO")))
        assertFalse(looksLikeSong(video("Learn Spanish in 10 minutes")))
        assertFalse(looksLikeSong(video("Everyday English conversation", channel = "English with Lucy")))
        assertFalse(looksLikeSong(null))
    }

    @Test
    fun aSongWithOnlyAutoCaptionsGetsTheOfferEvenWithoutAKey() {
        assertTrue(shouldSuggestAiTranscript(songWithAutoCaptions, song = true))
    }

    @Test
    fun noOfferForOtherVideosOrOnceClosed() {
        assertFalse(shouldSuggestAiTranscript(songWithAutoCaptions, song = false))
        val closed = songWithAutoCaptions.copy(aiTranscriptSuggestionClosed = setOf("dQw4w9WgXcQ"))
        assertFalse(shouldSuggestAiTranscript(closed, song = true))
    }

    @Test
    fun noOfferWhenPeopleWroteTheCaptionsOrTheAiTranscriptShows() {
        val human = songWithAutoCaptions.copy(availableSourceLanguages = listOf(autoEnglish, CaptionLanguage("en", "English", ".en")))
        assertFalse(shouldSuggestAiTranscript(human, song = true))
        assertFalse(shouldSuggestAiTranscript(human.copy(generatedCaptions = false), song = true))
        // Human captions in another language don't make the English auto captions right.
        val otherLanguage =
            songWithAutoCaptions.copy(
                availableSourceLanguages = listOf(autoEnglish, CaptionLanguage("es", "Spanish", ".es")),
            )
        assertTrue(shouldSuggestAiTranscript(otherLanguage, song = true))
        val aiShown = songWithAutoCaptions.copy(loadedTrackId = "ai", generatedCaptions = false)
        assertFalse(shouldSuggestAiTranscript(aiShown, song = true))
        assertFalse(shouldSuggestAiTranscript(songWithAutoCaptions.copy(segments = emptyList()), song = true))
        assertFalse(shouldSuggestAiTranscript(songWithAutoCaptions.copy(liveFallback = true), song = true))
    }
}
