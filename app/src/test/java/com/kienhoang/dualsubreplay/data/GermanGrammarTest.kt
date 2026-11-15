package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German grammar on the bundled model and lexicon: the sentences of the request, near misses and ambiguity. */
class GermanGrammarTest {
    private companion object {
        init {
            check(GermanMorphology.awaitGrammarReady()) { "German model or lexicon did not load" }
        }

        fun sentence(line: String): GermanSentence = GermanMorphology.sentence(line)!!

        fun matches(line: String): List<GrammarMatch> = germanGrammar(sentence(line))
    }

    private fun GrammarMatch.words(line: String) = evidence.map { line.substring(it.first, it.last + 1) }

    private fun point(
        line: String,
        meaning: GrammarMeaning,
    ): GrammarMatch? = matches(line).firstOrNull { meaning in it.meanings }

    private fun assertPoint(
        line: String,
        meaning: GrammarMeaning,
        vararg words: String,
    ): GrammarMatch {
        val found = matches(line).filter { meaning in it.meanings }
        assertTrue("$meaning not found in \"$line\"; got ${matches(line).map { it.meanings }}", found.isNotEmpty())
        val exact = found.firstOrNull { it.words(line) == words.toList() }
        assertNotNull("$meaning in \"$line\" has evidence ${found.map { it.words(line) }}, expected ${words.toList()}", exact)
        return exact!!
    }

    private fun assertNoPoint(
        line: String,
        meaning: GrammarMeaning,
    ) = assertNull("$meaning should not be found in \"$line\"", point(line, meaning))

    // ---- the acceptance sentences

    @Test fun praesensAndSentenceStructure() {
        val line = "Ich gehe nach Hause."
        val present = assertPoint(line, GrammarMeaning.DE_PRAESENS, "gehe")
        assertEquals("the second meaning is the other reading of gehe", GrammarMeaning.DE_KONJUNKTIV_I, present.meanings[1])
        assertPoint(line, GrammarMeaning.DE_PREP_DATIV, "nach", "Hause")
        assertNoPoint(line, GrammarMeaning.DE_TRENNBARES_VERB)
    }

    @Test fun perfekt() {
        assertPoint("Ich bin gestern nach Hause gegangen.", GrammarMeaning.DE_PERFEKT, "bin", "gegangen")
        assertPoint("Sie haben das Buch gelesen.", GrammarMeaning.DE_PERFEKT, "haben", "gelesen")
    }

    @Test fun plusquamperfekt() {
        assertPoint("Er hatte das Buch gelesen.", GrammarMeaning.DE_PLUSQUAMPERFEKT, "hatte", "gelesen")
        assertNoPoint("Er hatte das Buch gelesen.", GrammarMeaning.DE_PERFEKT)
    }

    @Test fun konjunktivIiAndTheUnrealCondition() {
        val line = "Wenn ich Zeit hätte, würde ich kommen."
        assertPoint(line, GrammarMeaning.DE_KONDITIONAL_IRREAL, "Wenn", "hätte", "würde")
        assertPoint(line, GrammarMeaning.DE_KONJUNKTIV_II, "hätte")
        assertPoint(line, GrammarMeaning.DE_WUERDE_INFINITIV, "würde", "kommen")
        assertPoint(line, GrammarMeaning.DE_SUB_WENN, "Wenn", "hätte")
        assertNoPoint(line, GrammarMeaning.DE_PRAESENS)
        val lexicon = GermanMorphology.lexicon()!!
        assertTrue(lexicon.verbReadings("hätte").any { it.verb.infinitive == "haben" && it.cell.tense == GermanTense.SUBJUNCTIVE_II })
    }

    @Test fun pastCounterfactualWithAModalConstruction() {
        val line = "Ich hätte das früher wissen müssen."
        val point = assertPoint(line, GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT, "hätte", "wissen", "müssen")
        assertEquals(GrammarMeaning.DE_ERSATZINFINITIV, point.meanings[1])
        assertEquals(CefrLevel.B2, point.level)
        assertNoPoint(line, GrammarMeaning.DE_MODALVERB)
        assertNoPoint(line, GrammarMeaning.DE_PRAESENS)
    }

