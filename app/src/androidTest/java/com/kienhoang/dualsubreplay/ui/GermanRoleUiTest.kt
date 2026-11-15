package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.GermanMorphology
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearnerData
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** What the German word card says about the tapped word's job in its sentence, and the verb tables, on the bundled data. */
class GermanRoleUiTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun reset() {
        val editor = studyPreferences(context).edit()
        WORD_CARD_SECTION_KEYS.forEach(editor::remove)
        editor.commit()
        context
            .getSharedPreferences("dual_sub_preferences", 0)
            .edit()
            .remove(APP_COLOR_THEME_PREFERENCE)
            .commit()
    }

    private fun show(
        line: String,
        word: String,
        theme: AppColorThemeOption? = null,
        translated: Boolean = false,
    ) {
        LearnerData.useAssets(context.assets)
        assertTrue(GermanMorphology.awaitGrammarReady())
        theme?.let {
            context
                .getSharedPreferences("dual_sub_preferences", 0)
                .edit()
                .putString(APP_COLOR_THEME_PREFERENCE, it.key)
                .commit()
        }
        val token = LanguageAwareTokenizer.tokenize(line, "de").first { it.text == word }
        val segment =
            if (translated) {
                SubtitleSegment(
                    1,
                    1000,
                    3000,
                    "English original.",
                    line,
                )
            } else {
                SubtitleSegment(1, 1000, 3000, line, null)
            }
        val selection = LearningWordSelection(token, "de", "en", "dQw4w9WgXcQ", segment, translated)
        compose.setContent { DualSubTheme { WordLearningDialog(selection, false, { "meaning" }, { _, _, _ -> }, {}, null, {}) } }
    }

    private fun title() = compose.onNodeWithTag("german_role_title", useUnmergedTree = true)

    private fun openConjugation() = compose.onNodeWithTag("german_conjugation_header", useUnmergedTree = true).performClick()

    @Test fun anAdjectiveExplainsItsEndingAndTheNounItBelongsTo() {
        show("Ich trinke kalten Tee.", "kalten")
        title().assertTextContains("Adjective before a noun", substring = true)
        compose.onNodeWithTag("german_role_line_0", useUnmergedTree = true).assertTextContains("Tee", substring = true)
        compose.onNodeWithTag("german_role_line_0", useUnmergedTree = true).assertTextContains("-en", substring = true)
        saveUiEvidence("german-role-adjective")
    }

    @Test fun anAdverbSaysWhichKindItIsAndWhatItDoesToTheWordOrder() {
        show("Heute gehe ich nach Hause.", "Heute")
        title().assertTextContains("Adverb", substring = true)
        title().assertTextContains("time", substring = true)
        compose.onNodeWithTag("german_role_line_1", useUnmergedTree = true).assertTextContains("verb comes second", substring = true)
        saveUiEvidence("german-role-adverb")
    }

    @Test fun aPronounGetsPersonNumberCaseAndTheReasonForTheCase() {
        show("Er hilft mir.", "mir")
        title().assertTextContains("Personal pronoun", substring = true)
        title().assertTextContains("Dativ", substring = true)
        compose.onNodeWithTag("german_role_line_0", useUnmergedTree = true).assertTextContains("helfen", substring = true)
        saveUiEvidence("german-role-pronoun")
    }

    @Test fun aWordWithNoCatalogueRuleStillGetsItsExplanation() {
        // No grammar point matches a lone adverb, yet the word explanation shows.
        show("Leider bin ich krank.", "Leider")
        assertTrue(compose.onAllNodesWithTag("german_role", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("german_role_line_0", useUnmergedTree = true).assertTextContains("whole sentence", substring = true)
    }

    @Test fun anAmbiguousPronounSaysThatTheLineDoesNotDecide() {
        show("Wir sehen sie.", "sie")
        assertTrue(compose.onAllNodesWithTag("german_role_tentative", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theConjugationStartsClosedAndShowsTheFourTensesAndSixPersons() {
        show("Ich gehe nach Hause.", "gehe")
        assertTrue(
            "closed at first",
            compose.onAllNodesWithTag("german_conj_table", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        openConjugation()
        for (tense in listOf("PRESENT", "PRETERITE", "PERFECT", "PLUPERFECT")) {
            assertNotNull(compose.onNodeWithTag("german_tense_tab_$tense", useUnmergedTree = true).fetchSemanticsNode())
        }
        assertEquals(
            6,
            (0..5).count {
                compose.onAllNodesWithTag("german_conj_row_$it").fetchSemanticsNodes().isNotEmpty()
            },
        )
        compose.onNodeWithTag("german_conj_row_0").assertTextContains("gehe", substring = true)
        compose.onNodeWithTag("german_conj_row_5").assertTextContains("gehen", substring = true)
        saveUiEvidence("german-conjugation-present")
    }

    @Test fun thePastTabsShowTheRightForms() {
        show("Ich ging nach Hause.", "ging")
        openConjugation()
        // the sentence is in the Präteritum, so that tab is open and its first row is marked
        compose.onNodeWithTag("german_conj_row_0").assertTextContains("ging", substring = true)
        compose.onNodeWithTag("german_tense_tab_PERFECT", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("german_conj_row_0").assertTextContains("bin gegangen", substring = true)
        compose.onNodeWithTag("german_conj_row_2").assertTextContains("ist gegangen", substring = true)
        compose.onNodeWithTag("german_tense_tab_PLUPERFECT", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("german_conj_row_0").assertTextContains("war gegangen", substring = true)
        saveUiEvidence("german-conjugation-past")
    }

    @Test fun aParticipleOpensThePerfectTableOfItsVerb() {
        show("Ich bin nach Hause gegangen.", "gegangen")
        compose.onNodeWithTag("german_lemma", useUnmergedTree = true).assertTextContains("gehen", substring = true)
        openConjugation()
        compose.onNodeWithTag("german_conj_row_0").assertTextContains("bin gegangen", substring = true)
    }

    @Test fun aNegationAndTheInfinitiveMarkerAreExplained() {
        show("Ich weiß es nicht.", "nicht")
        title().assertTextContains("negation", substring = true)
        saveUiEvidence("german-role-negation")
    }

    @Test fun zuBeforeAnInfinitiveIsTheInfinitiveMarker() {
        show("Er hofft, dich zu sehen.", "zu")
        title().assertTextContains("Infinitive marker", substring = true)
        compose.onNodeWithTag("german_role_line_0", useUnmergedTree = true).assertTextContains("sehen", substring = true)
    }

    @Test fun anAdverbIsNeverOfferedAVerbTable() {
        // heute is also a lexicon form of the verb heuen; the card must not show a conjugation for it
        show("Ich bleibe heute zu Hause.", "heute")
        assertTrue(compose.onAllNodesWithTag("german_conjugation", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theLightThemeShowsTheSameExplanationAndTables() {
        show("Er hilft mir.", "mir", theme = AppColorThemeOption.entries.first { it.isLight })
        title().assertTextContains("Personal pronoun", substring = true)
        saveUiEvidence("german-role-light")
    }

    @Test fun theTranslatedSlotGetsTheSameExplanation() {
        show("Er hilft mir.", "mir", translated = true)
        title().assertTextContains("Personal pronoun", substring = true)
        saveUiEvidence("german-role-translated-slot")
    }

    @Test fun theBlocksFitANarrowScreenWithoutOverflowing() {
        LearnerData.useAssets(context.assets)
        assertTrue(GermanMorphology.awaitGrammarReady())
        val line = "Obwohl es heute sehr kalt war, sind wir mit unseren Freunden spazieren gegangen."
        val roleStart = line.indexOf("Obwohl")
        val role = GermanMorphology.wordRole(line, roleStart, roleStart + "Obwohl".length)!!
        val verbStart = line.indexOf("gegangen")
        val conjugation = GermanMorphology.conjugation(line, verbStart, verbStart + "gegangen".length)!!
        compose.setContent {
            DualSubTheme {
                Column(Modifier.width(280.dp)) {
                    GermanRoleSection(role, "Obwohl")
                    GermanConjugationSection(conjugation)
                }
            }
        }
        val limit = with(compose.density) { 280.dp.roundToPx() }
        for (tag in listOf("german_role", "german_conjugation")) {
            val width =
                compose
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .size.width
            assertTrue("$tag is $width px wide, more than $limit", width <= limit)
        }
    }
}
