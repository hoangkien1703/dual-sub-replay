package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningPlayerUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun playerModeDialogOffersScrollFriendlyBrowsingMode() {
        var selected: PlayerExperienceMode? = null
        composeRule.setContent {
            DualSubTheme {
                PlayerModeDialog(
                    selectedMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                    onModeChange = { selected = it },
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
}