    @Test fun nominativeDativeAndAccusativeInOneSentence() {
        val line = "Der Mann gibt dem Kind einen Ball."
        assertPoint(line, GrammarMeaning.DE_DATIV_AKKUSATIV, "gibt", "dem", "Kind", "einen", "Ball")
        val s = sentence(line)
        val index = { word: String -> s.words.indexOfFirst { it.text == word } }
        assertEquals(setOf(GermanCase.NOMINATIVE), germanNounPhrase(s, index("Mann"))!!.cases)
        assertEquals(setOf(GermanCase.DATIVE), germanNounPhrase(s, index("Kind"))!!.cases)
        assertEquals(setOf(GermanCase.ACCUSATIVE), germanNounPhrase(s, index("Ball"))!!.cases)
    }

    @Test fun verbPrepositionGovernment() {
        val line = "Ich warte auf den Bus."
        val point = assertPoint(line, GrammarMeaning.DE_VERB_PREP_AKKUSATIV, "warte", "auf", "den", "Bus")
        assertFalse(point.tentative)
        assertEquals("warten auf + Akkusativ", point.form)
        assertNoPoint(line, GrammarMeaning.DE_WECHSEL_AKKUSATIV)
        assertNoPoint(line, GrammarMeaning.DE_TRENNBARES_VERB)
    }

    @Test fun directionalAccusativeAndLocativeDative() {
        assertPoint("Ich lege das Buch auf den Tisch.", GrammarMeaning.DE_WECHSEL_AKKUSATIV, "auf", "den", "Tisch")
        assertPoint("Das Buch liegt auf dem Tisch.", GrammarMeaning.DE_WECHSEL_DATIV, "auf", "dem", "Tisch")
        assertNoPoint("Ich lege das Buch auf den Tisch.", GrammarMeaning.DE_TRENNBARES_VERB)
    }

    @Test fun separableVerbIsMadeOfTwoWordsAndNotTheOnesBetween() {
        val line = "Ich stehe früh auf."
        val point = assertPoint(line, GrammarMeaning.DE_TRENNBARES_VERB, "stehe", "auf")
        assertEquals("aufstehen", point.form)
        val early = line.indexOf("früh")
        assertTrue("a tap on früh gets no card for the separable verb", grammarForSelection(listOf(point), early, early + 4).isEmpty())
        val stehe = line.indexOf("stehe")
        assertEquals(listOf(point), grammarForSelection(listOf(point), stehe, stehe + 5))
        val auf = line.indexOf("auf")
        assertEquals(listOf(point), grammarForSelection(listOf(point), auf, auf + 3))
    }

    @Test fun subordinateClauseWithTheVerbLast() {
        assertPoint("Ich weiß, dass er morgen kommt.", GrammarMeaning.DE_SUB_DASS, "dass", "kommt")
        assertPoint("Er bleibt zu Hause, weil er krank ist.", GrammarMeaning.DE_SUB_WEIL, "weil", "ist")
    }

    @Test fun relativeClause() {
        assertPoint("Das Buch, das ich gelesen habe, ist interessant.", GrammarMeaning.DE_RELATIVSATZ, "das", "gelesen", "habe")
        assertNoPoint("Ich glaube, die Frau kommt.", GrammarMeaning.DE_RELATIVSATZ)
    }

    @Test fun vorgangspassivAndTheAmbiguousResultState() {
        assertPoint("Das Haus wird gebaut.", GrammarMeaning.DE_VORGANGSPASSIV, "wird", "gebaut")
        val state = assertPoint("Das Haus ist gebaut.", GrammarMeaning.DE_ZUSTANDSPASSIV, "ist", "gebaut")
        assertTrue("a result state reads like a predicate adjective, so it is only tentative", state.tentative)
        assertNoPoint("Das Haus ist gebaut.", GrammarMeaning.DE_PERFEKT)
    }

