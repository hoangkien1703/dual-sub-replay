package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.GermanMorphology
import com.kienhoang.dualsubreplay.data.LearnerData
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The German Original row, read from the text Compose really laid out, with the bundled model. */
class GermanPosColorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sentence = "Der Mann liest ein interessantes Buch und trinkt Kaffee."

    @Before
    fun setUp() {
        LearnerData.useAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertTrue(GermanMorphology.awaitReady())
    }

    private fun show(language: String?) {
        composeRule.setContent {
            DualSubTheme {
                CompactSubtitleCard(
                    segment = SubtitleSegment(1, 1_000, 2_000, sentence),
                    active = true,
                    fontScale = 1f,
                    onReplay = {},
                    wordLearningEnabled = true,
                    resolvedSourceLanguage = language,
                )
            }
        }
    }

    private fun renderedColors(): Map<String, Color> {
        composeRule.waitForIdle()
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onAllNodesWithText(sentence)
            .onFirst()
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        val text = results.first().layoutInput.text
        return text.spanStyles
            .filter { it.item.color != Color.Unspecified }
            .associate { sentence.substring(it.start, it.end) to it.item.color }
    }

    @Test fun germanWordsGetTheirGrammaticalColors() {
        show("de")
        val colors = renderedColors()
        assertEquals(partOfSpeechColor(PartOfSpeech.NOUN, false), colors["Mann"])
        assertEquals(partOfSpeechColor(PartOfSpeech.NOUN, false), colors["Kaffee"])
        assertEquals(partOfSpeechColor(PartOfSpeech.VERB, false), colors["liest"])
        assertEquals(partOfSpeechColor(PartOfSpeech.VERB, false), colors["trinkt"])
        assertEquals(partOfSpeechColor(PartOfSpeech.ADJECTIVE, false), colors["interessantes"])
        assertEquals(partOfSpeechColor(PartOfSpeech.CONJUNCTION, false), colors["und"])
        assertTrue(colors.values.toSet().size >= 4)
        saveUiEvidence("pos_german_de", composeRule.onRoot().captureToImage())
    }

    @Test fun austrianGermanUsesTheSameModel() {
        show("de-AT")
        assertEquals(partOfSpeechColor(PartOfSpeech.ADJECTIVE, false), renderedColors()["interessantes"])
        saveUiEvidence("pos_german_de_at", composeRule.onRoot().captureToImage())
    }
}
