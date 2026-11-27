package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Listening practice (owner pick 2026-11-27): with neither line shown while playing, the panel keeps
 * every line as dots, and a tap on a line's dots shows just that line.
 */
class ListeningPracticeUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val videoId = "listening_test"
    private val segments =
        listOf(
            "It was the first snowfall of the year." to "Đó là trận tuyết đầu tiên của năm.",
            "We begin to make a snowman." to "Chúng tôi bắt đầu đắp người tuyết.",
            "My little brother brings a carrot." to "Em trai tôi mang tới một củ cà rốt.",
            "Then we take a photo together." to "Rồi chúng tôi chụp ảnh cùng nhau.",
        ).mapIndexed { index, (original, translated) ->
            SubtitleSegment(index.toLong(), index * 3_000L, index * 3_000L + 2_800L, original, translated)
        }

    private val playing =
        DualSubUiState(
            activeVideoId = videoId,
            segments = segments,
            currentIndex = 2,
            playbackPaused = false,
            originalVisibility = CaptionVisibility.PAUSED,
            translatedVisibility = CaptionVisibility.PAUSED,
            resolvedSourceLanguage = "en",
            wordLearningEnabled = false,
        )

    private val played = mutableListOf<Long>()

    private fun showPanel(
        state: DualSubUiState,
        listening: ListeningPracticeState = ListeningPracticeState(),
    ) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalListeningPractice provides listening) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(480.dp).testTag(PANEL),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            TranslatedSubtitleTimeline(state, onWordClick = {}, onReplay = { played += it.id })
                        }
                    }
                }
            }
        }
    }

    private fun hiddenLines() = composeRule.onAllNodesWithTag("listening_hidden_line", useUnmergedTree = true)

    @Test
    fun linesHideAsDotsAndATapShowsJustThatLine() {
        assertTrue(playing.tracksVisible())
        showPanel(playing, ListeningPracticeState().apply { reveal(DictationKey(videoId, 0)) })
        composeRule.onNodeWithText("It was the first snowfall of the year.").assertIsDisplayed()
        composeRule.onNodeWithText("Đó là trận tuyết đầu tiên của năm.").assertIsDisplayed()
        composeRule.onNodeWithText("My little brother brings a carrot.").assertDoesNotExist()
        assertEquals(3, hiddenLines().fetchSemanticsNodes().size)
        saveUiEvidence("listening-practice-hidden", composeRule.onNodeWithTag(PANEL).captureToImage())

        // The playing line's dots: the line and its translation show, and the video doesn't jump.
        hiddenLines()[1].performClick()
        composeRule.onNodeWithText("My little brother brings a carrot.").assertIsDisplayed()
        composeRule.onNodeWithText("Em trai tôi mang tới một củ cà rốt.").assertIsDisplayed()
        composeRule.onNodeWithText("We begin to make a snowman.").assertDoesNotExist()
        assertEquals(2, hiddenLines().fetchSemanticsNodes().size)
        composeRule.runOnIdle { assertTrue(played.isEmpty()) }
        // Only shown lines get the hide icon: the first line and the one just tapped.
        assertEquals(2, composeRule.onAllNodesWithTag("listening_hide_line", useUnmergedTree = true).fetchSemanticsNodes().size)
        saveUiEvidence("listening-practice-revealed", composeRule.onNodeWithTag(PANEL).captureToImage())

        // The hide icon turns the line back into dots.
        composeRule.onAllNodesWithTag("listening_hide_line", useUnmergedTree = true)[1].performClick()
        composeRule.onNodeWithText("My little brother brings a carrot.").assertDoesNotExist()
        assertEquals(3, hiddenLines().fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithTag("listening_hide_line", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.runOnIdle { assertTrue(played.isEmpty()) }
    }

    @Test
    fun pausingRevealsNothingAndAShownLineStaysShown() {
        val listening = ListeningPracticeState().apply { reveal(DictationKey(videoId, 3)) }
        showPanel(playing.copy(playbackPaused = true), listening)
        // Pausing would give the next lines away, so they stay hidden.
        assertEquals(3, hiddenLines().fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Then we take a photo together.").assertIsDisplayed()
    }

    @Test
    fun aLineThatShowsWhilePlayingTurnsItOff() {
        showPanel(playing.copy(translatedVisibility = CaptionVisibility.ALWAYS))
        assertEquals(0, hiddenLines().fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Em trai tôi mang tới một củ cà rốt.").assertIsDisplayed()
    }

    private companion object {
        const val PANEL = "listening_panel"
    }
}