    @Test fun reportedSpeechNeedsAFormThatIsNotTheIndicative() {
        assertPoint("Er sagte, er sei krank.", GrammarMeaning.DE_KONJUNKTIV_I, "er", "sei")
        // After ich, habe is Präsens; Konjunktiv I is only the formal alternative the card lists second.
        val have = assertPoint("Ich habe Zeit.", GrammarMeaning.DE_PRAESENS, "habe")
        assertEquals(GrammarMeaning.DE_PRAESENS, have.meanings.first())
        assertFalse("no evidence for indirect speech after ich", have.tentative)
        val said = assertPoint("Er sagte, er sei krank.", GrammarMeaning.DE_PRAETERITUM, "sagte")
        assertEquals("sagte is also Konjunktiv II in form", GrammarMeaning.DE_KONJUNKTIV_II, said.meanings[1])
    }

    @Test fun genitivePreposition() {
        assertPoint("Wegen des Wetters bleiben wir zu Hause.", GrammarMeaning.DE_PREP_GENITIV, "Wegen", "des", "Wetters")
    }

    @Test fun reflexiveVerbAndInfinitiveWithZu() {
        val line = "Ich freue mich darauf, dich zu sehen."
        assertPoint(line, GrammarMeaning.DE_REFLEXIVES_VERB, "freue", "mich")
        assertPoint(line, GrammarMeaning.DE_ZU_INFINITIV, "zu", "sehen")
        assertPoint(line, GrammarMeaning.DE_VERB_PREP_AKKUSATIV, "freue", "darauf")
    }

    @Test fun adjectiveEndingAndDativeVerb() {
        val line = "Der alte Mann hilft der jungen Frau."
        assertPoint(line, GrammarMeaning.DE_ADJEKTIV_SCHWACH, "Der", "alte", "Mann")
        assertPoint(line, GrammarMeaning.DE_DATIV_VERB, "hilft", "der", "jungen", "Frau")
        val s = sentence(line)
        val frau = s.words.indexOfFirst { it.text == "Frau" }
        val cases = germanNounPhrase(s, frau)!!.cases
        assertEquals("der jungen Frau is dative or genitive by form alone", setOf(GermanCase.DATIVE, GermanCase.GENITIVE), cases)
    }

    @Test fun genitiveAttributeOfAPluralNoun() {
        val line = "Die Bücher der Kinder liegen hier."
        val point = assertPoint(line, GrammarMeaning.DE_GENITIVATTRIBUT, "Die", "Bücher", "der", "Kinder")
        assertFalse("der Kinder can only be genitive plural", point.tentative)
    }

    @Test fun ambiguousFormsAreReportedAsAmbiguous() {
        // der is five different things; with Frau the phrase is dative or genitive, and the card must not pick one.
        val s = sentence("Der Frau gebe ich das Buch.")
        val frau = s.words.indexOfFirst { it.text == "Frau" }
        assertEquals(setOf(GermanCase.DATIVE, GermanCase.GENITIVE), germanNounPhrase(s, frau)!!.cases)
        // die Frau is nominative or accusative
        val t = sentence("Ich sehe die Frau.")
        assertEquals(
            setOf(GermanCase.NOMINATIVE, GermanCase.ACCUSATIVE),
            germanNounPhrase(t, t.words.indexOfFirst { it.text == "Frau" })!!.cases,
        )
        // a bare noun after an ambiguous preposition gives no case claim at all
        assertNoPoint("Ich wohne in Berlin.", GrammarMeaning.DE_WECHSEL_DATIV)
        assertNoPoint("Ich wohne in Berlin.", GrammarMeaning.DE_WECHSEL_AKKUSATIV)
    }

    @Test fun imperativeQuestionsAndInversion() {
        assertPoint("Geh nach Hause!", GrammarMeaning.DE_IMPERATIV, "Geh")
        assertPoint("Gehen Sie nach Hause!", GrammarMeaning.DE_IMPERATIV, "Gehen", "Sie")
        assertNoPoint("Kommst du morgen?", GrammarMeaning.DE_IMPERATIV)
        assertPoint("Kommst du morgen?", GrammarMeaning.DE_JA_NEIN_FRAGE, "Kommst")
        assertPoint("Wo wohnst du?", GrammarMeaning.DE_W_FRAGE, "Wo", "wohnst")
        assertPoint("Heute gehe ich nach Hause.", GrammarMeaning.DE_INVERSION, "Heute", "gehe")
    }

