package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.GrammarMatch
import com.kienhoang.dualsubreplay.data.GrammarMeaning
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Rule
import org.junit.Test

class GrammarExplanationsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheLikeliestMeaningAndRevealsTheOthers() {
        compose.setContent {
            DualSubTheme {
                GrammarPointList(
                    listOf(
                        GrammarMatch("に", 3, 4, listOf(GrammarMeaning.PLACE_EXIST, GrammarMeaning.TIME, GrammarMeaning.DESTINATION)),
                        GrammarMatch("〜てくれる", 6, 9, listOf(GrammarMeaning.FAVOR_RECEIVE)),
                    ),
                )
            }
        }

        compose.onNodeWithText("Grammar").assertIsDisplayed()
        compose.onNodeWithText("In A; on A; at A (where something is)").assertIsDisplayed()
        compose.onNodeWithText("Someone does A for me (or us)").assertIsDisplayed()
        compose.onAllNodesWithTag("grammar_alternate").assertCountEquals(0)
        compose.onAllNodesWithTag("grammar_toggle_alternates").assertCountEquals(1)
        saveUiEvidence("grammar-explanations")

        compose.onNodeWithTag("grammar_toggle_alternates").performClick()

        compose.onNodeWithText("At A; on A (a point in time)").assertIsDisplayed()
        compose.onAllNodesWithTag("grammar_alternate").assertCountEquals(2)
        compose.onNodeWithText("Hide other meanings").assertIsDisplayed()
    }

    @Test
    fun noGrammarShowsNothing() {
        compose.setContent { DualSubTheme { GrammarPointList(emptyList()) } }

        compose.onAllNodesWithTag("grammar_section").assertCountEquals(0)
    }
}
