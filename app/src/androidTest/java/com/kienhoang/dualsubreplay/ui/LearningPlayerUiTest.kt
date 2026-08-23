package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningPlayerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subtitleSettingsOffersScrollFriendlyBrowsingMode() {
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

        composeRule.onNodeWithText("Player mode").assertIsDisplayed()
        composeRule.onNodeWithText("Transcript panel").assertIsDisplayed()
        composeRule.onNodeWithText("Scroll-friendly overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("player_mode_scroll_friendly_overlay").performClick()
        composeRule.runOnIdle {
            assertEquals(PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY, selected)
        }
    }

    @Test
    fun learningOverlayHidesControlsUntilTapped() {
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
