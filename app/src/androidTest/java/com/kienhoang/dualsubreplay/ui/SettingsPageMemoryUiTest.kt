package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsPageMemoryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun closeDialogs() {
        closeSystemDialogs()
    }

    private var settingsOpen by mutableStateOf(true)

    private fun showSettings(
        memory: SettingsPageMemory,
        jumpToSection: MoreSettingsSection? = null,
    ) {
        composeRule.setContent {
            DualSubTheme {
                if (settingsOpen) {
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
                        pageMemory = memory,
                        jumpToSection = jumpToSection,
                        onDismiss = { settingsOpen = false },
                    )
                }
            }
        }
    }

    @Test
    fun reopeningTheSettingsPageKeepsTheOpenSectionAndScrollPosition() {
        showSettings(SettingsPageMemory())
        composeRule.onNodeWithTag("settings_section_layout").performScrollTo().performClick()
        composeRule.onNodeWithTag("portrait_panel_position_slider").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("close_settings").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { settingsOpen = true }

        // No scrolling this time: the page reopens where it was.
        composeRule.onNodeWithTag("portrait_panel_position_slider").assertIsDisplayed()
        saveUiEvidence("settings-reopened-at-layout")
    }

    @Test
    fun aLongPressShortcutOpensTheLayoutSection() {
        showSettings(SettingsPageMemory(), jumpToSection = MoreSettingsSection.LAYOUT)

        composeRule.onNodeWithTag("portrait_panel_position_slider").assertIsDisplayed()
    }

    @Test
    fun tappingTheEyeKeepsThePageSeeThroughUntilBackToSettings() {
        showSettings(SettingsPageMemory())

        composeRule.onNodeWithTag("settings_peek").performClick()
        composeRule.onNodeWithTag("settings_peek_back").assertIsDisplayed()
        saveUiEvidence("settings-peek")

        composeRule.onNodeWithTag("settings_peek_back").performClick()
        composeRule.onNodeWithTag("settings_peek_back").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(true, settingsOpen) }
    }

    @Test
    fun holdingTheTextSizeSliderShowsOnlyThatSliderOnACard() {
        showSettings(SettingsPageMemory())

        composeRule.onNodeWithTag("font_scale_slider").performScrollTo().performTouchInput { down(center) }
        composeRule.waitForIdle()
        saveUiEvidence("settings-held-slider")
        composeRule.onNodeWithTag("held_slider_card").assertIsDisplayed()

        composeRule.onNodeWithTag("font_scale_slider").performTouchInput { up() }
        composeRule.onNodeWithTag("held_slider_card").assertDoesNotExist()
    }

    @Test
    fun theSubtitlePanelGearOpensLanguagesOnTapAndLayoutOnLongPress() {
        var taps = 0
        var longPresses = 0
        composeRule.setContent {
            DualSubTheme { PanelSettingsButton(onClick = { taps++ }, onLongClick = { longPresses++ }, size = 48.dp, iconSize = 24.dp) }
        }
        val gear =
            composeRule.onNodeWithContentDescription(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.player_subtitle_settings),
            )

        gear.performClick()
        gear.performTouchInput { longClick() }

        composeRule.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, longPresses)
        }
    }
}