    @Test fun comparisonAndSuperlative() {
        assertPoint("Er ist älter als ich.", GrammarMeaning.DE_KOMPARATIV, "älter", "als")
        assertPoint("Sie ist am schönsten.", GrammarMeaning.DE_SUPERLATIV, "am", "schönsten")
        assertNoPoint("Das ist ein schöner Tag.", GrammarMeaning.DE_KOMPARATIV)
    }

    @Test fun futureAndPassiveVariants() {
        assertPoint("Wir werden morgen gehen.", GrammarMeaning.DE_FUTUR_I, "werden", "gehen")
        assertPoint("Das Haus ist gebaut worden.", GrammarMeaning.DE_PASSIV_PERFEKT, "ist", "gebaut", "worden")
        assertPoint("Ich möchte ein Buch lesen.", GrammarMeaning.DE_MODALVERB, "möchte", "lesen")
        assertPoint("Er geht, um Brot zu kaufen.", GrammarMeaning.DE_UM_ZU, "um", "zu", "kaufen")
    }

    // ---- near misses

    @Test fun nearMissesStayQuiet() {
        assertNoPoint("Ich habe Hunger.", GrammarMeaning.DE_PERFEKT)
        assertNoPoint("Das Buch ist rot.", GrammarMeaning.DE_ZUSTANDSPASSIV)
        assertNoPoint("Ich gehe nach Hause.", GrammarMeaning.DE_TRENNBARES_VERB)
        assertNoPoint("Wir haben Zeit.", GrammarMeaning.DE_PERFEKT)
        assertNoPoint("Das Haus ist groß.", GrammarMeaning.DE_VORGANGSPASSIV)
    }

    // ---- robustness and compatibility

    @Test fun oddInputNeverThrows() {
        for (line in listOf(
            "",
            "   ",
            "...",
            "!?",
            "ß",
            "Straße",
            "Äpfel",
            "ÖSTERREICH",
            "Ich bin müde …",
            "a".repeat(500),
            "Ich, du, er, sie, es",
        )) {
            val s = sentence(line)
            germanGrammar(s)
        }
        assertTrue(matches("").isEmpty())
        assertTrue(matches("...").isEmpty())
    }

    @Test fun umlautsEszettAndCapitalsKeepExactOffsets() {
        val line = "Die Straße ist größer als das Äußere."
        for (match in matches(line)) {
            for (range in match.evidence) assertTrue(line.substring(range.first, range.last + 1).isNotBlank())
            assertTrue(match.start in 0..line.length && match.end in match.start..line.length)
        }
    }

    @Test fun everyGermanCodeSelectsGermanGrammarAndOthersKeepTheirOwn() {
        for (code in listOf(
            "de",
            "DE",
            "de-DE",
            "de-AT",
            "de-CH",
            "de_DE",
        )) {
            assertEquals(code, GrammarLanguage.GERMAN, grammarLanguage("Ich gehe", code))
        }
        assertEquals(GrammarLanguage.ENGLISH, grammarLanguage("I go", "en-US"))
        assertEquals(GrammarLanguage.SPANISH, grammarLanguage("Yo voy", "es-MX"))
        assertEquals(GrammarLanguage.CHINESE, grammarLanguage("我去", "zh-CN"))
        assertEquals(GrammarLanguage.JAPANESE, grammarLanguage("私は行く", "ja"))
        assertNull(grammarLanguage("Je vais", "fr"))
        assertEquals("kana in a line wins over a German code", GrammarLanguage.JAPANESE, grammarLanguage("ひらがな", "de"))
    }

    @Test fun pointsWithoutEvidenceSelectExactlyAsBefore() {
        // The shape every other language produces: no evidence, so the old rules (overlap, or starting right after) apply.
        val inside = GrammarMatch("〜て", 2, 4, listOf(GrammarMeaning.TE_AND))
        val following = GrammarMatch("〜が", 5, 6, listOf(GrammarMeaning.BUT))
        val matches = listOf(inside, following)
        assertEquals(listOf(inside), grammarForSelection(matches, 3, 4).filter { it === inside })
        assertEquals(
            "a point that starts right after the selection is still included",
            listOf(inside, following),
            grammarForSelection(matches, 0, 5),
        )
        assertEquals(emptyList<GrammarMatch>(), grammarForSelection(matches, 7, 9))
    }

