package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.EnglishIpa
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhraseSelectionUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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

    private val askedInSentence = mutableListOf<WordTap>()

    private val ankiCards = mutableListOf<Pair<WordTap, String>>()

    private fun showCard(
        inSentence: String? = null,
        inSentenceFirst: Boolean = true,
        readingInBar: Boolean = false,
        buttons: Set<TapBarButton> = TapBarButton.entries.filterTo(LinkedHashSet()) { it.inBarByDefault },
        anki: Boolean = false,
        ankiPickedFromList: () -> Boolean = { false },
    ) {
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
                meaningInSentence =
                    inSentence?.let { meaning ->
                        { tap ->
                            askedInSentence += tap
                            ContextMeaning.Found(meaning)
                        }
                    },
                meaningInSentenceFirst = inSentenceFirst,
                readingInBar = readingInBar,
                buttons = buttons,
                ankiPickedFromList = ankiPickedFromList,
                ankiCard =
                    if (anki) {
                        { tap, meaning ->
                            ankiCards += tap to meaning
                            SavedWord(
                                "card-${tap.token.text}",
                                tap.token.text,
                                null,
                                "en",
                                "vi",
                                meaning,
                                original,
                                translated,
                                null,
                                1_000,
                                3_000,
                                false,
                            )
                        }
                    } else {
                        null
                    },
            )
        compose.setContent {
            val scope = rememberCoroutineScope()
            // AnkiConnect counts as set up without an app installed, so Add to Anki shows on any emulator.
            val tools =
                remember {
                    StudyTools(
                        KnownWordsRepository(File(context.cacheDir, "known-words-phrase-test")),
                        AnkiDroidNotes(context),
                        AnkiConnectNotes.create(context),
                        scope,
                    ).apply { settings = StudySettings(sendTo = FlashcardApp.ANKICONNECT) }
                }
            DualSubTheme {
                CompositionLocalProvider(LocalPhraseSelection provides controller, LocalStudyTools provides tools.takeIf { anki }) {
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
    fun theBarShowsTheMeaningInTheSentenceWhileThatSettingIsOn() {
        showCard(inSentence = "mong chờ")
        tapWord(original, "looking")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("mong chờ").fetchSemanticsNodes().isNotEmpty() }
        // Show first → Meaning in this sentence: it is the big line, the dictionary meaning under it.
        compose.onNodeWithText("mong chờ").assertIsDisplayed()
        compose.onNodeWithText("Dictionary: nghĩa của looking").assertIsDisplayed()
        assertEquals("looking", askedInSentence.single().token.text)
        saveUiEvidence("phrase-action-bar-meaning-in-sentence")
    }

    @Test
    fun theBarPutsTheDictionaryMeaningFirstWhenShowFirstSaysSo() {
        showCard(inSentence = "mong chờ", inSentenceFirst = false)
        tapWord(original, "looking")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("In this sentence: mong chờ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("nghĩa của looking").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-dictionary-first")
    }

    /** 25kiuo (Discussion #81, 2026-11-17): the word's IPA in the bar, while Pronunciation in the tap bar is on. */
    @Test
    fun theBarShowsTheWordsIpaOnlyWhenTheSettingIsOn() {
        assertTrue("English IPA did not load", EnglishIpa.awaitReady())
        showCard(readingInBar = true)
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_reading").assertIsDisplayed()
        compose.onNodeWithText(EnglishIpa.of("looking")!!).assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-reading")
        // A phrase has no single reading.
        dragEndHandle(original, from = "looking", to = "to ")
        compose.onNodeWithTag("phrase_reading").assertDoesNotExist()
    }

    @Test
    fun theBarShowsNoReadingWhileTheSettingIsOff() {
        assertTrue("English IPA did not load", EnglishIpa.awaitReady())
        showCard()
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_quick_translation").assertIsDisplayed()
        compose.onNodeWithTag("phrase_reading").assertDoesNotExist()
    }

    @Test
    fun theBarAsksNothingWhileMeaningInThisSentenceIsOff() {
        showCard()
        tapWord(original, "looking")
        compose.onNodeWithText("nghĩa của looking").assertIsDisplayed()
        compose.onNodeWithTag("phrase_meaning_in_sentence_loading").assertDoesNotExist()
        compose.onNodeWithTag("phrase_meaning_in_sentence").assertDoesNotExist()
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

    /** lion737 (#81, 2026-11-19): Add to Anki sits next to Pronounce and sends the bar's meaning in one tap. */
    @Test
    fun addToAnkiSendsTheMeaningInTheSentenceInOneTap() {
        showCard(inSentence = "đang mong chờ", buttons = defaultButtons + TapBarButton.ANKI, anki = true)
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_anki").assertIsDisplayed()
        // The bar asks about the sentence after a short pause, so the second line comes a moment later.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("phrase_meaning_in_sentence").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("phrase_meaning_in_sentence").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-add-to-anki")
        compose.onNodeWithTag("phrase_anki").performClick()
        compose.waitUntil(5_000) { ankiCards.isNotEmpty() }
        val (tap, meaning) = ankiCards.single()
        assertEquals("looking", tap.token.text)
        // As the word card saves it: the meaning in the sentence, then the dictionary meaning.
        assertEquals(combinedMeaning("đang mong chờ", "nghĩa của looking"), meaning)
        // No AnkiConnect runs on the emulator: the bar says what stopped the card, as the word card does.
        compose.waitForIdle()
        saveUiEvidence("phrase-action-bar-add-to-anki-sent")
    }

    @Test
    fun tapBarButtonsHideIconsButTheListKeepsEveryAction() {
        showCard(buttons = setOf(TapBarButton.PRONOUNCE, TapBarButton.GOOGLE_TRANSLATE), anki = true)
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_copy").assertDoesNotExist()
        compose.onNodeWithTag("phrase_translate").assertDoesNotExist()
        compose.onNodeWithTag("phrase_anki").assertDoesNotExist()
        compose.onNodeWithTag("phrase_pronounce").assertIsDisplayed()
        compose.onNodeWithTag("phrase_google_translate").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-picked-buttons")
        compose.onNodeWithTag("phrase_all_actions").performClick()
        compose.onNodeWithTag("phrase_menu_copy").assertIsDisplayed()
        compose.onNodeWithTag("phrase_menu_translate").assertIsDisplayed()
        compose.onNodeWithTag("phrase_menu_anki").assertIsDisplayed()
    }

    @Test
    fun addToAnkiJoinsTheBarAfterThreePicksFromTheList() {
        var picks = 0
        showCard(anki = true, ankiPickedFromList = { ++picks >= ANKI_PICKS_FOR_THE_BAR })
        tapWord(original, "looking")
        // Off at first (owner pick 2026-11-20): only the ▾ list has Add to Anki.
        compose.onNodeWithTag("phrase_anki").assertDoesNotExist()
        pickAnkiFromList(1)
        pickAnkiFromList(2)
        compose.onNodeWithTag("phrase_anki").assertDoesNotExist()
        pickAnkiFromList(3)
        compose.onNodeWithTag("phrase_anki").assertIsDisplayed()
        saveUiEvidence("phrase-action-bar-anki-joined")
    }

    private val defaultButtons = TapBarButton.entries.filterTo(LinkedHashSet()) { it.inBarByDefault }

    /** Opens the ▾ list and picks Add to Anki once its last send has finished, then waits for card [count]. */
    private fun pickAnkiFromList(count: Int) {
        compose.onNodeWithTag("phrase_all_actions").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("phrase_menu_anki") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("phrase_menu_anki").performClick()
        compose.waitUntil(10_000) { ankiCards.size == count }
    }

    @Test
    fun addToAnkiHidesWhileSendToIsNotSetUp() {
        showCard()
        tapWord(original, "looking")
        compose.onNodeWithTag("phrase_anki").assertDoesNotExist()
        compose.onNodeWithTag("phrase_all_actions").performClick()
        compose.onNodeWithTag("phrase_menu_anki").assertDoesNotExist()
    }
}
