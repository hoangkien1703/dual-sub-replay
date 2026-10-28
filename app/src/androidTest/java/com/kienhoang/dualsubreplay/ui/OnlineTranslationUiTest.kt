package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kienhoang.dualsubreplay.translation.GoogleWait
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator
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
    fun translatorChoiceShowsGoogleByDefaultAndPicksAiOnly() {
        val chosen = mutableListOf<SubtitleTranslator>()
        showTranslationSettings(
            OnlineTranslationSettings(engine = TranslationEngine.GOOGLE_WEB),
            TranslationEngineActions(select = {}, selectTranslator = { chosen += it }),
        )
        composeRule.onNodeWithTag("translator_google").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_translation_model_same").assertDoesNotExist()
        composeRule.onNodeWithTag("translator_ai_only").performScrollTo().performClick()
        assertEquals(listOf(SubtitleTranslator.AI_ONLY), chosen)
    }

    @Test
    fun aiChoiceShowsTheModelPickerWithTheAssistantsModelFirst() {
        val picked = mutableListOf<String>()
        showTranslationSettings(
            OnlineTranslationSettings(engine = TranslationEngine.GOOGLE_WEB, aiTranslation = true),
            TranslationEngineActions(select = {}, selectAiTranslationModel = { picked += it }),
        )
        composeRule
            .onNodeWithTag("ai_translation_model_same")
            .performScrollTo()
            .assertIsSelected()
        saveUiEvidence("settings-translate-subtitles-with-ai")
        composeRule.onNodeWithTag("google_wait_auto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_translation_model_gemini-flash-lite-latest").performScrollTo().performClick()
        assertEquals(listOf("gemini-flash-lite-latest"), picked)
    }

    @Test
    fun aiOnlyHidesWaitForGoogle() {
        val aiOnly = OnlineTranslationSettings(engine = TranslationEngine.GOOGLE_WEB, aiTranslation = true, aiTranslationOnly = true)
        showTranslationSettings(aiOnly)
        composeRule.onNodeWithTag("ai_translation_model_same").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("google_wait_auto").assertDoesNotExist()
    }

    @Test
    fun waitForGoogleShowsWhileGoogleIsOnAndPicksAWait() {
        val picked = mutableListOf<GoogleWait>()
        showTranslationSettings(
            OnlineTranslationSettings(engine = TranslationEngine.GOOGLE_WEB, googleWait = GoogleWait.AUTO),
            TranslationEngineActions(select = {}, selectGoogleWait = { picked += it }),
        )
        composeRule
            .onNodeWithTag("google_wait_auto")
            .performScrollTo()
            .assertIsSelected()
        saveUiEvidence("settings-wait-for-google")
        composeRule
            .onNodeWithTag("google_wait_8")
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        assertEquals(listOf(GoogleWait.EIGHT_SECONDS), picked)
    }

    @Test
    fun waitForGoogleIsHiddenWhileTranslatingOnTheDevice() {
        showTranslationSettings(OnlineTranslationSettings(engine = TranslationEngine.ON_DEVICE))
        composeRule.onNodeWithTag("google_wait_auto").assertDoesNotExist()
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
