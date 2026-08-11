package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubtitleUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsKeepsLanguageAndTextOptionsWithoutFocus() {
        var selectedLanguage: String? = null
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    fontScale = 1f,
                    onSourceChange = { selectedLanguage = it },
                    onFontScaleChange = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Original language").assertIsDisplayed()
        composeRule.onNodeWithText("Focus").assertDoesNotExist()
        composeRule.onNodeWithText("Japanese").performClick()
        composeRule.runOnIdle { assertEquals("ja", selectedLanguage) }
    }

    @Test
    fun webPageErrorOffersReload() {
        var reloaded = false
        composeRule.setContent {
            DualSubTheme {
                WebPageErrorCard(
                    message = "Renderer stopped",
                    onReload = { reloaded = true },
                )
            }
        }

        composeRule.onNodeWithText("Video page unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Reload").performClick()
        composeRule.runOnIdle { assertTrue(reloaded) }
    }

    @Test
    fun activeSubtitleIsIdentifiedAndReplays() {
        var replayed = false
        composeRule.setContent {
            DualSubTheme {
                CompactSubtitleCard(
                    segment = SubtitleSegment(1, 1_000, 2_000, "Active original", "Bản dịch"),
                    active = true,
                    fontScale = 1f,
                    onReplay = { replayed = true },
                )
            }
        }

        composeRule.onNodeWithText("Active original")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Active subtitle"))
            .performClick()
        composeRule.runOnIdle { assertTrue(replayed) }
    }
}
