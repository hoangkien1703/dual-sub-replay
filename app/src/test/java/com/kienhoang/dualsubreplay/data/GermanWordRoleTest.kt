package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a tapped German word is doing in its line, for adjectives, adverbs, pronouns, determiners, prepositions and conjunctions. */
class GermanWordRoleTest {
    private companion object {
        init {
            check(GermanMorphology.awaitGrammarReady()) { "German model or lexicon did not load" }
        }

        fun role(
            line: String,
            word: String,
            occurrence: Int = 0,
        ): GermanWordRole? {
            var from = -1
            repeat(occurrence + 1) { from = line.indexOf(word, from + 1) }
            check(from >= 0) { "$word not in $line" }
            return germanWordRole(GermanMorphology.sentence(line)!!, from, from + word.length)
        }

        val NOM = GermanCase.NOMINATIVE
        val ACC = GermanCase.ACCUSATIVE
        val DAT = GermanCase.DATIVE
        val GEN = GermanCase.GENITIVE
    }

    @Test fun attributiveAdjectiveExplainsItsEnding() {
        val strong = role("Ich trinke kalten Tee.", "kalten")!!
        assertEquals(GermanRole.ADJECTIVE_ATTRIBUTIVE, strong.role)
        assertEquals(GermanDeclension.STRONG, strong.declension)
        assertEquals("en", strong.ending)
        assertEquals("Tee", strong.target)
        assertTrue(ACC in strong.cases)
        val weak = role("Der alte Mann kommt.", "alte")!!
        assertEquals(GermanDeclension.WEAK, weak.declension)
        assertEquals(setOf(NOM), weak.cases)
        val mixed = role("Ein alter Mann kommt.", "alter")!!
        assertEquals(GermanDeclension.MIXED, mixed.declension)
        val neuter = role("Das ist ein schönes Haus.", "schönes")!!
        assertEquals("es", neuter.ending)
        assertEquals("Haus", neuter.target)
    }

    @Test fun adjectiveAfterAContractedArticleIsWeakAndDative() {
        val dative = role("Wir wohnen mit einem schönen Haus.", "schönen")!!
        assertEquals(GermanRole.ADJECTIVE_ATTRIBUTIVE, dative.role)
        assertEquals(setOf(DAT), dative.cases)
        assertEquals(GermanDeclension.MIXED, dative.declension)
    }

    @Test fun predicativeAndAdverbialAdjectivesCarryNoEnding() {
        val predicative = role("Das Haus ist schön.", "schön")!!
        assertEquals(GermanRole.ADJECTIVE_PREDICATIVE, predicative.role)
        assertEquals("ist", predicative.target)
        val adverbial = role("Er fährt schnell.", "schnell")!!
        assertEquals(GermanRole.ADJECTIVE_ADVERBIAL, adverbial.role)
        assertEquals("fährt", adverbial.target)
    }

    @Test fun trueAdverbsAreToldApartFromAdverbialAdjectives() {
        val time = role("Heute gehe ich nach Hause.", "Heute")!!
        assertEquals(GermanRole.ADVERB, time.role)
        assertEquals(GermanAdverbKind.TIME, time.adverbKind)
        assertTrue("verb second after a fronted adverb", time.verbSecond)
        assertEquals(false, time.comparable)
        assertEquals(GermanAdverbKind.PLACE, role("Dort wohnt meine Schwester.", "Dort")!!.adverbKind)
        val connective = role("Ich komme trotzdem.", "trotzdem")!!
        assertEquals(GermanAdverbKind.CONNECTIVE, connective.adverbKind)
        // leider is never claimed to have a comparative
        assertEquals(false, role("Ich bin leider krank.", "leider")!!.comparable)
        assertEquals(true, role("Ich esse gern Brot.", "gern")!!.comparable)
    }

    @Test fun aDegreeWordModifiesTheAdjectiveAfterIt() {
        val degree = role("Er arbeitet sehr schnell.", "sehr")!!
        assertEquals(GermanRole.DEGREE_WORD, degree.role)
        assertEquals("schnell", degree.target)
        assertEquals(GermanRole.ADJECTIVE_ADVERBIAL, role("Er arbeitet sehr schnell.", "schnell")!!.role)
    }

    @Test fun personalPronounsGetPersonNumberCaseAndFunction() {
        val dative = role("Er hilft mir.", "mir")!!
        assertEquals(GermanRole.PERSONAL_PRONOUN, dative.role)
        assertEquals(1, dative.person)
        assertEquals(false, dative.plural)
        assertEquals(setOf(DAT), dative.cases)
        assertEquals(GermanSyntaxRole.DATIVE_OBJECT, dative.function)
        assertEquals("helfen", dative.governor)
        val accusative = role("Ich sehe ihn.", "ihn")!!
        assertEquals(3, accusative.person)
        assertEquals(setOf(GermanGender.MASCULINE), accusative.genders)
        assertEquals(setOf(ACC), accusative.cases)
        assertEquals(GermanSyntaxRole.DIRECT_OBJECT, accusative.function)
        assertEquals(GermanSyntaxRole.SUBJECT, role("Er hilft mir.", "Er")!!.function)
    }

