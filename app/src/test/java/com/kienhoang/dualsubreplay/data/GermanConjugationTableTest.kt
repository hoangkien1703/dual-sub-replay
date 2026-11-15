package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Präsens, Präteritum, Perfekt and Plusquamperfekt tables, checked against forms written from German grammar. */
class GermanConjugationTableTest {
    private companion object {
        val lexicon: GermanLexicon by lazy {
            LearnerData.open("de-verbs.txt").use { verbs -> LearnerData.open("de-words.txt").use { loadGermanLexicon(verbs, it) } }
        }

        fun conjugation(
            infinitive: String,
            reflexive: Boolean = false,
        ): GermanConjugation = germanConjugation(lexicon.verbs.getValue(infinitive), lexicon, reflexive)

        /** The usual (first) form of each of the six persons. */
        fun usual(
            c: GermanConjugation,
            tense: GermanTableTense,
        ): List<String> =
            c.tables
                .first { it.tense == tense }
                .rows
                .map { it.forms.first() }

        fun all(
            c: GermanConjugation,
            tense: GermanTableTense,
        ): List<Set<String>> =
            c.tables
                .first { it.tense == tense }
                .rows
                .map { it.forms.toSet() }
    }

    @Test fun gehenMatchesTheTablesOfTheRequest() {
        val c = conjugation("gehen")
        assertEquals(listOf("gehe", "gehst", "geht", "gehen", "geht", "gehen"), usual(c, GermanTableTense.PRESENT))
        assertEquals(listOf("ging", "gingst", "ging", "gingen", "gingt", "gingen"), usual(c, GermanTableTense.PRETERITE))
        assertEquals(
            listOf("bin gegangen", "bist gegangen", "ist gegangen", "sind gegangen", "seid gegangen", "sind gegangen"),
            usual(c, GermanTableTense.PERFECT),
        )
        assertEquals(
            listOf("war gegangen", "warst gegangen", "war gegangen", "waren gegangen", "wart gegangen", "waren gegangen"),
            usual(c, GermanTableTense.PLUPERFECT),
        )
        assertEquals(
            listOf("ich", "du", "er/sie/es", "wir", "ihr", "sie/Sie"),
            c.tables
                .first()
                .rows
                .map { it.pronoun },
        )
        assertEquals(GermanVerbKind.STRONG, c.kind)
    }

