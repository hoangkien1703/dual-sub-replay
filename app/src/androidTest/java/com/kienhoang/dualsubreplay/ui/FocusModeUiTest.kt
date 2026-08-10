package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FocusModeUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusOverlayShowsOnlyActiveDualSubtitleAndReplaysIt() {
        var replayedId: Long? = null
        val active = SubtitleSegment(1, 1_000, 2_000, "Active original", "Active translation")
        val next = SubtitleSegment(2, 2_000, 3_000, "Next original", "Next translation")

        composeRule.setContent {
            DualSubTheme {
                FocusSubtitleOverlay(
                    state = DualSubUiState(
                        videoId = "video",
                        videoDisplayMode = VideoDisplayMode.FOCUS,
                        segments = listOf(active, next),
                        currentIndex = 0,
                    ),
                    onSettings = {},
                    onReplay = { replayedId = it.id },
                )
            }
        }

        composeRule.onNodeWithText("Active original").assertIsDisplayed()
        composeRule.onNodeWithText("Active translation").assertIsDisplayed()
        composeRule.onNodeWithText("Next original").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Focus mode settings").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_subtitle_overlay").performClick()
        composeRule.runOnIdle { assertEquals(1L, replayedId) }
    }

    @Test
    fun settingsChangesViewingMode() {
        var selected = VideoDisplayMode.LEARNING
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    fontScale = 1f,
                    displayMode = selected,
                    onSourceChange = {},
                    onFontScaleChange = {},
                    onDisplayModeChange = { selected = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Viewing mode").assertIsDisplayed()
        composeRule.onNodeWithText("Focus").performClick()
        composeRule.runOnIdle { assertEquals(VideoDisplayMode.FOCUS, selected) }
    }
}
