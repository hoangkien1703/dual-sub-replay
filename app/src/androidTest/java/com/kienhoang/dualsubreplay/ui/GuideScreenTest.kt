package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GuideScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstPageShowsContentWithSkipOption() {
        composeRule.setContent {
            DualSubTheme { GuideScreen(onFinish = {}) }
        }

        composeRule.onNodeWithTag("guide_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Dual subtitles while you watch").assertIsDisplayed()
        composeRule.onNodeWithTag("guide_skip").assertIsDisplayed()
        composeRule.onNodeWithTag("guide_next").assertIsDisplayed()
    }

    @Test
    fun skipFinishesFromFirstPage() {
        var finished = false
        composeRule.setContent {
            DualSubTheme { GuideScreen(onFinish = { finished = true }) }
        }

        composeRule.onNodeWithTag("guide_skip").performClick()
        composeRule.runOnIdle { assertTrue(finished) }
    }

    @Test
    fun nextAdvancesThroughPagesAndDoneFinishes() {
        var finished = false
        composeRule.setContent {
            DualSubTheme { GuideScreen(onFinish = { finished = true }) }
        }

        composeRule.onNodeWithTag("guide_next").performClick()
        composeRule.onNodeWithText("Replay any line instantly").assertIsDisplayed()

        composeRule.onNodeWithTag("guide_next").performClick()
        composeRule.onNodeWithText("Open any video").assertIsDisplayed()
        composeRule.onNodeWithTag("guide_done").performClick()

        composeRule.runOnIdle { assertTrue(finished) }
    }
}
