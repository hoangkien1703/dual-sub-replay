package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings → Study tools → Play mode → Dictation (owner pick 2026-11-09): hidden lines and the typing box. */
class DictationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    @After
    fun resetTheme() {
        preferences.edit().remove(APP_COLOR_THEME_PREFERENCE).commit()
    }

    private val videoId = "dictation_test"
    private val segments =
        listOf(
            "It was the first snowfall of the year." to "Đó là trận tuyết đầu tiên của năm.",
            "We begin to make a snowman." to "Chúng tôi bắt đầu đắp người tuyết.",
            "My little brother brings a carrot." to "Em trai tôi mang tới một củ cà rốt.",
            "Then we take a photo together." to "Rồi chúng tôi chụp ảnh cùng nhau.",
        ).mapIndexed { index, (original, translated) ->
            SubtitleSegment(index.toLong(), index * 3_000L, index * 3_000L + 2_800L, original, translated)
        }

    private val state =
        DualSubUiState(
            activeVideoId = videoId,
            segments = segments,
            currentIndex = 1,
            playbackPaused = true,
            studyPlayMode = StudyPlayMode.DICTATION,
            resolvedSourceLanguage = "en",
            wordLearningEnabled = false,
        )

    private val played = mutableListOf<Long>()

    private fun showPanel(dictation: DictationState = DictationState()) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalDictation provides dictation) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(560.dp).testTag(PANEL),
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

    private fun saveEvidence(name: String) = saveUiEvidence(name, composeRule.onNodeWithTag(PANEL).captureToImage())

    /** The first line was checked earlier, so the transcript shows it and hides the rest. */
    private fun withFirstLineRevealed() = DictationState().apply { reveal(DictationKey(videoId, 0)) }

    @Test
    fun linesStayHiddenUntilCheckedAndCheckMarksMissedWords() {
        showPanel(withFirstLineRevealed())
        composeRule.onNodeWithText("It was the first snowfall of the year.").assertIsDisplayed()
        composeRule.onNodeWithText("We begin to make a snowman.").assertDoesNotExist()
        assertEquals(3, composeRule.onAllNodesWithTag("dictation_hidden_line", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("dictation_check").assertIsNotEnabled()

        composeRule.onNodeWithTag("dictation_answer").performTextInput("we begin make snowman")
        composeRule.onNodeWithTag("dictation_check").assertIsEnabled()
        saveEvidence("dictation-typing")

        composeRule.onNodeWithTag("dictation_check").performClick()
        composeRule.onNodeWithText("4 of 6 right").assertIsDisplayed()
        composeRule.onNodeWithText("We begin to make a snowman.").assertIsDisplayed()
        composeRule.onNodeWithText("Chúng tôi bắt đầu đắp người tuyết.").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithTag("dictation_hidden_line", useUnmergedTree = true).fetchSemanticsNodes().size)
        saveEvidence("dictation-checked")

        composeRule.onNodeWithTag("dictation_replay").performClick()
        composeRule.onNodeWithTag("dictation_next").performClick()
        composeRule.runOnIdle { assertEquals(listOf(1L, 2L), played) }
    }

    @Test
    fun aPerfectAnswerSaysSo() {
        showPanel()
        composeRule.onNodeWithTag("dictation_answer").performTextInput("We begin to make a snowman")
        composeRule.onNodeWithTag("dictation_check").performClick()
        composeRule.onNodeWithText("Perfect!").assertIsDisplayed()
        saveEvidence("dictation-perfect")
    }

    @Test
    fun showRevealsTheLineWithoutChecking() {
        val dictation = DictationState()
        showPanel(dictation)
        composeRule.onNodeWithTag("dictation_show").performClick()
        composeRule.onNodeWithText("We begin to make a snowman.").assertIsDisplayed()
        composeRule.onNodeWithTag("dictation_result").assertDoesNotExist()
        composeRule.onNodeWithTag("dictation_next").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(dictation.isRevealed(DictationKey(videoId, 1))) }
    }

    @Test
    fun theCheckedLineIsReadableOnTheLightTheme() {
        preferences.edit().putString(APP_COLOR_THEME_PREFERENCE, AppColorThemeOption.LIGHT.key).commit()
        showPanel(withFirstLineRevealed())
        composeRule.onNodeWithTag("dictation_answer").performTextInput("we begin make snowman")
        composeRule.onNodeWithTag("dictation_check").performClick()
        composeRule.onNodeWithText("4 of 6 right").assertIsDisplayed()
        saveEvidence("dictation-checked-light")
    }

    @Test
    fun otherPlayModesShowNoBox() {
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxWidth().height(400.dp)) {
                    TranslatedSubtitleTimeline(state.copy(studyPlayMode = StudyPlayMode.LISTEN_FIRST), onWordClick = {}, onReplay = {})
                }
            }
        }
        composeRule.onNodeWithTag("dictation_box").assertDoesNotExist()
        composeRule.onNodeWithText("We begin to make a snowman.").assertIsDisplayed()
    }

    private companion object {
        const val PANEL = "dictation_test_panel"
    }
}
