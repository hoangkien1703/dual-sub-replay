package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.*
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WordLearningDialogTest {
    @get:Rule val compose = createComposeRule()
    private val selection = LearningWordSelection(AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN), "en", "vi",
        "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "a word", null), false)

    @Test fun pronouncesOnceAndSavesOnlineClipChoiceWithoutOfflineOption() {
        var spoken = 0
        var saved: Pair<String, Boolean>? = null
        val recompose = mutableStateOf<String?>(null)
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, true, { "từ" }, { meaning, online -> saved = Pair(meaning, online) },
                { spoken++ }, recompose.value, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken); recompose.value = "Voice ready" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken) }
        saveUiEvidence("word-definition")
        compose.onNodeWithTag("offline_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("online_clip_choice").assertIsDisplayed()
        compose.onNodeWithTag("save_word").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Pair("từ", true), saved) }
        compose.onNodeWithTag("word_saved").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun englishWordShowsItsDictionaryFormAndLevel() {
        LearnerData.useAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertTrue(EnglishMorphology.awaitReady())
        val went = LanguageAwareTokenizer.tokenize("We went home.", "en").first { it.text == "went" }
        val wentSelection = selection.copy(token = went)
        compose.setContent {
            DualSubTheme { WordLearningDialog(wentSelection, false, { "đi" }, { _, _ -> }, {}, null, {}) }
        }
        compose.onNodeWithTag("dictionary_form").assertTextEquals("Dictionary form: go")
        compose.onNodeWithContentDescription("Word level: CEFR A1").assertIsDisplayed()
        saveUiEvidence("word-card-english")
    }

    @Test fun manualSpeechWorksWhenAutomaticSpeechIsDisabled() {
        var spoken = 0
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, false, { "từ" }, { _, _ -> }, { spoken++ }, null, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, spoken) }
        compose.onNodeWithTag("pronounce_word").performClick()
        compose.runOnIdle { assertEquals(1, spoken) }
    }

    @Test fun liveWordCanBeSavedWithoutOfferingUnreliableClipExamples() {
        var saved: Pair<String, Boolean>? = null
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection.copy(videoId = null, segment = null), false, { "từ" },
                { meaning, online -> saved = Pair(meaning, online) }, {}, null, {})
        } }
        compose.onNodeWithTag("offline_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("online_clip_choice").assertDoesNotExist()
        compose.onNodeWithTag("save_word").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Pair("từ", false), saved) }
    }

    @Test
    fun missingVoiceOffersSettingsAndAllowsPronunciationRetry() {
        var settingsOpened = 0
        var spoken = 0
        compose.setContent {
            DualSubTheme {
                WordLearningDialog(
                    selection = selection,
                    autoPronounce = false,
                    onTranslateWord = { "từ" },
                    onSave = { _, _ -> },
                    onSpeak = { spoken++ },
                    speechMessage = "No installed speech engine has a voice for this language.",
                    onDismiss = {},
                    onSpeechSettings = { settingsOpened++ },
                )
            }
        }
        compose.onNodeWithTag("speech_settings").performScrollTo().performClick()
        compose.onNodeWithTag("pronounce_word").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(1, spoken)
        }
    }

    @Test
    fun closeSitsApartFromSaveAndPhraseListsEachWord() {
        val parts =
            listOf(
                AnalyzedToken("looking", 2, 9, PartOfSpeech.VERB),
                AnalyzedToken("forward", 10, 17, PartOfSpeech.ADVERB),
                AnalyzedToken("to", 18, 20, PartOfSpeech.PREPOSITION),
            )
        val phrase =
            selection.copy(
                token = AnalyzedToken("looking forward to", 2, 20, PartOfSpeech.OTHER),
                parts = parts,
            )
        val spokenParts = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            DualSubTheme {
                WordLearningDialog(
                    selection = phrase,
                    autoPronounce = false,
                    onTranslateWord = { "mong chờ" },
                    onSave = { _, _ -> },
                    onSpeak = {},
                    speechMessage = null,
                    onDismiss = { dismissed++ },
                    onSpeakPart = { spokenParts += it.text },
                )
            }
        }
        compose.onNodeWithText("Phrase").assertIsDisplayed()
        compose.onNodeWithText("Save to vocabulary").assertIsDisplayed()
        compose.onNodeWithText("Word by word").performScrollTo().assertIsDisplayed()
        saveUiEvidence("phrase-card")
        compose.onNodeWithTag("pronounce_part_1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("forward"), spokenParts) }
        compose.onNodeWithTag("close_word_card").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