    @Test fun theAuxiliariesAndIrregularStrongVerbs() {
        val expectedPresent =
            mapOf(
                "sein" to listOf("bin", "bist", "ist", "sind", "seid", "sind"),
                "haben" to listOf("habe", "hast", "hat", "haben", "habt", "haben"),
                "werden" to listOf("werde", "wirst", "wird", "werden", "werdet", "werden"),
                "fahren" to listOf("fahre", "fährst", "fährt", "fahren", "fahrt", "fahren"),
                "kommen" to listOf("komme", "kommst", "kommt", "kommen", "kommt", "kommen"),
                "sehen" to listOf("sehe", "siehst", "sieht", "sehen", "seht", "sehen"),
                "lesen" to listOf("lese", "liest", "liest", "lesen", "lest", "lesen"),
                "essen" to listOf("esse", "isst", "isst", "essen", "esst", "essen"),
                "nehmen" to listOf("nehme", "nimmst", "nimmt", "nehmen", "nehmt", "nehmen"),
                "geben" to listOf("gebe", "gibst", "gibt", "geben", "gebt", "geben"),
                "wissen" to listOf("weiß", "weißt", "weiß", "wissen", "wisst", "wissen"),
                "sprechen" to listOf("spreche", "sprichst", "spricht", "sprechen", "sprecht", "sprechen"),
                "helfen" to listOf("helfe", "hilfst", "hilft", "helfen", "helft", "helfen"),
                "arbeiten" to listOf("arbeite", "arbeitest", "arbeitet", "arbeiten", "arbeitet", "arbeiten"),
            )
        val expectedPreterite =
            mapOf(
                "sein" to listOf("war", "warst", "war", "waren", "wart", "waren"),
                "haben" to listOf("hatte", "hattest", "hatte", "hatten", "hattet", "hatten"),
                "werden" to listOf("wurde", "wurdest", "wurde", "wurden", "wurdet", "wurden"),
                "fahren" to listOf("fuhr", "fuhrst", "fuhr", "fuhren", "fuhrt", "fuhren"),
                "kommen" to listOf("kam", "kamst", "kam", "kamen", "kamt", "kamen"),
                "sehen" to listOf("sah", "sahst", "sah", "sahen", "saht", "sahen"),
                "nehmen" to listOf("nahm", "nahmst", "nahm", "nahmen", "nahmt", "nahmen"),
                "geben" to listOf("gab", "gabst", "gab", "gaben", "gabt", "gaben"),
                "wissen" to listOf("wusste", "wusstest", "wusste", "wussten", "wusstet", "wussten"),
                "sprechen" to listOf("sprach", "sprachst", "sprach", "sprachen", "spracht", "sprachen"),
                "helfen" to listOf("half", "halfst", "half", "halfen", "halft", "halfen"),
                "arbeiten" to listOf("arbeitete", "arbeitetest", "arbeitete", "arbeiteten", "arbeitetet", "arbeiteten"),
            )
        for ((verb, forms) in expectedPresent) {
            val rows = all(conjugation(verb), GermanTableTense.PRESENT)
            forms.forEachIndexed { i, form -> assertTrue("$verb Präsens ${i + 1}: $form not in ${rows[i]}", form in rows[i]) }
        }
        for ((verb, forms) in expectedPreterite) {
            val rows = all(conjugation(verb), GermanTableTense.PRETERITE)
            forms.forEachIndexed { i, form -> assertTrue("$verb Präteritum ${i + 1}: $form not in ${rows[i]}", form in rows[i]) }
        }
    }

    @Test fun modalVerbsHavePresentAndPreteriteFormsAndAreMarkedAsModals() {
        val present =
            mapOf(
                "können" to listOf("kann", "kannst", "kann", "können", "könnt", "können"),
                "müssen" to listOf("muss", "musst", "muss", "müssen", "müsst", "müssen"),
                "dürfen" to listOf("darf", "darfst", "darf", "dürfen", "dürft", "dürfen"),
                "wollen" to listOf("will", "willst", "will", "wollen", "wollt", "wollen"),
                "sollen" to listOf("soll", "sollst", "soll", "sollen", "sollt", "sollen"),
                "mögen" to listOf("mag", "magst", "mag", "mögen", "mögt", "mögen"),
            )
        for ((verb, forms) in present) {
            val c = conjugation(verb)
            assertEquals(GermanVerbKind.MODAL, c.kind)
            val rows = all(c, GermanTableTense.PRESENT)
            forms.forEachIndexed { i, form -> assertTrue("$verb Präsens ${i + 1}: $form not in ${rows[i]}", form in rows[i]) }
        }
        assertEquals(
            listOf("konnte", "konntest", "konnte", "konnten", "konntet", "konnten"),
            usual(conjugation("können"), GermanTableTense.PRETERITE),
        )
        // the Ersatzinfinitiv: the infinitive stands for the participle next to another infinitive (hat gekonnt, hat können)
        assertTrue(conjugation("können").participles.containsAll(listOf("gekonnt", "können")))
        assertEquals(GermanVerbKind.PRETERITE_PRESENT, conjugation("wissen").kind)
    }

