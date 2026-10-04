package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhraseSelectionUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val original = "I'm really looking forward to seeing you."
    private val translated = "Mình rất mong được gặp bạn."
    private val taps = mutableListOf<WordTap>()
    private val spoken = mutableListOf<Pair<String, Boolean>>()
    private var pauses = 0
    private var replays = 0
    private val translatedTexts = mutableListOf<Pair<String, Boolean>>()

    private fun showCard() {
        val controller = PhraseSelectionController()
        controller.actions =
            PhraseActions(
                pause = { pauses++ },
                pronounce = { text, isTranslated -> spoken += text to isTranslated },
                translate = { text, isTranslated ->
                    translatedTexts += text to isTranslated
                    "nghĩa của $text"
                },
            )
        compose.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalPhraseSelection provides controller) {
                    CompactSubtitleCard(
                        segment = SubtitleSegment(1, 1_000, 3_000, original, translated),
                        active = true,
                        fontScale = 1.4f,
                        onReplay = { replays++ },
                        wordLearningEnabled = true,
                        tapToLearnEnabled = true,
                        resolvedSourceLanguage = "en",
                        targetLanguage = "vi",
                        onWordClick = { taps += it },
                    )
                }
            }
        }
    }

    /** Taps the character at [index] of the subtitle line [text]. */
    private fun tapCharacter(
        text: String,
        index: Int,
    ) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(layouts)
        val center = layouts.first().getBoundingBox(index).center
        node.performTouchInput { click(center) }
        compose.waitForIdle()
    }

    private fun tapWord(
        text: String,
        word: String,
    ) = tapCharacter(text, text.indexOf(word) + 1)

    @Test
    fun tappingTwoWordsSelectsThePhraseAndTranslateSendsIt() {
        showCard()
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        compose.onNodeWithText("Tap another word to select a phrase").assertIsDisplayed()
        // One word shows its meaning in the bar at once.
        compose.onNodeWithTag("phrase_quick_translation").assertIsDisplayed()
        compose.onNodeWithText("nghĩa của looking").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-word")
        tapWord(original, " to ")
        saveUiEvidence("phrase-action-bar")
        compose.onNodeWithText("Tap another word to select a phrase").assertDoesNotExist()
        // A phrase shows its meaning in the bar at once too.
        compose.onNodeWithTag("phrase_quick_translation").assertIsDisplayed()
        compose.onNodeWithText("nghĩa của looking forward to").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("looking" to false, "looking forward to" to false), translatedTexts)
        }

        compose.onNodeWithTag("phrase_translate").performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(1, taps.size)
            assertEquals("looking forward to", taps.single().token.text)
            assertEquals(listOf("looking", "forward", "to"), taps.single().parts.map { it.text })
            assertEquals(false, taps.single().translated)
        }
        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()
    }

    @Test
    fun copyAndPronounceKeepTheSelectionWithoutOpeningTheCard() {
        showCard()
        tapWord(original, "seeing")
        tapWord(original, "really")

        compose.onNodeWithTag("phrase_copy").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithTag("phrase_pronounce").performClick()
        compose.runOnIdle {
            assertEquals(listOf("really looking forward to seeing" to false), spoken)
            assertTrue(taps.isEmpty())
        }

        compose.onNodeWithTag("phrase_close").performClick()
        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()
    }

    @Test
    fun translatedLineSelectsItsOwnWords() {
        showCard()
        tapWord(translated, "mong")
        tapWord(translated, "gặp")
        compose.onNodeWithTag("phrase_translate").performClick()

        compose.runOnIdle {
            assertEquals("mong được gặp", taps.single().token.text)
            assertTrue(taps.single().translated)
        }
    }

    @Test
    fun blankTapReplaysWithoutASelectionAndClearsOne() {
        showCard()
        tapCharacter(original, original.indexOf(' '))
        compose.runOnIdle { assertEquals(1, replays) }

        tapWord(original, "looking")
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()

        tapWord(original, "looking")
        tapCharacter(original, original.indexOf(' '))
        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, replays) }
    }
}
