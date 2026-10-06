package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnlineTranslationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showTranslationSettings(
        settings: OnlineTranslationSettings,
        actions: TranslationEngineActions = TranslationEngineActions(select = {}),
    ) {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalTranslationEngineActions provides actions) {
                    SubtitleSettingsDialog(
                        sourcePreference = "ja",
                        targetLanguage = "en",
                        availableSourceLanguages = emptyList(),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        onlineTranslation = settings,
                        onSourceChange = {},
                        onTargetChange = {},
                        onFontScaleChange = {},
                        onLandscapeSplitChange = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("settings_section_translation").performScrollTo().performClick()
    }

    @Test
    fun googleSwitchIsOnForTheDefaultEngineAndTurnsOffToOnDevice() {
        val chosen = mutableListOf<TranslationEngine>()
        showTranslationSettings(
            OnlineTranslationSettings(available = true, engine = TranslationEngine.GOOGLE_WEB),
            TranslationEngineActions(select = { chosen += it }),
        )
        composeRule
            .onNodeWithTag("google_translate_switch")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        assertEquals(listOf(TranslationEngine.ON_DEVICE), chosen)
    }

    @Test
    fun googleSwitchTurnsOnToChooseGoogle() {
        val chosen = mutableListOf<TranslationEngine>()
        showTranslationSettings(
            OnlineTranslationSettings(available = true, engine = TranslationEngine.ON_DEVICE),
            TranslationEngineActions(select = { chosen += it }),
        )
        composeRule
            .onNodeWithTag("google_translate_switch")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(listOf(TranslationEngine.GOOGLE_WEB), chosen)
    }

    @Test
    fun googleSwitchIsHiddenWhenTheBuildCannotTranslateOnline() {
        showTranslationSettings(OnlineTranslationSettings(available = false, engine = TranslationEngine.GOOGLE_WEB))
        composeRule.onNodeWithTag("google_translate_switch").assertDoesNotExist()
    }

    @Test
    fun fallbackIconShowsWhyAndTriesGoogleAgain() {
        var triedGoogle = 0
        var retried = 0
        composeRule.setContent {
            DualSubTheme {
                TranslationIssueButton(
                    TranslationIssue.OnDeviceFallback("Google Translate refused the request (HTTP 429)."),
                    onTryGoogleAgain = { triedGoogle++ },
                    onRetryTranslation = { retried++ },
                )
            }
        }
        composeRule.onNodeWithTag("translation_issue_details").assertDoesNotExist()
        composeRule.onNodeWithTag("translation_issue_button").performClick()
        composeRule.onNodeWithText("Using on-device translation").assertIsDisplayed()
        composeRule.onNodeWithText("Google Translate refused the request (HTTP 429).").assertIsDisplayed()
        composeRule.onNodeWithText("Try Google again").performClick()
        assertEquals(1, triedGoogle)
        assertEquals(0, retried)
        composeRule.onNodeWithTag("translation_issue_details").assertDoesNotExist()
    }

    @Test
    fun unavailableIconShowsTheReasonAndRetries() {
        var triedGoogle = 0
        var retried = 0
        composeRule.setContent {
            DualSubTheme {
                TranslationIssueButton(
                    TranslationIssue.Unavailable("The translation model download took too long."),
                    onTryGoogleAgain = { triedGoogle++ },
                    onRetryTranslation = { retried++ },
                )
            }
        }
        composeRule.onNodeWithTag("translation_issue_button").performClick()
        composeRule.onNodeWithText("Translation unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("The translation model download took too long.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry translation").performClick()
        assertEquals(0, triedGoogle)
        assertEquals(1, retried)
    }

    @Test
    fun noIconWhileTranslationWorks() {
        composeRule.setContent {
            DualSubTheme {
                TranslationIssueButton(null, onTryGoogleAgain = {}, onRetryTranslation = {})
            }
        }
        composeRule.onNodeWithTag("translation_issue_button").assertDoesNotExist()
    }
}