    @Test fun aPronounAfterAPrepositionTakesThatPrepositionsCase() {
        val role = role("Ich gehe mit ihm.", "ihm")!!
        assertEquals(GermanSyntaxRole.AFTER_PREPOSITION, role.function)
        assertEquals("mit", role.governor)
        assertEquals(setOf(DAT), role.cases)
    }

    @Test fun reflexivePronounsRepeatTheSubject() {
        assertEquals(GermanRole.REFLEXIVE_PRONOUN, role("Ich freue mich.", "mich")!!.role)
        assertEquals(GermanRole.REFLEXIVE_PRONOUN, role("Er freut sich.", "sich")!!.role)
        // mich after another subject is an ordinary object, not reflexive
        assertEquals(GermanRole.PERSONAL_PRONOUN, role("Er sieht mich.", "mich")!!.role)
    }

    @Test fun ambiguousPronounsAreNeverStatedWithFalseConfidence() {
        val sie = role("Sie kommen morgen.", "Sie")!!
        assertEquals(GermanRole.PERSONAL_PRONOUN, sie.role)
        assertEquals(true, sie.plural)
        val open = role("Wir sehen sie.", "sie")!!
        assertTrue(open.tentative || open.cases == setOf(ACC))
        val ihr = role("Ich helfe ihr.", "ihr")!!
        assertTrue("ihr stays open between du-plural and dative", ihr.tentative || DAT in ihr.cases)
        val uns = role("Er hilft uns.", "uns")!!
        assertEquals(setOf(DAT), uns.cases)
    }

    @Test fun relativePronounPointsBackAndGetsItsCase() {
        val relative = role("Das ist der Mann, den ich kenne.", "den")!!
        assertEquals(GermanRole.RELATIVE_PRONOUN, relative.role)
        assertEquals("Mann", relative.governor)
        assertEquals(setOf(ACC), relative.cases)
        assertTrue(relative.verbLast)
        // the same word before a noun is an article
        assertEquals(GermanRole.DEFINITE_ARTICLE, role("Ich kenne den Mann.", "den")!!.role)
    }

    @Test fun articlesAndDeterminersAreDeclinedByTheirNoun() {
        val dativeArticle = role("Ich helfe dem Mann.", "dem")!!
        assertEquals(GermanRole.DEFINITE_ARTICLE, dativeArticle.role)
        assertEquals(setOf(DAT), dativeArticle.cases)
        assertEquals("Mann", dativeArticle.target)
        assertEquals(setOf(GEN), role("Das Haus des Mannes ist alt.", "des")!!.cases)
        val indefinite = role("Das ist ein guter Freund.", "ein")!!
        assertEquals(GermanRole.INDEFINITE_ARTICLE, indefinite.role)
        assertEquals(GermanDeclension.MIXED, indefinite.declension)
        assertEquals(GermanRole.POSSESSIVE, role("Das ist mein Freund.", "mein")!!.role)
        assertEquals(GermanRole.NEGATIVE_ARTICLE, role("Ich habe keine Zeit.", "keine")!!.role)
    }

    @Test fun prepositionsGiveTheirCaseAndTwoWayPrepositionsFollowTheGovernedPhrase() {
        val mit = role("Ich fahre mit meinem Freund.", "mit")!!
        assertEquals(GermanRole.PREPOSITION, mit.role)
        assertEquals(GermanGovernment.DATIVE, mit.government)
        assertEquals("Freund", mit.target)
        val accusative = role("Das Buch liegt auf den Tisch.", "auf")!!
        assertEquals(setOf(ACC), accusative.cases)
        val dative = role("Das Buch liegt auf dem Tisch.", "auf")!!
        assertEquals(setOf(DAT), dative.cases)
        assertEquals(GermanGovernment.TWO_WAY, dative.government)
    }

    @Test fun aPrepositionFixedByAVerbIsNotReadAsAWechselPreposition() {
        val fixed = role("Ich warte auf den Bus.", "auf")!!
        assertEquals("warten", fixed.fixedWith)
        assertNull(fixed.government)
        assertEquals(setOf(ACC), fixed.cases)
    }

    @Test fun conjunctionsTellWhereTheVerbGoes() {
        val weil = role("Ich bleibe, weil ich krank bin.", "weil")!!
        assertEquals(GermanRole.SUBORDINATOR, weil.role)
        assertTrue(weil.verbLast)
        assertEquals("sein", weil.target)
        assertEquals(GermanRole.SUBORDINATOR, role("Ich bleibe, obwohl es regnet.", "obwohl")!!.role)
        val und = role("Ich bleibe und du gehst.", "und")!!
        assertEquals(GermanRole.COORDINATOR, und.role)
        assertFalse(und.verbLast)
    }

