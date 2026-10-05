package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @Test
    fun googleSwitchIsOffByDefaultAndChoosesTheEngine() {
        val chosen = mutableListOf<TranslationEngine>()
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalTranslationEngineActions provides TranslationEngineActions { chosen += it }) {
                    SubtitleSettingsDialog(
                        sourcePreference = "ja",
                        targetLanguage = "en",
                        availableSourceLanguages = emptyList(),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        onlineTranslationAvailable = true,
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
        composeRule
            .onNodeWithTag("google_translate_switch")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(listOf(TranslationEngine.GOOGLE_WEB), chosen)
    }

    @Test
    fun googleSwitchIsHiddenWhenTheBuildCannotTranslateOnline() {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalTranslationEngineActions provides TranslationEngineActions {}) {
                    SubtitleSettingsDialog(
                        sourcePreference = "ja",
                        targetLanguage = "en",
                        availableSourceLanguages = emptyList(),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        onlineTranslationAvailable = false,
                        translationEngine = TranslationEngine.GOOGLE_WEB,
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
        composeRule.onNodeWithTag("google_translate_switch").assertDoesNotExist()
    }

    @Test
    fun googleSwitchShowsTheCurrentChoice() {
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalTranslationEngineActions provides TranslationEngineActions {}) {
                    SubtitleSettingsDialog(
                        sourcePreference = "ja",
                        targetLanguage = "en",
                        availableSourceLanguages = emptyList(),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        onlineTranslationAvailable = true,
                        translationEngine = TranslationEngine.GOOGLE_WEB,
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
        composeRule.onNodeWithTag("google_translate_switch").performScrollTo().assertIsOn()
    }

    @Test
    fun failureDialogOffersBothChoices() {
        var usedOnDevice = 0
        var keptGoogle = 0
        composeRule.setContent {
            DualSubTheme {
                OnlineTranslationFailedDialog(onUseOnDevice = { usedOnDevice++ }, onKeepGoogle = { keptGoogle++ })
            }
        }
        composeRule.onNodeWithTag("keep_google_translate").performClick()
        composeRule.onNodeWithTag("use_on_device_translation").performClick()
        assertEquals(1, keptGoogle)
        assertEquals(1, usedOnDevice)
    }

    @Test
    fun unavailableBarOffersOnDeviceOnlyAfterAGoogleFailure() {
        var usedOnDevice = 0
        composeRule.setContent {
            DualSubTheme {
                TranslationUnavailableBar("Google Translate stopped working", onRetry = {}, onUseOnDevice = { usedOnDevice++ })
            }
        }
        composeRule.onNodeWithTag("bar_use_on_device_translation").performClick()
        assertEquals(1, usedOnDevice)
    }

    @Test
    fun unavailableBarHasNoSwitchBackForOnDeviceFailures() {
        composeRule.setContent {
            DualSubTheme {
                TranslationUnavailableBar("Translation is unavailable right now.", onRetry = {})
            }
        }
        composeRule.onNodeWithTag("bar_use_on_device_translation").assertDoesNotExist()
    }
}
