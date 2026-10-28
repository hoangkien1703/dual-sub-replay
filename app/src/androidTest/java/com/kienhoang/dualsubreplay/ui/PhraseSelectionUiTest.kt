package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val googleOpened = mutableListOf<String>()
    private var pauses = 0
    private var replays = 0
    private val translatedTexts = mutableListOf<Pair<String, Boolean>>()

    private fun showCard() {
        val controller = PhraseSelectionController()
        controller.actions =
            PhraseActions(
                pause = { pauses++ },
                pronounce = { text, isTranslated -> spoken += text to isTranslated },
                openGoogleTranslate = { text -> googleOpened += text },
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

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(layouts)
        return layouts.first()
    }

    /**
     * Drags the end handle from the end of [from] onto the middle of [to] in [text], in one move:
     * the handle's popup jumps to each new word, and the test moves its finger relative to it.
     */
    private fun dragEndHandle(
        text: String,
        from: String,
        to: String,
    ) {
        val layout = layoutOf(text)
        val end = layout.getBoundingBox(text.indexOf(from) + from.length - 1)
        val target = layout.getBoundingBox(text.indexOf(to) + to.length / 2).center
        compose.onNodeWithTag("phrase_handle_end").performTouchInput {
            down(Offset(width / 2f, height / 3f))
            moveBy(target - Offset(end.right, end.center.y))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun draggingTheEndHandleSelectsThePhraseAndTranslateSendsIt() {
        showCard()
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        compose.onNodeWithTag("phrase_handle_start").assertIsDisplayed()
        compose.onNodeWithTag("phrase_handle_end").assertIsDisplayed()
        compose.onNodeWithText("Drag the handles to select more words").assertIsDisplayed()
        // One word shows its meaning in the bar at once.
        compose.onNodeWithTag("phrase_quick_translation").assertIsDisplayed()
        compose.onNodeWithText("nghĩa của looking").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-word")
        dragEndHandle(original, from = "looking", to = "to ")
        saveUiEvidence("phrase-action-bar")
        compose.onNodeWithText("Drag the handles to select more words").assertDoesNotExist()
        // A phrase shows its meaning in the bar too, once the finger lets go.
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
        compose.onNodeWithTag("phrase_handle_end").assertDoesNotExist()
    }

    @Test
    fun holdingAWordAndDraggingSelectsEveryWordPassed() {
        showCard()
        val layout = layoutOf(original)
        val really = layout.getBoundingBox(original.indexOf("really") + 2).center
        val forward = layout.getBoundingBox(original.indexOf("forward") + 3).center
        compose.onNodeWithText(original, useUnmergedTree = true).performTouchInput {
            down(really)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            moveTo(Offset((really.x + forward.x) / 2, really.y))
            moveTo(forward)
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithText("nghĩa của really looking forward").assertIsDisplayed()
        compose.onNodeWithTag("phrase_pronounce").performClick()
        compose.runOnIdle {
            assertEquals(listOf("really looking forward" to false), spoken)
            assertEquals(1, pauses)
            assertEquals(0, replays)
        }
    }

    @Test
    fun tappingAnotherWordMovesTheSelectionAndCopyKeepsIt() {
        showCard()
        tapWord(original, "seeing")
        tapWord(original, "really")
        compose.onNodeWithText("nghĩa của really").assertIsDisplayed()

        compose.onNodeWithTag("phrase_copy").performClick()
        compose.onNodeWithContentDescription("Copied").assertIsDisplayed()
        compose.onNodeWithTag("phrase_pronounce").performClick()
        compose.runOnIdle {
            assertEquals(listOf("really" to false), spoken)
            assertTrue(taps.isEmpty())
        }

        compose.onNodeWithTag("phrase_close").performClick()
        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()
    }

    @Test
    fun tappingOutsideTheSubtitlesClearsTheSelection() {
        val controller = PhraseSelectionController()
        compose.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalPhraseSelection provides controller) {
                    Column(Modifier.clearPhraseSelectionOnTapElsewhere(controller)) {
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
                        Box(Modifier.fillMaxWidth().height(120.dp).testTag("elsewhere"))
                    }
                }
            }
        }
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        // A tap on a word of the other line moves the selection there and keeps it.
        tapWord(translated, "mong")
        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()

        compose.onNodeWithTag("elsewhere").performTouchInput { click(center) }
        compose.waitForIdle()

        compose.onNodeWithTag("phrase_action_bar").assertDoesNotExist()
        compose.onNodeWithTag("phrase_handle_start").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(controller.selection)
            assertEquals(0, replays)
        }
    }

    @Test
    fun allActionsListShowsEachActionByNameAndRunsIt() {
        showCard()
        tapWord(original, "really")

        compose.onNodeWithTag("phrase_all_actions").performClick()
        compose.onNodeWithTag("phrase_menu_copy").assertIsDisplayed()
        compose.onNodeWithTag("phrase_menu_translate").assertIsDisplayed()
        compose.onNodeWithText("Pronounce").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-all-actions")
        compose.onNodeWithTag("phrase_menu_pronounce").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("phrase_menu_pronounce").assertDoesNotExist()
        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("really" to false), spoken)
            assertTrue(taps.isEmpty())
        }
    }

    @Test
    fun googleTranslateInTheMenuSendsTheSelectedWordAndKeepsTheOtherActions() {
        showCard()
        tapWord(original, "really")

        // Only the ▾ list offers it; the bar keeps its everyday icons.
        compose.onNodeWithTag("phrase_google_translate").assertDoesNotExist()
        compose.onNodeWithTag("phrase_all_actions").performClick()
        compose.onNodeWithTag("phrase_menu_google_translate").performClick()
        compose.onNodeWithTag("phrase_pronounce").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("phrase_action_bar").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("really"), googleOpened)
            assertEquals(listOf("really" to false), spoken)
        }
    }

    @Test
    fun translatedLineSelectsItsOwnWords() {
        showCard()
        tapWord(translated, "mong")
        dragEndHandle(translated, from = "mong", to = "gặp")
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
