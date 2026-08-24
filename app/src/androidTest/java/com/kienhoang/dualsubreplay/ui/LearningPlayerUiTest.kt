package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningPlayerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unifiedSubtitleSettingsOffersViewAndOverlayBehavior() {
        var selected: PlayerExperienceMode? = null
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
                    onPlayerModeChange = { selected = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Dual-subtitle settings").assertIsDisplayed()
        composeRule.onNodeWithText("Default view").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Transcript panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Scroll-friendly overlay").performScrollTo().assertIsDisplayed()

        // Spoken-word highlighting stays in the main Appearance section.
        composeRule.onNodeWithTag("word_highlight_switch").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("player_mode_scroll_friendly_overlay")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY, selected)
        }

        // Custom colors and other advanced controls live behind More settings.
        composeRule.onNodeWithTag("more_settings_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("custom_colors_switch").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("reset_all_settings").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithContentDescription("Return to transcript panel").assertDoesNotExist()
        composeRule.onNodeWithTag("learning_subtitle_overlay").performClick()
        composeRule.onNodeWithContentDescription("Dual-subtitle settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Return to transcript panel").assertIsDisplayed()
    }
}
