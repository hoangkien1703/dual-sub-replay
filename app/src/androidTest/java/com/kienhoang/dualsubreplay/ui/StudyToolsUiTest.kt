package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.VideoComprehension
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class StudyToolsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    @After
    fun forgetTheSettings() {
        preferences
            .edit()
            .apply { (STUDY_SETTING_KEYS + PLAYBACK_SETTING_KEYS + APP_COLOR_THEME_PREFERENCE).forEach(::remove) }
            .commit()
    }

    private fun showSettings(section: String = "settings_section_study") {
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = emptyList(),
                    fontScale = 1f,
                    landscapeSplitEnabled = true,
                    onSourceChange = {},
                    onTargetChange = {},
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(section).performScrollTo().performClick()
    }

    @Test
    fun repeatEachLineOffersForever() {
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_$STUDY_REPEAT_FOREVER").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(STUDY_REPEAT_FOREVER, preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, -1)) }
        composeRule.onNodeWithText("Forever").assertIsDisplayed()
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo()
        saveUiEvidence("study-repeat-forever")
    }

    @Test
    fun customRepeatCountTakesAnyCountWithTheButtons() {
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_custom").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_more").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_more").performClick()
        composeRule.runOnIdle { assertEquals(4, preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, -1)) }
        composeRule.onNodeWithTag("study_repeat_custom_count").assertTextContains("4 times")
        composeRule.onNodeWithTag("study_repeat_custom_row").performScrollTo()
        saveUiEvidence("study-repeat-custom")
        composeRule.onNodeWithTag("study_repeat_10").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(10, preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, -1)) }
        composeRule.onNodeWithTag("study_repeat_custom_row").assertDoesNotExist()
    }

    @Test
    fun pageTokensSitWithTheCaptionSettings() {
        showSettings("settings_section_translation")
        // Automatic by default, then On.
        composeRule.onNodeWithTag("page_token_on").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("on", preferences.getString(PAGE_TOKEN_CAPTIONS_PREFERENCE, null)) }
        saveUiEvidence("settings-captions-page-token")
    }

    @Test
    fun theStudyToolsSectionTurnsEachToolOnAndOff() {
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_3").performScrollTo().performClick()
        composeRule.onNodeWithTag("skip_silence_2x").performScrollTo().performClick()
        composeRule.onNodeWithTag("blur_box_switch").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("repeat_line", preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null))
            assertEquals(3, preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, 0))
            assertEquals("2x", preferences.getString(SKIP_SILENCE_PREFERENCE, null))
            assertTrue(preferences.getBoolean(BLUR_BOX_PREFERENCE, false))
        }
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo()
        saveUiEvidence("study-tools-settings")
        composeRule.onNodeWithTag("blur_box_switch").performScrollTo()
        saveUiEvidence("study-tools-settings-lower")
        // The section's line follows the stored value.
        composeRule.onNodeWithTag("settings_section_study").assertTextContains("Play mode: Repeat each line")
        // Page tokens are about loading captions, so they sit in Captions & translation (owner pick 2026-11-11).
        assertTrue(composeRule.onAllNodes(hasTestTag("page_token_on")).fetchSemanticsNodes().isEmpty())
        // Send to and card audio moved to their own section (owner pick 2026-11-09).
        assertTrue(composeRule.onAllNodes(hasTestTag("ankidroid_switch")).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodes(hasTestTag("known_words_off")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun wordLearningHoldsKnownWordsAndCharacterDetails() {
        showSettings("settings_section_word_learning")
        composeRule.onNodeWithTag("kanji_details_switch").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertFalse("Kanji details is on until turned off", preferences.getBoolean(KANJI_DETAILS_PREFERENCE, true))
        }
        // Known words is off at first; Automatic shows its count, and Mark new words goes with Known words.
        assertTrue(composeRule.onAllNodes(hasTestTag("mark_new_words_switch")).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("known_words_auto").performScrollTo().performClick()
        composeRule.onNodeWithTag("known_words_after_10").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("known_words_after_20").performScrollTo().performClick()
        composeRule.onNodeWithTag("mark_new_words_switch").performScrollTo().assertIsDisplayed()
        saveUiEvidence("word-learning-known-words")
        composeRule.onNodeWithTag("known_words_off").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("mark_new_words_switch")).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle {
            assertEquals("off", preferences.getString(KNOWN_WORDS_PREFERENCE, null))
            assertEquals(20, preferences.getInt(KNOWN_WORDS_AFTER_PREFERENCE, 0))
        }
    }

    @Test
    fun flashcardsAndAnkiHoldsSendToAndCardAudio() {
        showSettings("settings_section_flashcards")
        // Send to: AnkiDroid by default; AnkiConnect adds its deck choice.
        composeRule.onNodeWithTag("send_to_app_ankiconnect").performScrollTo().performClick()
        composeRule.onNodeWithTag("ankiconnect_choose_deck").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("ankiconnect", preferences.getString(SEND_TO_APP_PREFERENCE, null)) }
        // The word's voice and the line's sound are on cards until turned off (lion737, #81, 2026-11-12).
        composeRule.onNodeWithTag("card_word_sound_front").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_sentence_sound_off").performScrollTo().assertIsDisplayed()
        saveUiEvidence("study-tools-send-to-settings")
        composeRule.onNodeWithTag("card_sentence_sound_off").performClick()
        composeRule.runOnIdle { assertEquals("off", preferences.getString(CARD_SENTENCE_SOUND_PREFERENCE, null)) }
    }

    @Test
    fun flashcardsAndAnkiPicksThePictureAndTheCardFront() {
        showSettings("settings_section_flashcards")
        // No picture, and the front is the word with its sentence, until changed.
        composeRule.onNodeWithTag("card_picture_off").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_front_listening").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.study_card_front_word_sentence_description),
                substring = true,
            ).performScrollTo()
            .assertIsDisplayed()
        saveUiEvidence("flashcards-picture-and-card-front")
        composeRule.onNodeWithTag("card_front_listening").performClick()
        composeRule
            .onNodeWithText(
                context.getString(R.string.study_card_front_listening_description),
                substring = true,
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("card_picture_front").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("listening", preferences.getString(CARD_FRONT_PREFERENCE, null))
            assertEquals("front", preferences.getString(CARD_PICTURE_PLACE_PREFERENCE, null))
            // The front hides the word, so its voice moved to the back with it.
            assertEquals("back", preferences.getString(CARD_WORD_SOUND_PREFERENCE, null))
        }
        // A Listening front is the line's sound: it plays on the front or not at all.
        composeRule.onNodeWithTag("card_sentence_sound_back").assertDoesNotExist()
        composeRule.onNodeWithTag("card_sentence_sound_front").performScrollTo().assertIsDisplayed()
        saveUiEvidence("flashcards-card-front-listening")
    }

    @Test
    fun theSentenceSoundIsOnTheBackUntilMoved() {
        showSettings("settings_section_flashcards")
        composeRule.onNodeWithTag("card_front_word").performScrollTo().performClick()
        composeRule.onNodeWithTag("card_sentence_sound_front").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, preferences.getString(CARD_SENTENCE_SOUND_PREFERENCE, null)) }
        composeRule.onNodeWithTag("card_sentence_sound_front").performClick()
        composeRule.runOnIdle { assertEquals("front", preferences.getString(CARD_SENTENCE_SOUND_PREFERENCE, null)) }
        composeRule.onNodeWithTag("card_picture_off").performScrollTo()
        composeRule.waitForIdle()
        saveUiEvidence("flashcards-sentence-sound-on-front")
    }

    @Test
    fun playFirstShowsOnlyWhileBothSoundsGoOnCards() {
        showSettings("settings_section_flashcards")
        // Word first until changed (lion737, #81, 2026-11-13).
        composeRule.onNodeWithTag("card_sound_first_word").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_sound_first_sentence").performClick()
        composeRule.runOnIdle { assertEquals("sentence", preferences.getString(CARD_SOUND_FIRST_PREFERENCE, null)) }
        composeRule.waitForIdle()
        saveUiEvidence("flashcards-play-first")
        // With one sound off, or a Listening front, there is nothing to order.
        composeRule.onNodeWithTag("card_word_sound_off").performScrollTo().performClick()
        composeRule.onNodeWithTag("card_sound_first_sentence").assertDoesNotExist()
        composeRule.onNodeWithTag("card_word_sound_front").performScrollTo().performClick()
        composeRule.onNodeWithTag("card_sound_first_sentence").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_front_listening").performScrollTo().performClick()
        composeRule.onNodeWithTag("card_sound_first_sentence").assertDoesNotExist()
        // A Listening front with the word's voice on it too: the line plays first until changed (lion737, #81).
        composeRule.onNodeWithTag("card_word_sound_front").performScrollTo().performClick()
        composeRule.onNodeWithTag("card_sound_first_word").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_sound_first_sentence").assertIsDisplayed()
        composeRule.waitForIdle()
        saveUiEvidence("flashcards-play-first-listening")
    }

    @Test
    fun readingPlaceAndReadAloudChipsAreSaved() {
        showSettings("settings_section_flashcards")
        // Back by default, so Japanese and Chinese cards don't give the answer's reading away.
        composeRule.onNodeWithTag("card_reading_back").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("card_reading_front").performClick()
        composeRule.runOnIdle { assertEquals("front", preferences.getString(CARD_READING_PLACE_PREFERENCE, null)) }
        // Only the meaning is read aloud until more is picked (owner pick 2026-11-16).
        composeRule.onNodeWithTag(CARD_READ_DICTIONARY_PREFERENCE).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(preferences.getBoolean(CARD_READ_DICTIONARY_PREFERENCE, false))
            assertTrue(preferences.getBoolean(CARD_READ_MEANING_PREFERENCE, true))
            assertFalse(preferences.getBoolean(CARD_READ_TRANSLATION_PREFERENCE, false))
        }
        composeRule.waitForIdle()
        saveUiEvidence("flashcards-reading-and-read-aloud")
    }

    @Test
    fun theSleepTimerLivesInPlaybackAndOnlyJoinsTheGearPopupWhenAsked() {
        showSettings("settings_section_playback")
        composeRule.onNodeWithTag("sleep_timer").performScrollTo().assertIsDisplayed()
        // Off by default, so the gear popup keeps only the languages and Hide while playing.
        composeRule.onNodeWithTag("sleep_timer_quick_switch").performScrollTo().assertIsOff()
        composeRule.waitForIdle()
        saveUiEvidence("settings-playback-sleep-timer")
        composeRule.onNodeWithTag("sleep_timer_quick_switch").performClick()
        composeRule.runOnIdle { assertTrue(preferences.getBoolean(SLEEP_TIMER_QUICK_PREFERENCE, false)) }
        // Whole transcript joins the gear popup only when turned on, too (owner, 2026-11-17).
        composeRule.onNodeWithTag("whole_transcript_switch").performScrollTo().assertIsOff()
        composeRule.onNodeWithTag("whole_transcript_switch").performClick()
        composeRule.runOnIdle { assertTrue(preferences.getBoolean(WHOLE_TRANSCRIPT_PREFERENCE, false)) }
    }

    @Test
    fun theStudyToolsSectionFollowsTheLightTheme() {
        preferences.edit().putString(APP_COLOR_THEME_PREFERENCE, AppColorThemeOption.LIGHT.key).commit()
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_normal").performScrollTo()
        composeRule.waitForIdle()
        saveUiEvidence("study-tools-settings-light")
    }

    @Test
    fun theScoreChipAndNewWordMarksShowForAVideo() {
        val segment = SubtitleSegment(1, 0, 2_000, "I like cats", "Tôi thích mèo")
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val tools =
                remember {
                    StudyTools(
                        KnownWordsRepository(File(context.cacheDir, "known-words-ui-test")),
                        AnkiDroidNotes(context),
                        AnkiConnectNotes.create(context),
                        scope,
                    ).apply {
                        settings = StudySettings(knownWordsMode = KnownWordsMode.AUTO, markNewWords = true)
                        language = "en"
                        known = setOf("i", "like")
                        comprehension = VideoComprehension(3, 2, 0, setOf("cats"), setOf(0))
                    }
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp)) {
                            ComprehensionChip()
                            CompactSubtitleCard(segment, true, 1f, {}, resolvedSourceLanguage = "en")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Known 66%").assertIsDisplayed()
        saveUiEvidence("study-known-words-chip", composeRule.onRoot().captureToImage())
        composeRule.onNodeWithTag("comprehension_chip").performClick()
        composeRule.onNodeWithTag("mark_all_known").performClick()
        composeRule.onNodeWithTag("confirm_mark_all_known").assertIsDisplayed()
        saveUiEvidence("study-mark-all-known")
    }

    /** The open video's Study tools for a word card test, kept across recompositions. */
    @Composable
    private fun studyTools(
        folder: String,
        setUp: StudyTools.() -> Unit,
    ): StudyTools {
        val scope = rememberCoroutineScope()
        return remember {
            StudyTools(
                KnownWordsRepository(File(context.cacheDir, folder)),
                AnkiDroidNotes(context),
                AnkiConnectNotes.create(context),
                scope,
            ).apply(setUp)
        }
    }

    @Test
    fun theWordCardShowsHowOftenAutomaticSawTheWordAndMoreHoldsIKnowThisWord() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val tools =
                studyTools("known-words-seen-test") {
                    settings = StudySettings(knownWordsMode = KnownWordsMode.AUTO)
                    language = "en"
                    wordsLanguage = "en"
                    seen = mapOf("cats" to 4)
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme { WordLearningDialog(selection, false, { "mèo" }, { _, _ -> }, {}, null, {}, onOpenSettings = {}) }
            }
        }

        composeRule.onNodeWithText("Seen 4 of 10 times").assertIsDisplayed()
        composeRule.onNodeWithTag("word_card_more").performClick()
        composeRule.onNodeWithTag("known_word_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("word_card_settings").assertIsDisplayed()
        saveUiEvidence("study-known-word-seen")
    }

    @Test
    fun iKnowThisWordWaitsUntilKnownWordsIsOn() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val tools = studyTools("known-words-off-test") { wordsLanguage = "en" }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme { WordLearningDialog(selection, false, { "mèo" }, { _, _ -> }, {}, null, {}, onOpenSettings = {}) }
            }
        }

        // Known words is off by default (owner pick 2026-11-17): More holds only Word card settings.
        composeRule.onNodeWithTag("word_card_more").performClick()
        composeRule.onNodeWithTag("word_card_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("known_word_toggle").assertDoesNotExist()
    }

    @Test
    fun theAnkiButtonSendsToTheAppPickedInSettings() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val tools =
                studyTools("known-words-send-to-test") {
                    ankiInstalled = true
                    // Nothing serves AnkiConnect on the test phone, so the card says so.
                    settings = StudySettings(sendTo = FlashcardApp.ANKICONNECT)
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme { WordLearningDialog(selection, false, { "mèo" }, { _, _ -> }, {}, null, {}, onOpenSettings = {}) }
            }
        }

        composeRule.onNodeWithContentDescription("Send to AnkiConnect").assertIsDisplayed()
        // The bar's order: Save to Practice, Anki, then More at the far right.
        val save = composeRule.onNodeWithTag("save_word").fetchSemanticsNode().boundsInRoot
        val anki = composeRule.onNodeWithTag("send_to_anki").fetchSemanticsNode().boundsInRoot
        val more = composeRule.onNodeWithTag("word_card_more").fetchSemanticsNode().boundsInRoot
        assertTrue(save.right <= anki.left && anki.right <= more.left)
        saveUiEvidence("word-card-bar-anki")
        composeRule.onNodeWithTag("send_to_anki").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Nothing answered", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        saveUiEvidence("study-send-to-ankiconnect")
    }

    @Test
    fun alsoSendSavedWordsHidesTheAnkiButtonAndSaveSendsTheCard() {
        var saved = 0
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val tools =
                studyTools("known-words-send-on-save-test") {
                    ankiInstalled = true
                    settings = StudySettings(sendTo = FlashcardApp.ANKICONNECT, sendOnSave = true)
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme { WordLearningDialog(selection, false, { "mèo" }, { _, _ -> saved++ }, {}, null, {}, onOpenSettings = {}) }
            }
        }

        composeRule.onNodeWithTag("send_to_anki").assertDoesNotExist()
        composeRule.onNodeWithTag("save_word").performClick()
        composeRule.runOnIdle { assertEquals(1, saved) }
        // The send after Save to Practice failed (no AnkiConnect here): its problem shows and the Anki button comes back to retry.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Nothing answered", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("send_to_anki").assertIsDisplayed()
        saveUiEvidence("word-card-send-on-save-problem")
    }

    @Test
    fun flashcardsAndAnkiHoldsTheVideoExampleAndAlsoSendSavedWords() {
        showSettings("settings_section_flashcards")
        composeRule.onNodeWithTag("saved_word_video_example_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("send_on_save_switch").performScrollTo().assertIsOff()
        saveUiEvidence("flashcards-send-saved-words")
        composeRule.onNodeWithTag("send_on_save_switch").performClick()
        composeRule.onNodeWithTag("saved_word_video_example_switch").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(preferences.getBoolean(SEND_ON_SAVE_PREFERENCE, false))
            assertFalse(preferences.getBoolean(SAVED_WORD_VIDEO_EXAMPLE_PREFERENCE, true))
        }
    }
}
