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
        composeRule.onNodeWithTag("auto_switch_on_device_switch").assertDoesNotExist()
    }

    @Test
    fun autoSwitchIsOffByDefaultAndCanBeTurnedOn() {
        val autoSwitch = mutableListOf<Boolean>()
        showTranslationSettings(
            OnlineTranslationSettings(available = true, engine = TranslationEngine.GOOGLE_WEB),
            TranslationEngineActions(select = {}, setAutoSwitchToOnDevice = { autoSwitch += it }),
        )
        composeRule
            .onNodeWithTag("auto_switch_on_device_switch")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(listOf(true), autoSwitch)
    }

    @Test
    fun autoSwitchIsHiddenWhileTranslatingOnDevice() {
        showTranslationSettings(OnlineTranslationSettings(available = true, engine = TranslationEngine.ON_DEVICE))
        composeRule.onNodeWithTag("auto_switch_on_device_switch").assertDoesNotExist()
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
