package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every requested verb in all four tenses and six persons, written out from standard German grammar (not from the
 * generator's own output). Forms are compared as sets, so a listed variant such as "du lasest" does not fail a check.
 */
class GermanConjugationCoverageTest {
    private class Spec(
        val infinitive: String,
        val present: String,
        val preterite: String,
        val participle: String,
        val auxiliaries: Set<String>,
        val reflexive: Boolean = false,
    )

    private companion object {
        val lexicon: GermanLexicon by lazy {
            LearnerData.open("de-verbs.txt").use { verbs -> LearnerData.open("de-words.txt").use { loadGermanLexicon(verbs, it) } }
        }

        val HABEN_PRESENT = "habe hast hat haben habt haben".split(" ")
        val SEIN_PRESENT = "bin bist ist sind seid sind".split(" ")
        val HABEN_PRETERITE = "hatte hattest hatte hatten hattet hatten".split(" ")
        val SEIN_PRETERITE = "war warst war waren wart waren".split(" ")

        // present and preterite are the six persons ich du er/sie/es wir ihr sie/Sie; "/" separates accepted variants
        val SPECS =
            listOf(
                Spec("sein", "bin bist ist sind seid sind", "war warst war waren wart waren", "gewesen", setOf("sein")),
                Spec("haben", "habe hast hat haben habt haben", "hatte hattest hatte hatten hattet hatten", "gehabt", setOf("haben")),
                Spec(
                    "werden",
                    "werde wirst wird werden werdet werden",
                    "wurde wurdest wurde wurden wurdet wurden",
                    "geworden",
                    setOf("sein"),
                ),
                Spec("gehen", "gehe gehst geht gehen geht gehen", "ging gingst ging gingen gingt gingen", "gegangen", setOf("sein")),
                Spec(
                    "fahren",
                    "fahre fährst fährt fahren fahrt fahren",
                    "fuhr fuhrst fuhr fuhren fuhrt fuhren",
                    "gefahren",
                    setOf("sein", "haben"),
                ),
                Spec("kommen", "komme kommst kommt kommen kommt kommen", "kam kamst kam kamen kamt kamen", "gekommen", setOf("sein")),
                Spec("sehen", "sehe siehst sieht sehen seht sehen", "sah sahst sah sahen saht sahen", "gesehen", setOf("haben")),
                Spec("lesen", "lese liest liest lesen lest lesen", "las lasest/last las lasen last lasen", "gelesen", setOf("haben")),
                Spec("essen", "esse isst isst essen esst essen", "aß aßest/aßt aß aßen aßt aßen", "gegessen", setOf("haben")),
                Spec(
                    "nehmen",
                    "nehme nimmst nimmt nehmen nehmt nehmen",
                    "nahm nahmst nahm nahmen nahmt nahmen",
                    "genommen",
                    setOf("haben"),
                ),
                Spec("geben", "gebe gibst gibt geben gebt geben", "gab gabst gab gaben gabt gaben", "gegeben", setOf("haben")),
                Spec(
                    "wissen",
                    "weiß weißt weiß wissen wisst wissen",
                    "wusste wusstest wusste wussten wusstet wussten",
                    "gewusst",
                    setOf("haben"),
                ),
                Spec(
                    "sprechen",
                    "spreche sprichst spricht sprechen sprecht sprechen",
                    "sprach sprachst sprach sprachen spracht sprachen",
                    "gesprochen",
                    setOf("haben"),
                ),
                Spec(
                    "helfen",
                    "helfe hilfst hilft helfen helft helfen",
                    "half halfst half halfen halft halfen",
                    "geholfen",
                    setOf("haben"),
                ),
                Spec(
                    "arbeiten",
                    "arbeite arbeitest arbeitet arbeiten arbeitet arbeiten",
                    "arbeitete arbeitetest arbeitete arbeiteten arbeitetet arbeiteten",
                    "gearbeitet",
                    setOf("haben"),
                ),
                Spec(
                    "können",
                    "kann kannst kann können könnt können",
                    "konnte konntest konnte konnten konntet konnten",
                    "gekonnt",
                    setOf("haben"),
                ),
                Spec(
                    "müssen",
                    "muss musst muss müssen müsst müssen",
                    "musste musstest musste mussten musstet mussten",
                    "gemusst",
                    setOf("haben"),
                ),
                Spec(
                    "dürfen",
                    "darf darfst darf dürfen dürft dürfen",
                    "durfte durftest durfte durften durftet durften",
                    "gedurft",
                    setOf("haben"),
                ),
                Spec(
                    "wollen",
                    "will willst will wollen wollt wollen",
                    "wollte wolltest wollte wollten wolltet wollten",
                    "gewollt",
                    setOf("haben"),
                ),
                Spec(
                    "sollen",
                    "soll sollst soll sollen sollt sollen",
                    "sollte solltest sollte sollten solltet sollten",
                    "gesollt",
                    setOf("haben"),
                ),
                Spec(
                    "mögen",
                    "mag magst mag mögen mögt mögen",
                    "mochte mochtest mochte mochten mochtet mochten",
                    "gemocht",
                    setOf("haben"),
                ),
                // Wiktionary lists haben as well (regional, and for "open"); the data says so, so the table shows both
                Spec(
                    "aufstehen",
                    "stehe_auf stehst_auf steht_auf stehen_auf steht_auf stehen_auf",
                    "stand_auf standest_auf/stand_auf stand_auf standen_auf standet_auf standen_auf",
                    "aufgestanden",
                    setOf("sein", "haben"),
                ),
                Spec(
                    "anrufen",
                    "rufe_an rufst_an ruft_an rufen_an ruft_an rufen_an",
                    "rief_an riefst_an rief_an riefen_an rieft_an riefen_an",
                    "angerufen",
                    setOf("haben"),
                ),
                Spec(
                    "mitkommen",
                    "komme_mit kommst_mit kommt_mit kommen_mit kommt_mit kommen_mit",
                    "kam_mit kamst_mit kam_mit kamen_mit kamt_mit kamen_mit",
                    "mitgekommen",
                    setOf("sein"),
                ),
                Spec(
                    "freuen",
                    "freue freust freut freuen freut freuen",
                    "freute freutest freute freuten freutet freuten",
                    "gefreut",
                    setOf("haben"),
                    reflexive = true,
                ),
            )

        val REFLEXIVE = listOf("mich", "dich", "sich", "uns", "euch", "sich")

        fun forms(
            spec: String,
            reflexive: Boolean,
        ): List<Set<String>> =
            spec.split(" ").mapIndexed { i, cell ->
                cell
                    .split("/")
                    .map { variant ->
                        val parts = variant.split("_")
                        val head = if (reflexive) "${parts[0]} ${REFLEXIVE[i]}" else parts[0]
                        (listOf(head) + parts.drop(1)).joinToString(" ")
                    }.toSet()
            }
    }

    private fun table(
        c: GermanConjugation,
        tense: GermanTableTense,
    ) = c.tables
        .firstOrNull { it.tense == tense }
        ?.rows
        ?.map { it.forms.toSet() }

    @Test fun everyRequestedVerbIsCorrectInAllFourTensesAndSixPersons() {
        val problems = mutableListOf<String>()
        for (spec in SPECS) {
            val entry = lexicon.verbs[spec.infinitive]
            if (entry == null) {
                problems += "${spec.infinitive}: missing from the lexicon"
                continue
            }
            val c = germanConjugation(entry, lexicon, spec.reflexive)

            fun check(
                tense: GermanTableTense,
                expected: List<Set<String>>,
            ) {
                val rows = table(c, tense)
                if (rows == null) {
                    problems += "${spec.infinitive} ${tense.name}: no table"
                    return
                }
                expected.forEachIndexed { i, want ->
                    if (want.none { it in rows[i] }) problems += "${spec.infinitive} ${tense.name} #${i + 1}: want $want, got ${rows[i]}"
                }
            }
            check(GermanTableTense.PRESENT, forms(spec.present, spec.reflexive))
            check(GermanTableTense.PRETERITE, forms(spec.preterite, spec.reflexive))
            // compound tenses: each listed auxiliary must appear, and no other one
            for ((tense, hab, sei) in listOf(
                Triple(GermanTableTense.PERFECT, HABEN_PRESENT, SEIN_PRESENT),
                Triple(GermanTableTense.PLUPERFECT, HABEN_PRETERITE, SEIN_PRETERITE),
            )) {
                val rows = table(c, tense)
                if (rows == null) {
                    problems += "${spec.infinitive} ${tense.name}: no table"
                    continue
                }
                val participle = spec.participle
                for (i in 0..5) {
                    val reflexive = if (spec.reflexive) " ${REFLEXIVE[i]}" else ""
                    val expected =
                        spec.auxiliaries.map { aux ->
                            val form = if (aux == "haben") hab[i] else sei[i]
                            "$form$reflexive $participle"
                        }
                    if (rows[i] !=
                        expected.toSet()
                    ) {
                        problems += "${spec.infinitive} ${tense.name} #${i + 1}: want ${expected.toSet()}, got ${rows[i]}"
                    }
                }
            }
        }
        assertTrue("conjugation defects:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
