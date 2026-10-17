package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kienhoang.dualsubreplay.data.CefrLevel
import com.kienhoang.dualsubreplay.data.GrammarMatch
import com.kienhoang.dualsubreplay.data.GrammarMeaning
import com.kienhoang.dualsubreplay.data.HskLevel
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
    fun showsHskAndCefrLevels() {
        compose.setContent {
            DualSubTheme {
                GrammarPointList(
                    listOf(
                        GrammarMatch("V + 了 le", 4, 5, listOf(GrammarMeaning.ZH_LE_COMPLETED), HskLevel.HSK1),
                        GrammarMatch("be going to + verb", 5, 16, listOf(GrammarMeaning.EN_GOING_TO), CefrLevel.A2),
                    ),
                )
            }
        }

        compose.onNodeWithText("Verb + 了: the action is completed").assertIsDisplayed()
        compose.onNodeWithContentDescription("HSK level 1").assertIsDisplayed()
        compose.onNodeWithText("Be going to: a plan, or something you can see is coming (gonna in speech)").assertIsDisplayed()
        compose.onNodeWithContentDescription("CEFR level A2").assertIsDisplayed()
        saveUiEvidence("grammar-hsk-and-cefr")
    }

    @Test
    fun noGrammarShowsNothing() {
        compose.setContent { DualSubTheme { GrammarPointList(emptyList()) } }

        compose.onAllNodesWithTag("grammar_section").assertCountEquals(0)
    }
}
