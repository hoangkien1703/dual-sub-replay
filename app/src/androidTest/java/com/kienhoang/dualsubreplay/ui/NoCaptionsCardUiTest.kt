package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

    private companion object {
        const val PANEL = "no_captions_test_panel"
    }
}
