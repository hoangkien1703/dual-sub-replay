package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The panel for a video YouTube has no captions for (owner pick 2026-11-08), in each of its states. */
class NoCaptionsCardUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var made = 0
    private var settingsOpened = 0
    private val actions = AiSubtitleActions(make = { made++ }, openAiSettings = { settingsOpened++ })
    private val videoId = "yjsAuMiLc_A"

    private val noCaptions =
        DualSubUiState(
            activeVideoId = videoId,
            liveFallback = true,
            noCaptions = true,
            fullTranscriptProblem =
                "Full transcript: YouTube lists no captions for this video.\n" +
                    "App download: This video does not provide a public caption track.",
        )

    /** Saves the panel as drawn; a whole-screen capture can run before the frame is on screen and come out blank. */
    private fun saveEvidence(name: String) = saveUiEvidence(name, composeRule.onNodeWithTag(PANEL).captureToImage())

    /** Draws [content] the way the app's subtitle panel does: on its Surface, so text gets the panel's colour. */
    @Composable
    private fun AppPanel(content: @Composable () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().testTag(PANEL),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.padding(16.dp)) { content() }
        }
    }

    private fun showPanel(state: DualSubUiState) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalAiSubtitleActions provides actions) {
                    AppPanel {
                        Box(Modifier.fillMaxWidth().height(420.dp)) {
                            LiveSubtitlePanel(state, onRetry = {}, onWordClick = {})
                        }
                    }
                }
            }
        }
    }

    private fun showCard(
        status: AiSubtitleStatus?,
        geminiReady: Boolean,
    ) {
        composeRule.setContent {
            DualSubTheme {
                AppPanel { NoCaptionsCard(status, geminiReady, actions) }
            }
        }
    }

    /** Draws the box over the video the way the player does, on a dark stand-in for the video. */
    private fun showOverlay(
        state: DualSubUiState,
        geminiReady: Boolean,
        onAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            DualSubTheme {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .height(240.dp)
                        .background(Color(0xFF263238))
                        .testTag(PANEL),
                ) {
                    val action = noCaptionsOverlayAction(state, geminiReady)
                    LearningSubtitleOverlay(
                        content = learningOverlayContent(state)!!.copy(noCaptionsAction = action),
                        fontScale = 1f,
                        orientation = Configuration.ORIENTATION_PORTRAIT,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                        onSettings = {},
                        onRetryTranscript =
                            if (action != null) {
                                onAction
                            } else if (showsOverlayRetry(state)) {
                                ({})
                            } else {
                                null
                            },
                        onClose = {},
                    )
                }
            }
        }
    }

    @Test
    fun theBoxOverTheVideoSaysThereAreNoCaptionsAndOffersAKey() {
        var tapped = 0
        showOverlay(noCaptions.copy(statusMessage = "Waiting for YouTube captions and their language."), geminiReady = false) { tapped++ }
        composeRule.onNodeWithText("not even automatic ones", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for YouTube captions", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Live subtitles", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Retry full transcript").assertDoesNotExist()
        composeRule.onNodeWithText("Add a free Gemini key", useUnmergedTree = true).assertIsDisplayed()
        saveEvidence("overlay-no-captions-add-key")
        composeRule.onNodeWithTag("overlay_no_captions_action").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun theBoxOverTheVideoMakesAiSubtitlesWithAKey() {
        showOverlay(noCaptions, geminiReady = true)
        composeRule.onNodeWithText("Make subtitles with AI", useUnmergedTree = true).assertIsDisplayed()
        saveEvidence("overlay-no-captions-make")
    }

    @Test
    fun theBoxOverTheVideoShowsHowFarAiSubtitlesAre() {
        showOverlay(
            noCaptions.copy(aiSubtitles = AiSubtitleStatus(videoId, working = true, doneMs = 300_000, totalMs = 564_000)),
            geminiReady = true,
        )
        composeRule.onNodeWithText("Making subtitles with AI… 5:00 of 9:24").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_no_captions_action").assertDoesNotExist()
        saveEvidence("overlay-no-captions-making")
    }

    @Test
    fun withoutAGeminiKeyTheButtonOpensAiSettings() {
        showPanel(noCaptions)
        composeRule.onNodeWithText("No subtitles on YouTube").assertIsDisplayed()
        composeRule.onNodeWithText("not even automatic ones", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for YouTube captions", substring = true).assertDoesNotExist()
        saveEvidence("no-captions-add-key")
        composeRule.onNodeWithTag("ai_subtitles_add_key").performClick()
        assertEquals(1, settingsOpened)
        assertEquals(0, made)
    }

    @Test
    fun withAGeminiKeyTheButtonMakesSubtitles() {
        showCard(status = null, geminiReady = true)
        composeRule.onNodeWithText("Make subtitles with AI").assertIsDisplayed()
        saveEvidence("no-captions-make")
        composeRule.onNodeWithTag("ai_subtitles_make").performClick()
        assertEquals(1, made)
    }

    @Test
    fun progressShowsHowMuchIsDone() {
        showCard(AiSubtitleStatus(videoId, working = true, doneMs = 300_000, totalMs = 564_000), geminiReady = true)
        composeRule.onNodeWithText("Making subtitles with AI… 5:00 of 9:24").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_subtitles_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_subtitles_make").assertDoesNotExist()
        saveEvidence("no-captions-making")
    }

    @Test
    fun aProblemOffersTryAgain() {
        showCard(AiSubtitleStatus(videoId, working = false, problem = AiSubtitleProblem.LIMIT), geminiReady = true)
        composeRule.onNodeWithTag("ai_subtitles_problem").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        saveEvidence("no-captions-problem")
    }

    @Test
    fun otherFailuresStillShowLiveSubtitles() {
        showPanel(noCaptions.copy(noCaptions = false))
        composeRule.onNodeWithTag("no_captions_card").assertDoesNotExist()
    }

    @Test
    fun aLiveStreamWithoutCaptionsOffersNoAiButton() {
        showPanel(
            noCaptions.copy(
                liveStream = true,
                fullTranscriptProblem =
                    "Full transcript: YouTube lists no captions for this video.\n" +
                        "Page check: player details 0 tracks, captions menu 0, downloaded 0",
            ),
        )
        composeRule.onNodeWithText("No captions on this live stream").assertIsDisplayed()
        composeRule.onNodeWithTag("live_no_captions_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_subtitles_add_key").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_subtitles_make").assertDoesNotExist()
        saveEvidence("no-captions-live-stream")
    }

    @Test
    fun liveStreamCaptionsWinOverTheNoCaptionsCard() {
        // ABC News Live sends TV-style captions inside the video: no track, but the player draws them.
        showPanel(
            noCaptions.copy(
                liveStream = true,
                liveOriginal = "Daily on ABC news live.",
                liveTranslated = "Hằng ngày trên ABC News Live.",
                resolvedSourceLanguage = "en",
                targetLanguage = "vi",
            ),
        )
        composeRule.onNodeWithTag("live_no_captions_card").assertDoesNotExist()
        composeRule.onNodeWithTag("no_captions_card").assertDoesNotExist()
        composeRule.onNodeWithText("Hằng ngày trên ABC News Live.").assertIsDisplayed()
        saveEvidence("live-stream-captions")
    }

    private companion object {
        const val PANEL = "no_captions_test_panel"
    }
}
