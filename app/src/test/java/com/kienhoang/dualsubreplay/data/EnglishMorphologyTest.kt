package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishMorphologyTest {
    private companion object {
        val tagger by lazy { LearnerData.open("en-pos.zip").use(EnglishPosTagger::load) }
    }

    private fun tags(line: String) = tagEnglish(tagger, line).map { it.tag }

    private fun morphemes(line: String) = englishMorphemes(tagger, line).filter { it.pos != "PUNCT" }

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
        assertEquals(PartOfSpeech.VERB, englishWords(tagger, "It's going to rain.").first { it.text == "rain" }.partOfSpeech)
        assertEquals(PartOfSpeech.VERB, englishWords(tagger, "It may rain tomorrow.").first { it.text == "rain" }.partOfSpeech)
    }

    @Test
    fun findsTheBaseFormOfVerbs() {
        assertEquals("study", verbLemma("studied"))
        assertEquals("make", verbLemma("making"))
        assertEquals("stop", verbLemma("stopped"))
        assertEquals("go", verbLemma("went"))
    }

    @Test
    fun showsDemonstrativesAsPronounsAndOtherDeterminersAsGrammar() {
        assertEquals(PartOfSpeech.PRONOUN, englishPartOfSpeech("DET", "this"))
        assertEquals(PartOfSpeech.PARTICLE, englishPartOfSpeech("DET", "the"))
        assertEquals(PartOfSpeech.PREPOSITION, englishPartOfSpeech("ADP", "under"))
    }
}
