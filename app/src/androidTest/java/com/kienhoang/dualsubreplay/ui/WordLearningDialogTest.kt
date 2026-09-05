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

    @Test fun pronouncesOnceAndSavesBothOptionalClipChoices() {
        var spoken = 0
        var saved: Triple<String, Boolean, Boolean>? = null
        val recompose = mutableStateOf<String?>(null)
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, true, { "từ" }, { meaning, online, offline -> saved = Triple(meaning, online, offline) },
                { spoken++ }, recompose.value, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken); recompose.value = "Voice ready" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, spoken) }
        compose.onNodeWithTag("offline_clip_choice").performScrollTo().performClick()
        compose.onNodeWithTag("save_word").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Triple("từ", true, true), saved) }
        compose.onNodeWithTag("word_saved").performScrollTo().assertIsDisplayed()
    }
    @Test fun manualSpeechWorksWhenAutomaticSpeechIsDisabled() {
        var spoken = 0
        compose.setContent { DualSubTheme {
            WordLearningDialog(selection, false, { "từ" }, { _, _, _ -> }, { spoken++ }, null, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, spoken) }
        compose.onNodeWithTag("pronounce_word").performClick()
        compose.runOnIdle { assertEquals(1, spoken) }
    }
}