    @Test fun anAmbiguousWordWithoutItsStructureGetsNoRole() {
        // da as an adverb, not a subordinate clause
        assertEquals(GermanRole.ADVERB, role("Da wohnt meine Schwester.", "Da")!!.role)
        // zu before an infinitive is the infinitive marker, not a preposition or a degree word
        val zu = role("Er hofft, dich zu sehen.", "zu")!!
        assertEquals(GermanRole.INFINITIVE_ZU, zu.role)
        assertEquals("sehen", zu.target)
        // zu before an adjective stays a degree word
        assertEquals(GermanRole.DEGREE_WORD, role("Das ist zu teuer.", "zu")!!.role)
    }

    @Test fun negationAndModalParticlesAreNamedForWhatTheyAre() {
        val nicht = role("Ich weiß es nicht.", "nicht")!!
        assertEquals(GermanRole.ADVERB, nicht.role)
        assertEquals(GermanAdverbKind.NEGATION, nicht.adverbKind)
        assertEquals(GermanAdverbKind.ATTITUDE, role("Das ist ja gut.", "ja")!!.adverbKind)
        assertEquals(GermanAdverbKind.ATTITUDE, role("Komm doch mit!", "doch")!!.adverbKind)
    }

    @Test fun aWordTheTaggerCallsNoVerbIsNeverGivenAVerbReadingOrATable() {
        // heute is a lexicon form of heuen and schön of schönen; neither is a verb here
        for ((line, word) in listOf(
            "Ich bleibe heute zu Hause." to "heute",
            "Das Haus ist schön." to "schön",
            "Er läuft schnell." to "schnell",
        )) {
            val from = line.indexOf(word)
            assertNull("$word got a verb", GermanMorphology.wordDetails(line, from, from + word.length)?.verb)
            assertNull("$word got a conjugation table", GermanMorphology.conjugation(line, from, from + word.length))
        }
        val line = "Ich lese ein Buch."
        assertNotNull(GermanMorphology.conjugation(line, line.indexOf("lese"), line.indexOf("lese") + 4))
    }

    @Test fun theSameFormGetsAnotherRoleInAnotherSentence() {
        assertEquals(GermanRole.DEFINITE_ARTICLE, role("Der Mann kommt.", "Der")!!.role)
        assertEquals(GermanRole.RELATIVE_PRONOUN, role("Der Mann, der dort steht, ist alt.", "der")!!.role)
        assertEquals(GermanRole.PREPOSITION, role("Ich gehe in die Schule.", "in")!!.role)
        assertEquals(setOf(DAT), role("Ich bin in der Schule.", "in", occurrence = 1)!!.cases)
        assertEquals(GermanRole.ADJECTIVE_ATTRIBUTIVE, role("Ein schönes Haus.", "schönes")!!.role)
        assertEquals(GermanRole.ADJECTIVE_PREDICATIVE, role("Das Haus ist schön.", "schön")!!.role)
        assertEquals(GermanRole.ADJECTIVE_ADVERBIAL, role("Er läuft schnell.", "schnell")!!.role)
        assertEquals(GermanRole.SUBORDINATOR, role("Er sagt, dass er kommt.", "dass")!!.role)
        assertEquals(GermanRole.COORDINATOR, role("Ich komme, aber er bleibt.", "aber")!!.role)
    }

    @Test fun finiteVerbsAndAuxiliariesGetTheirTenseFromTheirSentence() {
        fun placed(
            line: String,
            word: String,
        ): GermanSelectionConjugation? = line.indexOf(word).let { GermanMorphology.conjugation(line, it, it + word.length) }
        assertEquals("haben", placed("Ich habe es gesehen.", "habe")!!.conjugation.verb.infinitive)
        assertEquals(GermanTableTense.PRESENT, placed("Ich habe es gesehen.", "habe")!!.tense)
        assertEquals("sehen", placed("Ich habe es gesehen.", "gesehen")!!.conjugation.verb.infinitive)
        assertEquals(GermanTableTense.PERFECT, placed("Ich habe es gesehen.", "gesehen")!!.tense)
        assertEquals(GermanTableTense.PRETERITE, placed("Er sah den Mann.", "sah")!!.tense)
        assertEquals("können", placed("Ich kann schwimmen.", "kann")!!.conjugation.verb.infinitive)
    }

    @Test fun punctuationLongLinesAndUnknownWordsAreSafe() {
        assertNull(germanWordRole(GermanMorphology.sentence("Hallo, wie geht es dir?")!!, 0, 0))
        assertNotNull(role("Hallo, wie geht es dir?", "dir"))
        assertNull(role("Xyzzy plugh.", "Xyzzy"))
        val long = "Obwohl es heute sehr kalt war, sind wir mit unseren Freunden, die schon lange hier wohnen, spazieren gegangen."
        assertEquals(GermanRole.SUBORDINATOR, role(long, "Obwohl")!!.role)
        assertEquals(GermanRole.RELATIVE_PRONOUN, role(long, "die")!!.role)
        assertEquals(GermanRole.ADJECTIVE_ATTRIBUTIVE, role("Die kleine Katze schläft.", "kleine")!!.role)
    }
}
