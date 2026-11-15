package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the bundled German model (`de-pos.zip`), not stand-in tags. */
class GermanMorphologyTest {
    private companion object {
        val tagger by lazy { LearnerData.open("de-pos.zip").use(OpenNlpPosTagger::load) }
    }

    private fun words(line: String) = germanWords(tagger, line)

    private fun partOfSpeech(
        line: String,
        word: String,
    ) = words(line).first { it.text == word }.partOfSpeech

    @Test fun everyCategoryIsTaggedInContext() {
        val line = "Ich gehe mit dem Mann in das große Haus, weil er ein schönes Buch liest."
        val expected =
            mapOf(
                "Ich" to PartOfSpeech.PRONOUN,
                "gehe" to PartOfSpeech.VERB,
                "mit" to PartOfSpeech.PREPOSITION,
                "dem" to PartOfSpeech.PARTICLE,
                "Mann" to PartOfSpeech.NOUN,
                "große" to PartOfSpeech.ADJECTIVE,
                "Haus" to PartOfSpeech.NOUN,
                "weil" to PartOfSpeech.CONJUNCTION,
                "er" to PartOfSpeech.PRONOUN,
                "ein" to PartOfSpeech.PARTICLE,
                "schönes" to PartOfSpeech.ADJECTIVE,
                "Buch" to PartOfSpeech.NOUN,
                "liest" to PartOfSpeech.VERB,
            )
        assertEquals(expected, words(line).filter { it.text in expected }.associate { it.text to it.partOfSpeech })
        assertEquals(PartOfSpeech.ADVERB, partOfSpeech("Das Essen schmeckt gut, aber wir müssen auch arbeiten.", "auch"))
        assertEquals(PartOfSpeech.CONJUNCTION, partOfSpeech("Das Essen schmeckt gut, aber wir müssen auch arbeiten.", "aber"))
    }

    @Test fun theSameSpellingIsTaggedByItsContext() {
        val line = "Wir essen das Essen."
        assertEquals(PartOfSpeech.VERB, partOfSpeech(line, "essen"))
        assertEquals(PartOfSpeech.NOUN, partOfSpeech(line, "Essen"))
        val clause = "Ich weiß, dass das Haus groß ist."
        assertEquals(PartOfSpeech.CONJUNCTION, partOfSpeech(clause, "dass"))
        assertEquals(PartOfSpeech.PARTICLE, partOfSpeech(clause, "das"))
    }

    @Test fun umlautsEszettAndCapitalsKeepExactOffsets() {
        val line = "Die Größe des Fußballs ist wichtig."
        val tokens = words(line)
        assertEquals(listOf("Die", "Größe", "des", "Fußballs", "ist", "wichtig"), tokens.map { it.text })
        tokens.forEach { assertEquals(it.text, line.substring(it.startIndex, it.endIndex)) }
        assertEquals(PartOfSpeech.NOUN, tokens.first { it.text == "Größe" }.partOfSpeech)
        assertEquals(PartOfSpeech.NOUN, tokens.first { it.text == "Fußballs" }.partOfSpeech)
        assertEquals(PartOfSpeech.ADJECTIVE, tokens.first { it.text == "wichtig" }.partOfSpeech)
    }

    @Test fun hyphenatedCompoundsStayOneWordAndPunctuationIsNotAWord() {
        val line = "Schick mir die E-Mail, bitte."
        val tokens = words(line)
        assertEquals(listOf("Schick", "mir", "die", "E-Mail", "bitte"), tokens.map { it.text })
        tokens.forEach { assertEquals(it.text, line.substring(it.startIndex, it.endIndex)) }
    }

    @Test fun apostropheWordsStayOneSelectableWord() {
        listOf("Geht's dir gut?" to "Geht's", "Wir hören Rock'n'Roll heute." to "Rock'n'Roll", "Das ist O’Neils Haus." to "O’Neils")
            .forEach { (line, word) ->
                val tokens = words(line)
                assertTrue("$word in ${tokens.map { it.text }}", word in tokens.map { it.text })
                tokens.forEach { assertEquals(it.text, line.substring(it.startIndex, it.endIndex)) }
            }
    }

