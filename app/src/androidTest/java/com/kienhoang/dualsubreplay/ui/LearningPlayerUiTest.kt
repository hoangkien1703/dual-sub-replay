package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LearningPlayerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unifiedSubtitleSettingsOffersViewAndOverlayBehavior() {
        var selected: PlayerExperienceMode? = null
        val portraitPanelPosition = mutableFloatStateOf(DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION)
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = listOf(CaptionLanguage("en", "English")),
                    fontScale = 1f,
                    portraitPanelOffsetFraction = portraitPanelPosition.floatValue,
                    onPortraitPanelOffsetFractionChange = { portraitPanelPosition.floatValue = it },
                    onResetPortraitPanelPosition = {
                        portraitPanelPosition.floatValue = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION
                    },
                    landscapeSplitEnabled = true,
                    playerMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                    onSourceChange = {},
                    onTargetChange = {},
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onPlayerModeChange = { selected = it },
                    onResetSettings = {
                        portraitPanelPosition.floatValue = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION
                    },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Dual-subtitle settings").assertIsDisplayed()
        composeRule.onNodeWithText("Default view").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Transcript panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Scroll-friendly overlay").performScrollTo().assertIsDisplayed()

        // Spoken-word highlighting stays in the always-visible Reading group.
        composeRule.onNodeWithTag("word_highlight_switch").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("player_mode_scroll_friendly_overlay")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY, selected)
        }

        // Advanced controls live in collapsible sections under More settings.
        composeRule.onNodeWithText("More settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("portrait_panel_position_slider").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_section_layout").performScrollTo().performClick()
        composeRule.onNodeWithText("Portrait panel position").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("portrait_panel_position_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.2f)
            }
        composeRule.onNodeWithText("Position: 20% lower").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0.2f, portraitPanelPosition.floatValue, 0f) }
        composeRule.onNodeWithTag("reset_portrait_panel_position").performScrollTo().performClick()
        composeRule.onNodeWithText("Position: 1% lower").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                portraitPanelPosition.floatValue,
                0f,
            )
        }
        composeRule.onNodeWithTag("karaoke_mode_adaptive").assertDoesNotExist()
        composeRule.onNodeWithTag("karaoke_mode_youtube_live").assertDoesNotExist()
        composeRule.onNodeWithTag("karaoke_mode_transcript").assertDoesNotExist()
        composeRule.onNodeWithTag("landscape_split_switch").performScrollTo().assertIsDisplayed()

        // Opening another section closes the one that was open.
        composeRule.onNodeWithTag("settings_section_colors").performScrollTo().performClick()
        composeRule.onNodeWithTag("portrait_panel_position_slider").assertDoesNotExist()
        composeRule.onNodeWithTag("custom_colors_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("color_option_box_background_deep_teal")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("color_option_theme_accent_cyan")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_word_learning").performScrollTo().performClick()
        composeRule.onNodeWithTag("word_learning_mode_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_overlay").performScrollTo().performClick()
        composeRule.onNodeWithTag("auto_overlay_fullscreen_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("auto_overlay_landscape_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("auto_avoid_player_controls_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("remember_overlay_position_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("reset_overlay_position").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_translation").performScrollTo().performClick()
        composeRule.onNodeWithTag("preload_models_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reset_all_settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_reset_settings").performClick()
        composeRule.runOnIdle {
            assertEquals(
                DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                portraitPanelPosition.floatValue,
                0f,
            )
        }
    }

    @Test
    fun pronunciationVolumeIsSavedFromWordLearningSettings() {
        val preferences =
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("dual_sub_preferences", 0)
        preferences.edit().remove(PRONUNCIATION_VOLUME_PREFERENCE).commit()
        try {
            composeRule.setContent {
                DualSubTheme {
                    SubtitleSettingsDialog(
                        sourcePreference = "auto",
                        targetLanguage = "vi",
                        availableSourceLanguages = listOf(CaptionLanguage("en", "English")),
                        fontScale = 1f,
                        landscapeSplitEnabled = true,
                        playerMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                        onSourceChange = {},
                        onTargetChange = {},
                        onFontScaleChange = {},
                        onLandscapeSplitChange = {},
                        onDismiss = {},
                    )
                }
            }

            composeRule.onNodeWithTag("settings_section_word_learning").performScrollTo().performClick()
            composeRule.onNodeWithText("Pronunciation volume: 100%").performScrollTo().assertIsDisplayed()
            composeRule
                .onNodeWithTag("pronunciation_volume_slider")
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.5f) }
            composeRule.onNodeWithText("Pronunciation volume: 150%").performScrollTo().assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(1.5f, preferences.getFloat(PRONUNCIATION_VOLUME_PREFERENCE, 0f), 0f)
            }
            saveUiEvidence("pronunciation-volume-setting")
        } finally {
            preferences.edit().remove(PRONUNCIATION_VOLUME_PREFERENCE).commit()
        }
    }

    @Test
    fun learningOverlayHidesActionsUntilTapped() {
        composeRule.setContent {
            DualSubTheme {
                LearningSubtitleOverlay(
                    content = LearningOverlayContent(
                        originalText = "What will we discuss?",
                        translatedText = "Chúng ta sẽ thảo luận gì?",
                        statusText = null,
                    ),
                    fontScale = 1f,
                    onSettings = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Dual-subtitle settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Hide dual subtitles").assertDoesNotExist()
        composeRule.onNodeWithTag("learning_subtitle_overlay").performClick()
        composeRule.onNodeWithContentDescription("Dual-subtitle settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide dual subtitles").assertIsDisplayed()
    }

    @Test
    fun learningOverlayMovesDiagonallyInOneDrag() {
        val vertical = mutableFloatStateOf(0.5f)
        val horizontal = mutableFloatStateOf(0.5f)
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxSize()) {
                    LearningSubtitleOverlay(
                        content =
                            LearningOverlayContent(
                                originalText = "What will we discuss?",
                                translatedText = "Chúng ta sẽ thảo luận gì?",
                                statusText = null,
                            ),
                        fontScale = 1f,
                        position = vertical.floatValue,
                        horizontalPosition = horizontal.floatValue,
                        isFullscreen = true,
                        modifier = Modifier.align(Alignment.Center),
                        onPositionChange = { vertical.floatValue = it },
                        onHorizontalPositionChange = { horizontal.floatValue = it },
                        onSettings = {},
                        onClose = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("learning_subtitle_overlay").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-8f, 8f)) }
            up()
        }

        composeRule.runOnIdle {
            assertTrue("moved down: ${vertical.floatValue}", vertical.floatValue > 0.5f)
            assertTrue("moved left: ${horizontal.floatValue}", horizontal.floatValue < 0.5f)
        }
    }
}