    @Test fun anAdjectiveTheLexiconDoesNotListStillFormsAPhrase() {
        val s = sentence("Ich sehe den hochgradig blauen Himmel.")
        val himmel = s.words.indexOfFirst { it.text == "Himmel" }
        val phrase = germanNounPhrase(s, himmel)
        assertNotNull(phrase)
        assertTrue(GermanCase.ACCUSATIVE in phrase!!.cases)
    }

    // ---- hardening: separable prefixes, mis-tagged verbs, adjective government

    @Test fun separablePrefixesComeFromTheStandardInventory() {
        assertPoint("Wir sehen heute Abend fern.", GrammarMeaning.DE_TRENNBARES_VERB, "sehen", "fern")
        assertPoint("Sie sieht nie fern.", GrammarMeaning.DE_TRENNBARES_VERB, "sieht", "fern")
        assertPoint("Der Film fängt um acht Uhr an.", GrammarMeaning.DE_TRENNBARES_VERB, "fängt", "an")
        // near misses: a word that is not a prefix, or a preposition with its noun, is not a separable particle
        assertNoPoint("Er bleibt hier.", GrammarMeaning.DE_TRENNBARES_VERB)
        assertNoPoint("Wir gehen spazieren.", GrammarMeaning.DE_TRENNBARES_VERB)
        assertNoPoint("Er steht auf dem Stuhl.", GrammarMeaning.DE_TRENNBARES_VERB)
    }

    @Test fun aCapitalisedImperativeTheTaggerCallsANounIsStillACommand() {
        assertPoint("Mach das Fenster auf!", GrammarMeaning.DE_IMPERATIV, "Mach")
        assertPoint("Mach das Fenster auf!", GrammarMeaning.DE_TRENNBARES_VERB, "Mach", "auf")
        // Lauf is also a noun (der Lauf); the article or pronoun after it shows it is the verb
        assertPoint("Lauf mir nicht weg!", GrammarMeaning.DE_IMPERATIV, "Lauf")
        assertPoint("Lauf mir nicht weg!", GrammarMeaning.DE_TRENNBARES_VERB, "Lauf", "weg")
        // the same spelling as a noun subject stays a noun
        assertNoPoint("Lauf und Sprung sind Disziplinen.", GrammarMeaning.DE_IMPERATIV)
        assertNoPoint("Sprung ins Wasser ist erlaubt.", GrammarMeaning.DE_IMPERATIV)
        assertNoPoint("Der Lauf beginnt um acht.", GrammarMeaning.DE_IMPERATIV)
    }

    @Test fun aLowercaseWordTheTaggerCallsANounIsAMisTagBecauseNounsAreCapitalised() {
        assertPoint("Wenn du Zeit hast, ruf mich an.", GrammarMeaning.DE_TRENNBARES_VERB, "ruf", "an")
        assertPoint("Bevor wir gehen, räum bitte auf.", GrammarMeaning.DE_TRENNBARES_VERB, "räum", "auf")
        // a line without any capital carries no spelling evidence, so a word the tagger calls a noun is not re-read as a verb
        val plain = sentence("das haus ist groß").words.first { it.text == "haus" }
        assertTrue("haus keeps the tagger's reading in a line without capitals", plain.verbs.isEmpty() || plain.tag != "VERB")
        // a normal sentence is unchanged
        assertNoPoint("Er trinkt gern Wasser.", GrammarMeaning.DE_TRENNBARES_VERB)
        assertPoint("Er trinkt gern Wasser.", GrammarMeaning.DE_PRAESENS, "trinkt")
    }

