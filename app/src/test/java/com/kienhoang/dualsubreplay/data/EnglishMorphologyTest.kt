package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishMorphologyTest {
    private companion object {
        val analyzer by lazy { EnglishAnalyzer(LearnerData.open("en-pos.zip").use(EnglishPosTagger::load), loadEnglishLexicon()) }
    }

    private fun tags(line: String) = tagEnglish(analyzer.tagger, line).map { it.tag }

    private fun morphemes(line: String) = englishMorphemes(analyzer, line).filter { it.pos != "PUNCT" }

    private fun word(
        line: String,
        text: String,
    ) = englishWords(analyzer, line).first { it.text == text }

    @Test
    fun tagsLikeOpenNlp() {
        // OpenNLP 2.5.4's own output; these lines once came out differently here.
        assertEquals(listOf("PRON", "ADV", "AUX", "DET", "NOUN", "VERB", "PUNCT"), tags("What more can a woman do?"))
        assertEquals(
            listOf("PRON", "VERB", "PROPN", "AUX", "ADV", "ADJ", "ADP", "PROPN", "PUNCT"),
            tags("I think Tom is much busier than Mary."),
        )
        assertEquals(
            listOf("DET", "ADV", "VERB", "NOUN", "ADP", "PROPN", "AUX", "PROPN", "PUNCT"),
            tags("The most populated city of Europe is Istanbul."),
        )
        assertEquals(
            listOf("PRON", "VERB", "DET", "NOUN", "PRON", "PRON+AUX", "VERB", "PUNCT", "AUX", "PRON", "PUNCT"),
            tags("You have no clue what you're doing, do you?"),
        )
    }

    @Test
    fun splitsContractionsIntoTheirWords() {
        val words = morphemes("I can't go, but she's been working.")
        assertEquals(listOf("i", "ca", "n't", "go", "but", "she", "'s", "been", "working"), words.map { it.surface })
        assertEquals(listOf("i", "can", "not", "go", "but", "she", "have", "be", "work"), words.map { it.baseForm })
        assertEquals("ING", words.last().conjugationForm)
    }

    @Test
    fun keepsTheVerbAfterAHelperOrTo() {
        assertEquals("VERB", morphemes("It may rain tomorrow.").first { it.surface == "rain" }.pos)
        val goingTo = morphemes("It's going to rain.")
        assertEquals("PART", goingTo.first { it.surface == "to" }.pos)
        assertEquals("VERB", goingTo.first { it.surface == "rain" }.pos)
        // Going to a place is not the future.
        assertEquals("NOUN", morphemes("I'm going to school.").first { it.surface == "school" }.pos)
        // Word colors use the same fix.
        assertEquals(PartOfSpeech.VERB, englishWords(analyzer, "It's going to rain.").first { it.text == "rain" }.partOfSpeech)
        assertEquals(PartOfSpeech.VERB, englishWords(analyzer, "It may rain tomorrow.").first { it.text == "rain" }.partOfSpeech)
    }

    @Test
    fun findsTheDictionaryForm() {
        val lexicon = analyzer.lexicon
        listOf("studied" to "study", "making" to "make", "stopped" to "stop", "went" to "go", "invited" to "invite")
            .forEach { (form, lemma) -> assertEquals(lemma, lexicon.verbLemma(form)) }
        listOf("died" to "die", "lied" to "lie", "charged" to "charge", "hoping" to "hope", "hopping" to "hop", "singing" to "sing")
            .forEach { (form, lemma) -> assertEquals(lemma, lexicon.verbLemma(form)) }
        listOf("years" to "year", "children" to "child", "leaves" to "leaf", "boxes" to "box", "cities" to "city", "wives" to "wife")
            .forEach { (form, lemma) -> assertEquals(lemma, lexicon.nounLemma(form)) }
        // Plurals learners meet as words of their own stay as they are.
        listOf("glasses", "news", "clothes", "people").forEach { assertEquals(it, lexicon.nounLemma(it)) }
        listOf("bigger" to "big", "happiest" to "happy", "nicer" to "nice", "better" to "good", "clever" to "clever", "silver" to "silver")
            .forEach { (form, lemma) -> assertEquals(lemma, lexicon.adjectiveLemma(form)) }
        // Words the dictionary does not know still lose their endings.
        assertEquals("google", lexicon.verbLemma("googled"))
    }

    @Test
    fun wordsCarryTheirDictionaryFormAndLevel() {
        val went = word("We went home.", "went")
        assertEquals("go", went.baseForm)
        assertEquals(CefrLevel.A1, englishWordLevel(analyzer.lexicon, went))
        assertEquals("year", word("Three years ago.", "years").baseForm)
        // A word in its dictionary form has no separate base form.
        assertEquals(null, word("I like this light.", "light").baseForm)
        assertEquals(CefrLevel.A1, englishWordLevel(analyzer.lexicon, word("I like this light.", "light")))
        assertEquals(null, englishWordLevel(analyzer.lexicon, word("It's late.", "It's")))
    }

    @Test
    fun showsDemonstrativesAsPronounsAndOtherDeterminersAsGrammar() {
        assertEquals(PartOfSpeech.PRONOUN, englishPartOfSpeech("DET", "this"))
        assertEquals(PartOfSpeech.PARTICLE, englishPartOfSpeech("DET", "the"))
        assertEquals(PartOfSpeech.PREPOSITION, englishPartOfSpeech("ADP", "under"))
    }
}
