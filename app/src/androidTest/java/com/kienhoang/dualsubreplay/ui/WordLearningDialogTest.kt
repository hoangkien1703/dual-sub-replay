package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    @Test fun missingVoiceOffersSettingsAndAllowsPronunciationRetry() {
        var settingsOpened = 0
        var spoken = 0
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, false, { "từ" }, { _, _ -> }, { spoken++ },
                "No installed speech engine has a voice for this language.", {},
                onSpeechSettings = { settingsOpened++ })
        } }
        compose.onNodeWithTag("speech_settings").performScrollTo().performClick()
        compose.onNodeWithTag("pronounce_word").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(1, spoken)
        }
    }
}