    @Test fun anAdjectiveGovernsItsPrepositionAndIsNotADirection() {
        val proud =
            assertPoint(
                "Er ist sehr stolz auf seine Tochter.",
                GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV,
                "stolz",
                "auf",
                "seine",
                "Tochter",
            )
        assertFalse(proud.tentative)
        assertNoPoint("Er ist sehr stolz auf seine Tochter.", GrammarMeaning.DE_WECHSEL_AKKUSATIV)
        assertPoint("Wir sind zufrieden mit dem Hotel.", GrammarMeaning.DE_ADJEKTIV_PREP_DATIV, "zufrieden", "mit", "dem", "Hotel")
        assertPoint("Er ist stolz auf ihn.", GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV, "stolz", "auf", "ihn")
        // regression: a real Wechselpräposition keeps its card, also after an adverb that looks like the adjective
        assertPoint("Das Kind ist auf dem Sofa.", GrammarMeaning.DE_WECHSEL_DATIV, "auf", "dem", "Sofa")
        assertPoint("Er geht stolz auf die Bühne.", GrammarMeaning.DE_WECHSEL_AKKUSATIV, "auf", "die", "Bühne")
        assertNoPoint("Er geht stolz auf die Bühne.", GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV)
        // ambiguous: an adjective the table does not list still never makes the preposition a direction
        assertNoPoint("Sie ist müde auf dem Sofa.", GrammarMeaning.DE_WECHSEL_DATIV)
    }

    @Test fun theNewGermanPointsNeverTouchOtherLanguages() {
        assertEquals(GrammarLanguage.ENGLISH, grammarLanguage("Mach das Fenster auf!", "en"))
        assertEquals(GrammarLanguage.SPANISH, grammarLanguage("Mach das Fenster auf!", "es-MX"))
        assertEquals(GrammarLanguage.GERMAN, grammarLanguage("Mach das Fenster auf!", "de-CH"))
    }

    // ---- second hardening pass: development and regression examples (none of them is in a held-out set)

    @Test fun aParticipleWithAnIntensifierOrAPrepositionIsAnAdjectiveNotAPassive() {
        assertNoPoint("Der Schüler ist sehr gelangweilt.", GrammarMeaning.DE_ZUSTANDSPASSIV)
        assertNoPoint("Sie ist begeistert von dem Buch.", GrammarMeaning.DE_ZUSTANDSPASSIV)
        assertPoint("Sie ist begeistert von dem Buch.", GrammarMeaning.DE_ADJEKTIV_PREP_DATIV, "begeistert", "von", "dem", "Buch")
        // ambiguous: a result state without such a signal stays a guess, never a firm claim
        val shop = assertPoint("Das Geschäft ist geöffnet.", GrammarMeaning.DE_ZUSTANDSPASSIV, "ist", "geöffnet")
        assertTrue(shop.tentative)
    }

    @Test fun leiderIsNotAComparativeButBesserStillIs() {
        assertNoPoint("Leider ist er krank.", GrammarMeaning.DE_KOMPARATIV)
        assertPoint("Leider ist er krank.", GrammarMeaning.DE_INVERSION, "Leider", "ist")
        assertPoint("Das Wetter wird besser.", GrammarMeaning.DE_KOMPARATIV, "besser")
    }

    @Test fun naechsteIsAWordOfItsOwnAndSchoensteIsStillASuperlative() {
        assertNoPoint("Nächste Woche bin ich im Urlaub.", GrammarMeaning.DE_SUPERLATIV)
        assertPoint("Nächste Woche bin ich im Urlaub.", GrammarMeaning.DE_ADJEKTIV_STARK, "Nächste", "Woche")
        assertPoint("Das ist der schönste Tag.", GrammarMeaning.DE_SUPERLATIV, "schönste")
    }

    @Test fun aDegreeWordInsideANounPhraseKeepsTheDeclensionOfTheArticle() {
        assertPoint("Sie hat ein besonders schönes Zimmer.", GrammarMeaning.DE_ADJEKTIV_GEMISCHT, "ein", "besonders", "schönes", "Zimmer")
        assertPoint("Sie hat ein schönes Zimmer.", GrammarMeaning.DE_ADJEKTIV_GEMISCHT, "ein", "schönes", "Zimmer")
        // a degree word right before the noun is not part of an adjective phrase
        assertNoPoint("Er hat sehr Hunger.", GrammarMeaning.DE_ADJEKTIV_STARK)
        assertNoPoint("Er hat sehr Hunger.", GrammarMeaning.DE_ADJEKTIV_GEMISCHT)
    }

