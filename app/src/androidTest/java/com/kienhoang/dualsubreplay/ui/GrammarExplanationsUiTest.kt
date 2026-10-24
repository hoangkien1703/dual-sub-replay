package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CefrLevel
import com.kienhoang.dualsubreplay.data.GrammarMatch
import com.kienhoang.dualsubreplay.data.GrammarMeaning
import com.kienhoang.dualsubreplay.data.HskLevel
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GrammarExplanationsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = studyPreferences(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    @After
    fun openAllSections() {
        preferences.edit().apply { WORD_CARD_SECTION_KEYS.forEach(::remove) }.commit()
    }

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
                        GrammarMatch("虽然…但是 suīrán…dànshì", 0, 9, listOf(GrammarMeaning.ZH_SUIRAN), HskLevel.HSK2),
                        GrammarMatch("宁可 nìngkě", 0, 2, listOf(GrammarMeaning.ZH_NINGKE), HskLevel.HSK7_9),
                        GrammarMatch("be going to + verb", 5, 16, listOf(GrammarMeaning.EN_GOING_TO), CefrLevel.A2),
                    ),
                )
            }
        }

        compose.onNodeWithText("Verb + 了: the action is completed").assertIsDisplayed()
        compose.onNodeWithContentDescription("HSK level 1").assertIsDisplayed()
        // A long form sits above its text.
        compose.onNodeWithText("虽然…但是 / 可是…: although…, (still)…").assertIsDisplayed()
        compose.onNodeWithContentDescription("HSK level 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("HSK level 7–9").assertIsDisplayed()
        // The label only repeated the form, so it is dropped; a long form sits above its text.
        compose.onNodeWithText("A plan, or something you can see is coming (gonna in speech)").assertIsDisplayed()
        compose.onNodeWithContentDescription("CEFR level A2").assertIsDisplayed()
        saveUiEvidence("grammar-hsk-and-cefr")
    }

    @Test
    fun headingHidesThePointsAndTheChoiceIsRemembered() {
        val matches =
            listOf(
                GrammarMatch("に", 3, 4, listOf(GrammarMeaning.PLACE_EXIST)),
                GrammarMatch("〜てくれる", 6, 9, listOf(GrammarMeaning.FAVOR_RECEIVE)),
            )
        // A new key is a new word card, which reads the stored choice again.
        val card = mutableIntStateOf(0)
        compose.setContent { DualSubTheme { key(card.intValue) { GrammarPointList(matches) } } }

        compose.onAllNodesWithTag("grammar_point").assertCountEquals(2)
        compose.onNodeWithTag("grammar_section_count").assertDoesNotExist()
        compose.onNodeWithTag("grammar_section_header").performClick()

        compose.onAllNodesWithTag("grammar_point").assertCountEquals(0)
        compose.onNodeWithTag("grammar_section_count").assertTextEquals("2")
        saveUiEvidence("grammar-hidden")
        compose.runOnIdle { assertFalse(preferences.getBoolean(WORD_CARD_GRAMMAR_OPEN_PREFERENCE, true)) }

        compose.runOnIdle { card.intValue++ }
        compose.onAllNodesWithTag("grammar_point").assertCountEquals(0)
        compose.onNodeWithTag("grammar_section_header").performClick()
        compose.onAllNodesWithTag("grammar_point").assertCountEquals(2)
        compose.runOnIdle { assertTrue(preferences.getBoolean(WORD_CARD_GRAMMAR_OPEN_PREFERENCE, false)) }
    }

    @Test
    fun noGrammarShowsNothing() {
        compose.setContent { DualSubTheme { GrammarPointList(emptyList()) } }

        compose.onAllNodesWithTag("grammar_section").assertCountEquals(0)
    }
}
