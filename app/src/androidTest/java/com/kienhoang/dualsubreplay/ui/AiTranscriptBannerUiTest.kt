package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The card above a song's auto-made transcript (owner pick 2026-11-17), in each of its states. */
class AiTranscriptBannerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var used = 0
    private var lyricsAsked = 0
    private var aiAsked = 0
    private var closed = 0
    private var settingsOpened = 0
    private val actions =
        AiSubtitleActions(
            make = {},
            openAiSettings = { settingsOpened++ },
            useTranscript = { used++ },
            findLyrics = { lyricsAsked++ },
            writeWithAi = { aiAsked++ },
            closeSuggestion = { closed++ },
        )
    private val videoId = "dQw4w9WgXcQ"

    private val song =
        DualSubUiState(
            activeVideoId = videoId,
            resolvedSourceLanguage = "en",
            generatedCaptions = true,
            loadedTrackId = "a.en",
            availableSourceLanguages = listOf(CaptionLanguage("en", "English (auto-generated)", "a.en", generated = true)),
            segments = listOf(SubtitleSegment(1, 0, 2_000, "Everybody", "Mọi người")),
        )

    private fun show(
        state: DualSubUiState,
        geminiReady: Boolean = true,
    ) {
        composeRule.setContent {
            DualSubTheme {
                Surface(
                    modifier = Modifier.fillMaxWidth().systemBarsPadding().testTag(PANEL),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Column {
                        AiTranscriptBannerContent(state, song = true, geminiReady = geminiReady, actions = actions)
                        Text("Another day", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                        Text("Everybody", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }

    private fun saveEvidence(name: String) = saveUiEvidence(name, composeRule.onNodeWithTag(PANEL).captureToImage())

    @Test
    fun aSongWithAutoCaptionsOffersAnAiTranscript() {
        show(song)
        composeRule.onNodeWithText("Get real lyrics").assertIsDisplayed()
        saveEvidence("ai-transcript-offer")
        // Free lyrics only: AI never starts from this button (owner pick 2026-11-24).
        composeRule.onNodeWithTag("ai_transcript_make").performClick()
        assertEquals(1, lyricsAsked)
        assertEquals(0, used)
        composeRule.onNodeWithTag("ai_transcript_offer_close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun noLyricsAndNoKeyOffersToAddOne() {
        show(
            song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.NO_FREE_LYRICS)),
            geminiReady = false,
        )
        composeRule.onNodeWithTag("ai_transcript_add_key").assertIsDisplayed()
        saveEvidence("ai-transcript-no-lyrics-no-key")
        composeRule.onNodeWithTag("ai_transcript_add_key").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun noFreeLyricsWithAKeyOffersAiOnlyOnATap() {
        show(song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.NO_FREE_LYRICS)))
        composeRule.onNodeWithText("No free synced lyrics match this video.").assertIsDisplayed()
        composeRule.onNodeWithText("Write lyrics with AI").assertIsDisplayed()
        saveEvidence("ai-transcript-no-free-lyrics")
        composeRule.onNodeWithTag("ai_transcript_write_with_ai").performClick()
        assertEquals(1, aiAsked)
    }

    @Test
    fun somePlaylistSongsFoundWithAKeyCountsThemAndOffersTheRest() {
        val status =
            AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.SOME_FREE_LYRICS, lyricsFound = 21, lyricsTotal = 30)
        show(song.copy(aiSubtitles = status))
        composeRule.onNodeWithText("Free synced lyrics found for 21 of 30 songs.").assertIsDisplayed()
        saveEvidence("ai-transcript-some-free-lyrics")
        composeRule.onNodeWithText("Write the rest with AI").performClick()
        assertEquals(1, aiAsked)
    }

    @Test
    fun lookingForLyricsShowsWhileYouTubesCaptionsStay() {
        show(song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = true, lookingForLyrics = true)))
        composeRule.onNodeWithText("Looking for synced lyrics…").assertIsDisplayed()
        saveEvidence("ai-transcript-looking-for-lyrics")
    }

    @Test
    fun aCompilationCountsItsSongs() {
        val looking = AiSubtitleStatus(videoId, working = true, lookingForLyrics = true, lyricsChecked = 4, lyricsTotal = 32)
        show(song.copy(aiSubtitles = looking))
        composeRule.onNodeWithText("Looking for synced lyrics… song 5 of 32").assertIsDisplayed()
        saveEvidence("ai-transcript-compilation-songs")
    }

    @Test
    fun someLyricsAndNoKeyOffersToAddOne() {
        show(
            song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.SOME_FREE_LYRICS)),
            geminiReady = false,
        )
        composeRule.onNodeWithTag("ai_transcript_add_key").assertIsDisplayed()
        saveEvidence("ai-transcript-some-lyrics-no-key")
    }

    @Test
    fun progressShowsWhileYouTubesCaptionsStay() {
        show(song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = true, doneMs = 0L, totalMs = 218_000)))
        composeRule.onNodeWithTag("ai_transcript_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_transcript_offer").assertDoesNotExist()
        saveEvidence("ai-transcript-making")
    }

    @Test
    fun aProblemOffersTryAgain() {
        show(song.copy(aiSubtitles = AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.REFUSED)))
        composeRule.onNodeWithTag("ai_transcript_problem").assertIsDisplayed()
        saveEvidence("ai-transcript-problem")
        composeRule.onNodeWithTag("ai_transcript_retry").performClick()
        assertEquals(1, used)
    }

    private companion object {
        const val PANEL = "ai_transcript_test_panel"
    }
}