    @Test fun aCapitalisedAdjectiveThatOpensASentenceIsAnAdjectiveBeforeANoun() {
        assertPoint("Frische Brötchen schmecken gut.", GrammarMeaning.DE_ADJEKTIV_STARK, "Frische", "Brötchen")
        // the same spelling as a noun is left alone
        assertNoPoint("Frische ist wichtig.", GrammarMeaning.DE_ADJEKTIV_STARK)
        assertNoPoint("Milch ist gesund.", GrammarMeaning.DE_ADJEKTIV_STARK)
    }

    @Test fun anInvertedAuxiliaryOpensAPastConditional() {
        val line = "Wäre er früher gegangen, hätte er den Zug erreicht."
        assertPoint(line, GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT, "Wäre", "gegangen")
        assertPoint(line, GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT, "hätte", "erreicht")
    }

    @Test fun aVerbWithAPrepositionIsNotMatchedOnAPredicateAdjectiveButOnAParticiple() {
        assertNoPoint("Er ist verliebt in sie.", GrammarMeaning.DE_VERB_PREP_AKKUSATIV)
        assertPoint("Er ist verliebt in sie.", GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV, "verliebt", "in", "sie")
        assertPoint("Er wartet auf den Zug.", GrammarMeaning.DE_VERB_PREP_AKKUSATIV, "wartet", "auf", "den", "Zug")
        assertPoint("Sie hat lange auf den Brief gewartet.", GrammarMeaning.DE_VERB_PREP_AKKUSATIV, "auf", "den", "Brief", "gewartet")
    }

    // ---- third pass: contraction + adjective, inversion after a longer opener, readings that agree with the subject

    @Test fun aContractedArticleIsTheDeterminerOfTheAdjectivePhraseAfterIt() {
        assertPoint("Im kleinen Garten steht ein Baum.", GrammarMeaning.DE_ADJEKTIV_SCHWACH, "kleinen", "Garten")
        // a plain article phrase and a plain adjective phrase keep their cards
        assertPoint("Der kleine Garten ist schön.", GrammarMeaning.DE_ADJEKTIV_SCHWACH, "Der", "kleine", "Garten")
        assertNoPoint("Im Garten steht ein Baum.", GrammarMeaning.DE_ADJEKTIV_SCHWACH)
    }

    @Test fun inversionFollowsAnOpenerOfSeveralWordsWhenASubjectFollowsTheVerb() {
        assertPoint("Nach der Arbeit gehe ich schwimmen.", GrammarMeaning.DE_INVERSION, "Nach", "gehe")
        assertPoint("Heute Nachmittag kommt sie.", GrammarMeaning.DE_INVERSION, "Heute", "kommt")
        assertPoint("Dann gehe ich nach Hause.", GrammarMeaning.DE_INVERSION, "Dann", "gehe")
        // negatives: the subject comes first, or no subject follows the verb
        assertNoPoint("Er geht nach der Arbeit schwimmen.", GrammarMeaning.DE_INVERSION)
        assertNoPoint("Mit dem Zug fahren.", GrammarMeaning.DE_INVERSION)
        assertNoPoint("Heute regnet.", GrammarMeaning.DE_INVERSION)
    }

    @Test fun theReadingsOfAVerbAgreeWithTheSubjectPronounBesideIt() {
        fun cells(
            line: String,
            word: String,
        ): Set<Pair<Int, Boolean>> {
            val start = line.indexOf(word)
            return germanWordDetails(sentence(line), start, start + word.length)!!.cells.map { it.person to it.plural }.toSet()
        }
        // gehe after ich is first person singular; the third person of Konjunktiv I is not a reading here
        assertEquals(setOf(1 to false), cells("Ich gehe nach Hause.", "gehe"))
        assertEquals(setOf(3 to false), cells("Er geht nach Hause.", "geht"))
        // inverted order and a plural subject
        assertEquals(setOf(1 to true), cells("Gehen wir jetzt?", "Gehen"))
        // ambiguous: sie decides nothing, so every reading of the form stays
        assertTrue(cells("Sie gehen nach Hause.", "gehen").size > 1)
        // no subject pronoun beside the verb: nothing is filtered
        assertTrue(cells("Das Kind geht nach Hause.", "geht").size > 1)
    }
}