    /** Selection takes its words from the tokenizer; German must cut them where the spaced-language tokenizer (no language) does. */
    @Test fun germanWordBoundariesEqualTheSpacedTokenizers() {
        listOf(
            "Geht's dir gut?",
            "Wir hören Rock'n'Roll heute.",
            "Schick mir die E-Mail, bitte.",
            "Die Größe des Fußballs ist wichtig.",
            "Auf- und Abbau, 3,5 Prozent -- fertig!",
        ).forEach { line ->
            val spaced = LanguageAwareTokenizer.tokenize(line, null).map { Triple(it.text, it.startIndex, it.endIndex) }
            assertEquals(line, spaced, words(line).map { Triple(it.text, it.startIndex, it.endIndex) })
        }
    }

    /** Tags from Apache OpenNLP 2.5.4's own `POSTaggerME` on the same model and tokens (beam 3). */
    @Test fun tagsMatchApacheOpenNlp() {
        val reference =
            mapOf(
                "Ich gehe mit dem Mann in das große Haus , weil er ein schönes Buch liest ." to
                    "PRON VERB ADP DET NOUN ADP DET ADJ NOUN PUNCT SCONJ PRON DET ADJ NOUN VERB PUNCT",
                "Das Essen schmeckt gut , aber wir müssen auch arbeiten ." to
                    "DET NOUN VERB ADJ PUNCT CCONJ PRON AUX ADV VERB PUNCT",
                "Er liest die Zeitung . Sie liest gern ." to
                    "PRON VERB DET NOUN PUNCT PRON ADV ADV PUNCT",
                "Die Mädchen aus Österreich spielen draußen , doch die Straße ist nass ." to
                    "DET NOUN ADP PROPN VERB ADV PUNCT ADV DET NOUN AUX SCONJ PUNCT",
            )
        reference.forEach { (line, tags) -> assertEquals(line, tags.split(' '), tagger.tag(line.split(' '))) }
    }

    @Test fun universalTagsMapOntoTheExistingCategories() {
        val expected =
            mapOf(
                "NOUN" to PartOfSpeech.NOUN,
                "PROPN" to PartOfSpeech.NOUN,
                "VERB" to PartOfSpeech.VERB,
                "AUX" to PartOfSpeech.VERB,
                "ADJ" to PartOfSpeech.ADJECTIVE,
                "ADV" to PartOfSpeech.ADVERB,
                "PRON" to PartOfSpeech.PRONOUN,
                "ADP" to PartOfSpeech.PREPOSITION,
                "CCONJ" to PartOfSpeech.CONJUNCTION,
                "SCONJ" to PartOfSpeech.CONJUNCTION,
                "DET" to PartOfSpeech.PARTICLE,
                "PART" to PartOfSpeech.PARTICLE,
                "NUM" to PartOfSpeech.OTHER,
                "X" to PartOfSpeech.OTHER,
            )
        expected.forEach { (tag, part) -> assertEquals(tag, part, germanPartOfSpeech(tag)) }
    }

    @Test fun onlyGermanLanguageCodesSelectTheGermanAnalyzer() {
        listOf("de", "DE", "de-DE", "de-AT", "de-CH", "de_CH").forEach { assertTrue(it, LanguageAwareTokenizer.isGermanCode(it)) }
        listOf(null, "", "auto", "en", "nl", "den", "deu", "dev").forEach { assertFalse("$it", LanguageAwareTokenizer.isGermanCode(it)) }
    }

    @Test fun theTokenizerUsesTheModelForGermanVariantsOnly() {
        assertTrue(GermanMorphology.awaitReady())
        assertTrue(GermanMorphology.revision.value >= 1)
        val line = "Der Mann liest ein interessantes Buch und trinkt Kaffee."
        val expected = words(line)
        listOf("de", "de-AT", "de_CH").forEach { assertEquals(it, expected, LanguageAwareTokenizer.tokenize(line, it)) }
        assertTrue(expected.any { it.partOfSpeech == PartOfSpeech.ADJECTIVE })
        // An unknown or Auto language is never guessed to be German.
        assertFalse(expected == LanguageAwareTokenizer.tokenize(line, null))
        assertFalse(expected == LanguageAwareTokenizer.tokenize(line, "auto"))
    }
}
