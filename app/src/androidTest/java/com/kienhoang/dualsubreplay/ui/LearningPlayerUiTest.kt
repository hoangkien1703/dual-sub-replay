package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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

        // The page is plain "Settings", and the X alone closes it (owner pick 2026-11-11).
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertDoesNotExist()
        // The X is in the top right corner, right of the eye (owner request 2026-11-11).
        val closeButton = composeRule.onNodeWithTag("close_settings").getBoundsInRoot()
        val eyeButton = composeRule.onNodeWithTag("settings_peek").getBoundsInRoot()
        val page = composeRule.onAllNodes(isRoot()).onLast().getBoundsInRoot()
        assertTrue("X $closeButton should be right of the eye $eyeButton", closeButton.left >= eyeButton.right)
        assertTrue("X $closeButton should end at the page's right edge $page", page.right - closeButton.right <= 8.dp)
        // Every section is closed until tapped, and its line shows the current value.
        composeRule.onNodeWithTag("word_highlight_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_section_languages").assertTextContains("Auto → Vietnamese")
        composeRule.onNodeWithTag("settings_section_reading").assertTextContains("Text size: 100% · Translation below")
        composeRule.onNodeWithTag("settings_section_layout").assertTextContains("Default view: Transcript panel")
        saveUiEvidence("settings-reading-closed")
        composeRule.onNodeWithTag("settings_section_colors").performScrollTo()
        saveUiEvidence("settings-section-order")
        // Sections sit under four group names; the old "More settings" heading is gone.
        composeRule.onNodeWithText("More settings").assertDoesNotExist()
        listOf("subtitles", "study", "video", "app").forEach { group ->
            composeRule.onNodeWithTag("settings_group_$group").performScrollTo().assertIsDisplayed()
        }
        // Backup & sharing has nothing to switch, so its line says what it does; it stays last, above Reset.
        composeRule
            .onNodeWithTag("settings_section_backup")
            .performScrollTo()
            .assertTextContains("Move to a new phone, share your setup")
        composeRule.onNodeWithTag("reset_all_settings").performScrollTo()
        saveUiEvidence("settings-app-group")

        // Subtitle text: what the words look like.
        composeRule.onNodeWithTag("settings_section_reading").performScrollTo().performClick()
        composeRule.waitForIdle()
        saveUiEvidence("settings-reading-open")
        composeRule.onNodeWithTag("word_highlight_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("caption_format_short_phrases").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("third_line_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("player_mode_transcript_panel").assertDoesNotExist()

        // Screen layout: where things sit.
        composeRule.onNodeWithTag("portrait_panel_position_slider").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_section_layout").performScrollTo().performClick()
        composeRule.onNodeWithTag("third_line_switch").assertDoesNotExist()
        composeRule.onNodeWithText("Default view").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("player_mode_transcript_panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transcript_follow_center").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("player_mode_scroll_friendly_overlay")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY, selected)
        }
        saveUiEvidence("settings-layout-open")
        composeRule.onNodeWithText("Portrait panel position").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("portrait_panel_position_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.2f)
            }
        composeRule.onNodeWithText("Position: 20% lower").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0.2f, portraitPanelPosition.floatValue, 0f) }
        composeRule.onNodeWithTag("reset_portrait_panel_position").performScrollTo().performClick()
        composeRule.onNodeWithText("Position: Fits the video").performScrollTo().assertIsDisplayed()
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
        composeRule
            .onNodeWithTag("color_option_box_background_deep_teal")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("color_option_theme_accent_cyan")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_word_learning").performScrollTo().performClick()
        composeRule.onNodeWithTag("word_learning_mode_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_section_overlay").performScrollTo().performClick()
        composeRule
            .onNodeWithTag("auto_overlay_fullscreen_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("auto_overlay_landscape_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("auto_avoid_player_controls_switch")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("remember_overlay_position_switch")
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
        preferences
            .edit()
            .remove(PRONUNCIATION_VOLUME_PREFERENCE)
            .remove(PHONE_VOICE_PREFERENCE)
            .remove(MEANING_IN_CONTEXT_PREFERENCE)
            .remove(MEANING_ORDER_PREFERENCE)
            .commit()
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
            // lion737 (#81) wanted a male voice: the phone's own voice replaces Google's online one.
            composeRule.onNodeWithTag("phone_voice_switch").performScrollTo().performClick()
            composeRule.runOnIdle { assertTrue(preferences.getBoolean(PHONE_VOICE_PREFERENCE, false)) }
            // lion737 (#81): the meaning in the sentence from the AI, off until turned on (owner, 2026-11-10).
            composeRule.onNodeWithTag("meaning_in_context_switch").performScrollTo().assertIsOff().performClick()
            composeRule.runOnIdle { assertTrue(preferences.getBoolean(MEANING_IN_CONTEXT_PREFERENCE, false)) }
            // Show first appears once it is on (lion737 asked for the order three times; owner pick 2026-11-11).
            composeRule.onNodeWithTag("meaning_order_sentence").performScrollTo().assertIsSelected()
            // The chip row sits at the bottom edge behind the navigation bar, where a touch misses it; click by its action.
            composeRule.onNodeWithTag("meaning_order_dictionary").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithTag("meaning_order_dictionary").assertIsSelected()
            composeRule.runOnIdle { assertEquals(MEANING_ORDER_DICTIONARY_FIRST, preferences.getString(MEANING_ORDER_PREFERENCE, null)) }
            saveUiEvidence("pronunciation-volume-setting")
        } finally {
            preferences
                .edit()
                .remove(PRONUNCIATION_VOLUME_PREFERENCE)
                .remove(PHONE_VOICE_PREFERENCE)
                .remove(MEANING_IN_CONTEXT_PREFERENCE)
                .remove(MEANING_ORDER_PREFERENCE)
                .commit()
        }
    }

    @Test
    fun learningOverlayHidesActionsUntilTapped() {
        composeRule.setContent {
            DualSubTheme {
                LearningSubtitleOverlay(
                    content =
                        LearningOverlayContent(
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

        // Like YouTube's captions, a short line makes a narrow box in the middle.
        val screen = composeRule.onRoot().getBoundsInRoot()
        val box = composeRule.onNodeWithTag("learning_subtitle_overlay").getBoundsInRoot()
        val boxWidth = (box.right - box.left).value
        val screenWidth = (screen.right - screen.left).value
        assertTrue("box fits its text: $boxWidth of $screenWidth", boxWidth < screenWidth * 0.8f)
        assertEquals((screen.left + screen.right).value / 2f, (box.left + box.right).value / 2f, 2f)

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