    @Test fun separableVerbsPutThePrefixAtTheEndAndTheParticipleKeepsIt() {
        val c = conjugation("aufstehen")
        assertEquals(
            listOf("stehe auf", "stehst auf", "steht auf", "stehen auf", "steht auf", "stehen auf"),
            usual(c, GermanTableTense.PRESENT),
        )
        assertTrue("standen auf" in all(c, GermanTableTense.PRETERITE)[5])
        assertTrue("aufgestanden" in c.participles)
        assertTrue(all(c, GermanTableTense.PERFECT)[0].any { it.endsWith("aufgestanden") })
        assertEquals(
            listOf("rufe an", "rufst an", "ruft an", "rufen an", "ruft an", "rufen an"),
            usual(conjugation("anrufen"), GermanTableTense.PRESENT),
        )
        assertTrue("angerufen" in conjugation("anrufen").participles)
    }

    @Test fun aReflexiveVerbCarriesItsReflexivePronounInEveryRow() {
        val c = conjugation("freuen", reflexive = true)
        assertEquals(
            listOf("freue mich", "freust dich", "freut sich", "freuen uns", "freut euch", "freuen sich"),
            usual(c, GermanTableTense.PRESENT),
        )
        assertEquals("habe mich gefreut", usual(c, GermanTableTense.PERFECT)[0])
        assertEquals(
            listOf("freue", "freust", "freut", "freuen", "freut", "freuen"),
            usual(conjugation("freuen"), GermanTableTense.PRESENT),
        )
    }

    @Test fun aVerbThatTakesBothAuxiliariesShowsBothAndNeverFewerThanTheLexiconListsOrMore() {
        val rows = all(conjugation("fahren"), GermanTableTense.PERFECT)
        assertTrue("bin gefahren" in rows[0] && "habe gefahren" in rows[0])
        // a haben-only verb shows only haben
        assertEquals(setOf("habe gesehen"), all(conjugation("sehen"), GermanTableTense.PERFECT)[0])
    }

    @Test fun weakStrongAndMixedVerbsAreTold() {
        assertEquals(GermanVerbKind.WEAK, conjugation("arbeiten").kind)
        assertEquals(GermanVerbKind.STRONG, conjugation("nehmen").kind)
        assertEquals(GermanVerbKind.MIXED, conjugation("bringen").kind)
        assertEquals(GermanVerbKind.AUXILIARY, conjugation("sein").kind)
    }

    private fun selected(
        line: String,
        word: String,
    ): GermanSelectionConjugation? {
        check(GermanMorphology.awaitGrammarReady()) { "German data did not load" }
        val from = line.indexOf(word)
        return GermanMorphology.conjugation(line, from, from + word.length)
    }

    @Test fun theTappedFormIsPlacedInTheTenseOfItsSentence() {
        val present = selected("Ich gehe nach Hause.", "gehe")!!
        assertEquals("gehen", present.conjugation.verb.infinitive)
        assertEquals(GermanTableTense.PRESENT to 0, present.tense to present.row)
        val preterite = selected("Ich ging nach Hause.", "ging")!!
        assertEquals(GermanTableTense.PRETERITE to 0, preterite.tense to preterite.row)
        val perfect = selected("Ich bin nach Hause gegangen.", "gegangen")!!
        assertEquals("gehen", perfect.conjugation.verb.infinitive)
        assertEquals(GermanTableTense.PERFECT to 0, perfect.tense to perfect.row)
        val pluperfect = selected("Wir waren nach Hause gegangen.", "gegangen")!!
        assertEquals(GermanTableTense.PLUPERFECT to 3, pluperfect.tense to pluperfect.row)
    }

    @Test fun aSeparableVerbAndAReflexiveVerbAreFoundFromTheirLine() {
        val separable = selected("Wir stehen früh auf.", "stehen")!!
        assertEquals("aufstehen", separable.conjugation.verb.infinitive)
        assertEquals(3, separable.row)
        assertTrue(selected("Ich freue mich.", "freue")!!.conjugation.reflexive)
        assertEquals(false, selected("Ich gehe nach Hause.", "gehe")!!.conjugation.reflexive)
    }

    @Test fun aWordThatIsNoVerbHasNoConjugation() {
        assertEquals(null, selected("Ich sehe den Mann.", "Mann"))
    }
}
