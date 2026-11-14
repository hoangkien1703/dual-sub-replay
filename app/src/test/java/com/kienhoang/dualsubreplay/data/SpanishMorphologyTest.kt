package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishMorphologyTest {
    private companion object {
        val analyzer by lazy { loadSpanishAnalyzer() }
    }

    private fun tags(line: String) = tagSpanish(analyzer.tagger, line).map { it.tag }

    private fun pieces(line: String) = spanishPiecesOf(analyzer, tagSpanish(analyzer.tagger, line)).filter { it.tag != "PUNCT" }

    private fun reading(
        line: String,
        word: String,
    ): SpanishVerbReading = pieces(line).first { it.text == word }.readings.first()

    @Test
    fun tagsLikeOpenNlp() {
        // OpenNLP 2.5.4's own output for these lines with the same model.
        assertEquals(
            listOf("ADV", "VERB", "ADP+DET", "NOUN", "CCONJ", "VERB", "DET", "NOUN", "ADV", "ADJ", "PUNCT"),
            tags("Ayer fuimos al cine y vimos una película muy buena."),
        )
        assertEquals(listOf("ADV", "VERB", "SCONJ", "PRON", "VERB", "PUNCT"), tags("No quiero que te vayas."))
        assertEquals(
            listOf("DET", "NOUN", "AUX", "ADJ", "CCONJ", "VERB", "ADP", "PROPN", "PUNCT"),
            tags("Mi hermana es médica y vive en Sevilla."),
        )
        assertEquals(listOf("PUNCT", "ADV", "VERB", "DET", "NOUN", "PUNCT"), tags("¿Dónde pusiste las llaves?"))
        assertEquals(
            listOf("AUX", "VERB", "SCONJ", "VERB", "DET", "NOUN", "PUNCT"),
            tags("Estaba durmiendo cuando sonó el teléfono."),
        )
    }

    @Test
    fun readsTenseAndPersonOfRegularAndIrregularVerbs() {
        assertEquals(
            SpanishVerbReading("poner", SpanishTense.PRETERITE, SpanishPerson.TU),
            reading("¿Dónde pusiste las llaves?", "pusiste"),
        )
        assertEquals(
            SpanishVerbReading("ir", SpanishTense.PRESENT_SUBJUNCTIVE, SpanishPerson.TU),
            reading("No quiero que te vayas.", "vayas"),
        )
        assertEquals(SpanishVerbReading("dormir", SpanishTense.GERUND, SpanishPerson.NONE), reading("Estaba durmiendo.", "durmiendo"))
        assertEquals(SpanishVerbReading("saber", SpanishTense.PRESENT, SpanishPerson.VOS), reading("Vos sabés que te quiero.", "sabés"))
        assertEquals(SpanishVerbReading("oír", SpanishTense.PRESENT, SpanishPerson.NOSOTROS), reading("Oímos un ruido.", "oímos"))
        assertEquals(SpanishVerbReading("ir", SpanishTense.PARTICIPLE, SpanishPerson.NONE), reading("Ya se había ido.", "ido"))
        // A word the model calls a noun or a preposition that can only be a verb form is a verb.
        assertEquals(SpanishVerbReading("comer", SpanishTense.PRETERITE, SpanishPerson.YO), reading("Ayer comí paella.", "comí"))
        assertEquals(
            SpanishVerbReading("llegar", SpanishTense.PRESENT_SUBJUNCTIVE, SpanishPerson.TU),
            reading("Cuando llegues, llámame.", "llegues"),
        )
    }

    @Test
    fun choosesTheLikeliestVerbFromContext() {
        assertEquals("ir", reading("Fue a casa de su madre.", "fue").lemma)
        assertEquals("ser", reading("Fue un día muy largo.", "fue").lemma)
        assertEquals(SpanishTense.IMPERATIVE, reading("¡Habla más alto!", "habla").tense)
        assertEquals(SpanishTense.PRESENT, reading("Ella habla tres idiomas.", "habla").tense)
        assertEquals("saber", reading("No sé si viene.", "sé").lemma)
        assertEquals("soler", reading("Suelo levantarme temprano.", "suelo").lemma)
    }

    @Test
    fun wordsTheModelCallsVerbsKeepTheirListedPartOfSpeech() {
        // The model tags these as verbs, but they are no verb form.
        assertEquals("NOUN", pieces("¿Azúcar?").first { it.text == "azúcar" }.tag)
        assertEquals("INTJ", pieces("Ah, ya veo.").first { it.text == "ah" }.tag)
        assertTrue(pieces("¿Azúcar?").first { it.text == "azúcar" }.readings.isEmpty())
    }

    @Test
    fun splitsContractionsAndAttachedPronouns() {
        assertEquals(listOf("vamos", "a", "l", "parque"), pieces("Vamos al parque.").map { it.text })
        assertEquals("el", pieces("Vamos al parque.")[2].lemma)
        val give = pieces("Dámelo ahora.")
        assertEquals(listOf("dá", "me", "lo", "ahora"), give.map { it.text })
        assertEquals(SpanishVerbReading("dar", SpanishTense.IMPERATIVE, SpanishPerson.TU), give[0].readings.first())
        assertEquals(listOf("tienes", "que", "decír", "se", "lo"), pieces("Tienes que decírselo.").map { it.text })
        assertEquals(SpanishVerbReading("ir", SpanishTense.IMPERATIVE, SpanishPerson.NOSOTROS), reading("Vámonos ya.", "vámo"))
    }

    @Test
    fun findsTheDictionaryForm() {
        val words = analyzer.words
        listOf("canciones" to "canción", "casas" to "casa", "bonitas" to "bonito", "luces" to "luz", "chica" to "chico", "esta" to "este")
            .forEach { (word, lemma) ->
                assertEquals(
                    word,
                    lemma,
                    words.lemma(
                        word,
                        if (word ==
                            "esta"
                        ) {
                            'd'
                        } else if (word == "bonitas") {
                            'a'
                        } else {
                            'n'
                        },
                    ),
                )
            }
        val tokens = spanishWords(analyzer, "Ayer fuimos al cine con mis amigas.")
        assertEquals("ir", tokens.first { it.text == "fuimos" }.baseForm)
        assertEquals("amigo", tokens.first { it.text == "amigas" }.baseForm)
        assertEquals(PartOfSpeech.VERB, tokens.first { it.text == "fuimos" }.partOfSpeech)
        assertEquals("dar", spanishWords(analyzer, "Dámelo.").first().baseForm)
    }

    @Test
    fun verbDetailsListTheReadingsAndAttachedPronouns() {
        val line = "Dámelo ahora mismo."
        val details = spanishVerbDetails(analyzer, line, 0, "Dámelo".length)!!
        assertEquals(listOf("me", "lo"), details.attachedPronouns)
        assertEquals(SpanishPerson.TU, details.readings.first().person)
        val both = spanishVerbDetails(analyzer, "Hablamos mucho.", 0, "Hablamos".length)!!.readings.map { it.tense }
        assertTrue(SpanishTense.PRESENT in both && SpanishTense.PRETERITE in both)
    }

    @Test
    fun regularFormsComeFromTheEndings() {
        val forms = regularSpanishForms("hablar").values
        assertTrue("hablaste" in forms && "hablábamos" in forms && "hablaré" in forms && "hablando" in forms)
    }
}
