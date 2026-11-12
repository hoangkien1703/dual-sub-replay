package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
            .apply { (STUDY_SETTING_KEYS + APP_COLOR_THEME_PREFERENCE).forEach(::remove) }
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

    @Test
    fun theWordCardShowsHowOftenAutomaticSawTheWord() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val tools =
                remember {
                    StudyTools(
                        KnownWordsRepository(File(context.cacheDir, "known-words-seen-test")),
                        AnkiDroidNotes(context),
                        AnkiConnectNotes.create(context),
                        scope,
                    ).apply {
                        settings = StudySettings(knownWordsMode = KnownWordsMode.AUTO)
                        language = "en"
                        wordsLanguage = "en"
                        seen = mapOf("cats" to 4)
                    }
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp)) { StudyWordActions(selection, "mèo") }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Seen 4 of 10 times").assertIsDisplayed()
        composeRule.onNodeWithTag("known_word_toggle").assertIsDisplayed()
        saveUiEvidence("study-known-word-seen")
    }

    @Test
    fun theFirstKnownWordOffersToTurnOnKnownWordsOnce() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val tools =
                remember {
                    StudyTools(
                        KnownWordsRepository(File(context.cacheDir, "known-words-offer-test")),
                        AnkiDroidNotes(context),
                        AnkiConnectNotes.create(context),
                        scope,
                    ).apply {
                        wordsLanguage = "en"
                    }
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp)) { StudyWordActions(selection, "mèo") }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("known_word_toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("turn_on_known_words").assertIsDisplayed()
        saveUiEvidence("study-known-words-offer")
        composeRule.onNodeWithTag("turn_on_known_words").performClick()
        composeRule.runOnIdle {
            assertEquals("auto", preferences.getString(KNOWN_WORDS_PREFERENCE, null))
            assertTrue(preferences.getBoolean(KNOWN_WORDS_OFFERED_PREFERENCE, false))
        }
    }

    @Test
    fun theSendToButtonListsTheAppsAndRemembersThePick() {
        val selection =
            LearningWordSelection(AnalyzedToken("cats", 7, 11, PartOfSpeech.NOUN), "en", "vi", null, null, translated = false)
        lateinit var tools: StudyTools
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            tools =
                remember {
                    StudyTools(
                        KnownWordsRepository(File(context.cacheDir, "known-words-send-to-test")),
                        AnkiDroidNotes(context),
                        AnkiConnectNotes.create(context),
                        scope,
                    ).apply { ankiInstalled = true }
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp)) { StudyWordActions(selection, "mèo") }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Send to AnkiDroid").assertIsDisplayed()
        composeRule.onNodeWithTag("send_to_menu").performClick()
        composeRule.onNodeWithTag("send_to_option_ankidroid").assertIsDisplayed()
        composeRule.onNodeWithTag("send_to_option_ankiconnect").assertIsDisplayed()
        saveUiEvidence("study-send-to-menu")
        // Nothing serves AnkiConnect on the test phone, so the card says so.
        composeRule.onNodeWithTag("send_to_option_ankiconnect").performClick()
        composeRule.runOnIdle {
            assertEquals("ankiconnect", preferences.getString(SEND_TO_APP_PREFERENCE, null))
            tools.settings = tools.settings.copy(sendTo = FlashcardApp.ANKICONNECT)
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Nothing answered", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Send to AnkiConnect").assertIsDisplayed()
        saveUiEvidence("study-send-to-ankiconnect")
    }
}
