package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.assistant.AiMemory
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A dialog fades in after Compose is idle; the screenshot waits for it to finish. */
private const val FADE_IN_MS = 700L

/** The X on dialogs (owner request 2026-10-30): it closes without doing anything else. */
class DialogCloseUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    @After
    fun forgetTheme() {
        preferences.edit().remove(APP_COLOR_THEME_PREFERENCE).commit()
    }

    @Test
    fun theXClosesTheAppLanguageDialogWithoutChoosing() {
        var chosen = false
        compose.setContent { DualSubTheme { AppLanguageButton(onSelect = { chosen = true }) } }
        compose.onNodeWithTag("app_language_button").performClick()
        compose.onNodeWithTag(DIALOG_CLOSE_TAG).assertIsDisplayed()
        Thread.sleep(FADE_IN_MS)
        saveUiEvidence("dialog-close-app-language")
        compose.onNodeWithTag(DIALOG_CLOSE_TAG).performClick()
        compose.onNodeWithTag("app_language_option_device").assertDoesNotExist()
        compose.runOnIdle { assertFalse(chosen) }
    }

    @Test
    fun theXLooksRightOnTheLightTheme() {
        preferences.edit().putString(APP_COLOR_THEME_PREFERENCE, "light").commit()
        compose.setContent { DualSubTheme { AppLanguageButton(onSelect = {}) } }
        compose.onNodeWithTag("app_language_button").performClick()
        compose.onNodeWithTag(DIALOG_CLOSE_TAG).assertIsDisplayed()
        Thread.sleep(FADE_IN_MS)
        saveUiEvidence("dialog-close-light")
    }

    @Test
    fun changedTextIsNeverDroppedSilently() {
        var closed = false
        val memory = AiMemory("m1", "I study Japanese", 0)
        compose.setContent { DualSubTheme { AiMemoryEditDialog(memory, onDismiss = { closed = true }, onSave = { true }) } }
        compose.onNodeWithTag("ai_memory_edit_field").performTextInput(" every day")
        compose.onNodeWithTag("ai_memory_edit_close").performClick()
        compose.onNodeWithTag("dialog_keep_editing").assertIsDisplayed()
        Thread.sleep(FADE_IN_MS)
        saveUiEvidence("dialog-discard-changes")
        compose.onNodeWithTag("dialog_keep_editing").performClick()
        compose.runOnIdle { assertFalse(closed) }
        compose.onNodeWithTag("ai_memory_edit_field").assertIsDisplayed()
        compose.onNodeWithTag("ai_memory_edit_close").performClick()
        compose.onNodeWithTag("dialog_discard").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun unchangedTextClosesAtOnce() {
        var closed = false
        val memory = AiMemory("m1", "I study Japanese", 0)
        compose.setContent { DualSubTheme { AiMemoryEditDialog(memory, onDismiss = { closed = true }, onSave = { true }) } }
        compose.onNodeWithTag("ai_memory_edit_close").performClick()
        compose.onNodeWithTag("dialog_discard").assertDoesNotExist()
        compose.runOnIdle { assertTrue(closed) }
    }
}
