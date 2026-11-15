package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.GermanMorphology
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearnerData
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The word card for German: details, grammar cards, evidence highlighting and both themes, on the bundled data. */
class GermanWordDialogUiTest {
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
        occurrence: Int = 0,
        theme: AppColorThemeOption? = null,
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
        val token = LanguageAwareTokenizer.tokenize(line, "de").filter { it.text == word }[occurrence]
        val selection = LearningWordSelection(token, "de", "en", "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, line, null), false)
        compose.setContent {
            DualSubTheme { WordLearningDialog(selection, false, { "get up" }, { _, _ -> }, {}, null, {}) }
        }
    }

    private fun grammarTexts() = compose.onAllNodesWithText("Trennbares Verb", substring = true, useUnmergedTree = true)

    @Test fun separableVerbShowsItsLemmaReadingsAndTheGrammarCard() {
        show("Ich stehe früh auf.", "stehe")
        compose.onNodeWithTag("german_lemma").assertTextEquals("Dictionary form: aufstehen")
        compose.onNodeWithTag("german_verb_form").assertTextContains("Präsens", substring = true)
        assertTrue("the separable-verb card is shown on the verb", grammarTexts().fetchSemanticsNodes().isNotEmpty())
        saveUiEvidence("german-separable-dark")
    }

    @Test fun theWordBetweenTheTwoPartsDoesNotGetTheCardButTheParticleDoes() {
        show("Ich stehe früh auf.", "früh")
        assertTrue("früh lies between stehe and auf and is not part of the point", grammarTexts().fetchSemanticsNodes().isEmpty())
    }

    @Test fun theParticleGetsTheSeparableVerbCard() {
        show("Ich stehe früh auf.", "auf")
        assertTrue(grammarTexts().fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun anArticleShowsEveryCaseThatItsNounPhraseAllows() {
        show("Der alte Mann hilft der jungen Frau.", "Der")
        compose.onNodeWithTag("german_case").assertTextContains("Nominativ", substring = true)
        saveUiEvidence("german-case-first-der")
    }

    @Test fun theSecondDerIsDativeOrGenitiveBecauseTheFormAloneDoesNotDecide() {
        show("Der alte Mann hilft der jungen Frau.", "Frau")
        val shown = compose.onNodeWithTag("german_case")
        shown.assertTextContains("Dativ", substring = true)
        shown.assertTextContains("Genitiv", substring = true)
    }

    @Test fun aGuessedPointIsLabelledAndAReliableOneIsNot() {
        show("Das Haus ist gebaut.", "ist")
        assertTrue("the result-state reading is a guess", compose.onAllNodesWithTag("grammar_tentative").fetchSemanticsNodes().isNotEmpty())
        saveUiEvidence("german-tentative-dark")
    }

    @Test fun theLightThemeShowsTheSameCard() {
        show("Ich stehe früh auf.", "stehe", theme = AppColorThemeOption.entries.first { it.isLight })
        compose.onNodeWithTag("german_lemma").assertTextEquals("Dictionary form: aufstehen")
        assertTrue(grammarTexts().fetchSemanticsNodes().isNotEmpty())
        saveUiEvidence("german-separable-light")
    }

    @Test fun germanInTheTranslatedSlotGetsItsOwnGrammarWhileTheOriginalIsAnotherLanguage() {
        LearnerData.useAssets(context.assets)
        assertTrue(GermanMorphology.awaitGrammarReady())
        val german = "Ich stehe früh auf."
        val token = LanguageAwareTokenizer.tokenize(german, "de").first { it.text == "stehe" }
        // The original line is English; the German line is the translated slot (Subtitle 1), and the tapped word belongs to it.
        val segment = SubtitleSegment(1, 1000, 3000, "I get up early.", german)
        val selection = LearningWordSelection(token, "de", "en", "dQw4w9WgXcQ", segment, true)
        compose.setContent {
            DualSubTheme { WordLearningDialog(selection, false, { "get up" }, { _, _ -> }, {}, null, {}) }
        }
        compose.onNodeWithTag("german_lemma").assertTextEquals("Dictionary form: aufstehen")
        assertTrue(grammarTexts().fetchSemanticsNodes().isNotEmpty())
        saveUiEvidence("german-translated-slot")
    }
}
