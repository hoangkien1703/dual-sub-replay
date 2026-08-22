package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LanguageSetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun continueRequiresBothLanguagesAndReportsChoices() {
        var completed: Pair<String, String>? = null
        composeRule.setContent {
            DualSubTheme {
                LanguageSetupScreen(
                    onComplete = { native, learning -> completed = native to learning },
                    onSkip = {},
                )
            }
        }

        composeRule.onNodeWithTag("language_setup_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_continue").assertIsNotEnabled()

        composeRule.onNodeWithTag("native_language_picker").performClick()
        composeRule.onNodeWithText("Your native language").assertIsDisplayed()
        composeRule.onNodeWithTag("language_option_native_vi").performClick()
        composeRule.onNodeWithText("Vietnamese").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_continue").assertIsNotEnabled()

        composeRule.onNodeWithTag("learning_language_picker").performClick()
        composeRule.onNodeWithTag("language_search").performTextInput("Japanese")
        composeRule.onNodeWithTag("language_option_learning_ja").performClick()
        composeRule.onNodeWithText("Japanese").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_continue").assertIsEnabled()

        composeRule.onNodeWithTag("onboarding_continue").performClick()
        composeRule.runOnIdle { assertEquals("vi" to "ja", completed) }
    }

    @Test
    fun skipFinishesWithoutSelections() {
        var skipped = false
        var completed: Pair<String, String>? = null
        composeRule.setContent {
            DualSubTheme {
                LanguageSetupScreen(
                    onComplete = { native, learning -> completed = native to learning },
                    onSkip = { skipped = true },
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_skip").performClick()
        composeRule.runOnIdle {
            assertTrue(skipped)
            assertEquals(null, completed)
        }
    }

    @Test
    fun changingNativeLanguageUpdatesSelection() {
        composeRule.setContent {
            DualSubTheme {
                LanguageSetupScreen(onComplete = { _, _ -> }, onSkip = {})
            }
        }

        composeRule.onNodeWithTag("native_language_picker").performClick()
        composeRule.onNodeWithTag("language_option_native_en").performClick()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithTag("native_language_picker").performClick()
        composeRule.onNodeWithTag("language_option_native_ja").performClick()
        composeRule.onNodeWithText("Japanese").assertIsDisplayed()
    }
}
