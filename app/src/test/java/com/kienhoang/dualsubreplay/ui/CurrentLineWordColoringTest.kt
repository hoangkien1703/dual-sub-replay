package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.kienhoang.dualsubreplay.data.EnglishMorphology
import com.kienhoang.dualsubreplay.data.GermanMorphology
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SpanishMorphology
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Words of the line that is playing are coloured by part of speech, in German as in the other analyzed languages, and the
 * active spoken word keeps its colour (it only gains an underline). These call the same function the video overlay,
 * the fullscreen overlay and the transcript rows use.
 */
class CurrentLineWordColoringTest {
    private companion object {
        init {
            check(GermanMorphology.awaitReady()) { "German model did not load" }
            check(EnglishMorphology.awaitReady()) { "English model did not load" }
            check(SpanishMorphology.awaitReady()) { "Spanish model did not load" }
        }

        const val GERMAN = "Der Mann liest ein interessantes Buch und trinkt Kaffee."
    }

    private fun wordsOf(text: String): List<SubtitleWord> =
        text.split(" ").mapIndexed { i, word -> SubtitleWord(word, i * 500L, i * 500L + 450L) }

    private fun annotated(
        text: String,
        language: String?,
        active: Int = -1,
        wordLearning: Boolean = true,
    ): AnnotatedString =
        annotatedSubtitleText(
            text = text,
            words = wordsOf(text),
            activeWordIndex = active,
            baseColor = Color.White,
            highlightColor = Color.Yellow,
            wordLearningEnabled = wordLearning,
            languageCode = language,
        )

    /** The colour the part-of-speech pass gave exactly this word, or null when it was left uncoloured. */
    private fun colorOf(
        annotated: AnnotatedString,
        text: String,
        word: String,
    ): Color? {
        val start = text.indexOf(word)
        return annotated.spanStyles
            .filter { it.start == start && it.end == start + word.length && it.item.color != Color.Unspecified }
            .lastOrNull()
            ?.item
            ?.color
    }

    private fun pos(partOfSpeech: PartOfSpeech) = partOfSpeechColor(partOfSpeech, false)

    @Test fun germanWordsOfThePlayingLineGetTheirPartOfSpeechColors() {
        val a = annotated(GERMAN, "de")
        assertEquals(pos(PartOfSpeech.NOUN), colorOf(a, GERMAN, "Mann"))
        assertEquals(pos(PartOfSpeech.NOUN), colorOf(a, GERMAN, "Kaffee"))
        assertEquals(pos(PartOfSpeech.VERB), colorOf(a, GERMAN, "liest"))
        assertEquals(pos(PartOfSpeech.ADJECTIVE), colorOf(a, GERMAN, "interessantes"))
        assertEquals(pos(PartOfSpeech.CONJUNCTION), colorOf(a, GERMAN, "und"))
    }

    @Test fun theActiveSpokenWordKeepsItsColorAndOnlyGainsAnUnderline() {
        val index = GERMAN.split(" ").indexOf("liest")
        val a = annotated(GERMAN, "de", active = index)
        val start = GERMAN.indexOf("liest")
        // the part-of-speech color is still on the word
        assertEquals(pos(PartOfSpeech.VERB), colorOf(a, GERMAN, "liest"))
        // the active word has an underline, no background and no bold; its neighbours have no underline
        val onActive = a.spanStyles.filter { it.start == start && it.item.textDecoration == TextDecoration.Underline }
        assertTrue("active word is underlined", onActive.isNotEmpty())
        assertEquals(Color.Unspecified, onActive.last().item.background)
        assertEquals(null, onActive.last().item.fontWeight)
        val other = GERMAN.indexOf("Mann")
        assertTrue(a.spanStyles.none { it.start == other && it.item.textDecoration == TextDecoration.Underline })
    }

    // With "Color only active line" on, the card passes wordLearningEnabled = false for every line but the active one
    // (SubtitleCard: shouldHighlightPos = wordLearningEnabled && isSentenceEligibleForPos && ...), so this is that line.
    @Test fun withWordColoringOffTheLineIsNotColoredByPartOfSpeech() {
        val a = annotated(GERMAN, "de", wordLearning = false)
        assertNull(colorOf(a, GERMAN, "Mann"))
        assertNull(colorOf(a, GERMAN, "liest"))
    }

    @Test fun austrianGermanUsesTheSameColors() {
        assertEquals(pos(PartOfSpeech.VERB), colorOf(annotated(GERMAN, "de-AT"), GERMAN, "liest"))
    }

    @Test fun englishAndSpanishLinesAreStillColoredNextToGerman() {
        val english = "The man reads an interesting book."
        val en = annotated(english, "en")
        assertEquals(pos(PartOfSpeech.NOUN), colorOf(en, english, "man"))
        assertEquals(pos(PartOfSpeech.VERB), colorOf(en, english, "reads"))
        val spanish = "El hombre lee un libro interesante."
        val es = annotated(spanish, "es")
        assertEquals(pos(PartOfSpeech.NOUN), colorOf(es, spanish, "hombre"))
        assertEquals(pos(PartOfSpeech.VERB), colorOf(es, spanish, "lee"))
    }
}
