package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    fun learningLayoutKeepsFullWidthSixteenByNinePlayerAboveContent() {
        composeRule.setContent {
            Box(Modifier.width(400.dp).height(700.dp).testTag("learning_root")) {
                LearningContentLayout(
                    player = { Box(Modifier.fillMaxSize().testTag("fake_player")) },
                ) {
                    Box(Modifier.weight(1f).testTag("fake_subtitles"))
                }
            }
        }

        val root = composeRule.onNodeWithTag("learning_root").fetchSemanticsNode().boundsInRoot
        val player = composeRule.onNodeWithTag("learning_player").fetchSemanticsNode().boundsInRoot
        val subtitles = composeRule.onNodeWithTag("fake_subtitles").fetchSemanticsNode().boundsInRoot

        assertEquals(root.width, player.width, 1f)
        assertEquals(16f / 9f, player.width / player.height, 0.01f)
        assertTrue(subtitles.top >= player.bottom)
    }

    @Test
    fun settingsKeepsLanguageAndTextOptionsWithoutFocus() {
        var selectedLanguage: String? = null
        var dismissed = false
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    fontScale = 1f,
                    onSourceChange = { selectedLanguage = it },
                    onFontScaleChange = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Original language").assertIsDisplayed()
        composeRule.onNodeWithText("Translation: Vietnamese").assertIsDisplayed()
        composeRule.onNodeWithText("Focus").assertDoesNotExist()
        composeRule.onNodeWithText("Japanese").performClick()
        composeRule.runOnIdle { assertEquals("ja", selectedLanguage) }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
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

        composeRule.onNodeWithText("YouTube unavailable").assertIsDisplayed()
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
        composeRule.onNodeWithText("Bản dịch").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(replayed) }
    }

    @Test
    fun inactiveSubtitleSupportsLargeTextAndVietnamese() {
        composeRule.setContent {
            DualSubTheme {
                CompactSubtitleCard(
                    segment = SubtitleSegment(2, 2_000, 3_000, "Next sentence", "Câu tiếp theo"),
                    active = false,
                    fontScale = 1.5f,
                    onReplay = {},
                )
            }
        }

        composeRule.onNodeWithText("Next sentence")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Subtitle"))
        composeRule.onNodeWithText("Câu tiếp theo").assertIsDisplayed()
    }
}
