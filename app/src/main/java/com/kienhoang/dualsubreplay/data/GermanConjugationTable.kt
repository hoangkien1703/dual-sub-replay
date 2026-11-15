package com.kienhoang.dualsubreplay.data

/** The tenses the word card shows as tables. */
internal enum class GermanTableTense { PRESENT, PRETERITE, PERFECT, PLUPERFECT }

/** How a verb conjugates, in the German terms (schwach, stark, gemischt, Modalverb, Präteritopräsens, Hilfsverb). */
internal enum class GermanVerbKind { WEAK, STRONG, MIXED, MODAL, PRETERITE_PRESENT, AUXILIARY }

/** One person of a table: its pronoun and the form(s) after it; the first form is the usual one, the rest are attested variants. */
internal class GermanConjugationRow(
    val pronoun: String,
    val forms: List<String>,
)

internal class GermanConjugationTable(
    val tense: GermanTableTense,
    val rows: List<GermanConjugationRow>,
)

/**
 * The conjugation of a verb: the tables and what the card says about them. [reflexive] is true when the sentence uses the verb with a
 * reflexive pronoun, so the rows carry it. [participles] has more than one entry when a participle stands for the infinitive
 * (Ersatzinfinitiv: hat gekonnt, hat können). [auxiliaryNote] is true when the verb takes both haben and sein.
 */
internal class GermanConjugation(
    val verb: GermanVerb,
    val kind: GermanVerbKind,
    val tables: List<GermanConjugationTable>,
    val reflexive: Boolean,
    val participles: List<String>,
    val auxiliaries: List<String>,
)

private val PERSONS =
    listOf(
        Triple("ich", 1, false),
        Triple("du", 2, false),
        Triple("er/sie/es", 3, false),
        Triple("wir", 1, true),
        Triple("ihr", 2, true),
        Triple("sie/Sie", 3, true),
    )

/** The accusative reflexive pronoun by person and number; the dative (mir, dir) is not guessed. */
private fun reflexivePronoun(
    person: Int,
    plural: Boolean,
): String = listOf("mich", "dich", "sich", "uns", "euch", "sich")[(if (plural) 3 else 0) + person - 1]

private val MODAL_VERBS = setOf("können", "müssen", "wollen", "sollen", "dürfen", "mögen")

internal fun germanVerbKind(verb: GermanVerb): GermanVerbKind =
    when {
        verb.infinitive in setOf("sein", "haben", "werden") -> GermanVerbKind.AUXILIARY
        verb.modal && verb.base in MODAL_VERBS -> GermanVerbKind.MODAL
        verb.modal -> GermanVerbKind.PRETERITE_PRESENT
        verb.hasRegularPast -> GermanVerbKind.WEAK
        verb.past3.endsWith("te") -> GermanVerbKind.MIXED
        else -> GermanVerbKind.STRONG
    }

/**
 * The tables of [verb]: Präsens and Präteritum from its stored and derived forms, Perfekt and Plusquamperfekt from the auxiliary
 * (haben, sein or both, as the lexicon lists them) and the participle. A table is left out rather than guessed: a verb with no
 * auxiliary in the lexicon has no perfect tables, and a form that [GermanVerb] cannot generate is not shown.
 */
internal fun germanConjugation(
    verb: GermanVerb,
    lexicon: GermanLexicon,
    reflexive: Boolean = false,
): GermanConjugation {
    fun rows(
        tense: GermanTense,
        decorate: (String, Int, Boolean) -> String,
    ): List<GermanConjugationRow> =
        PERSONS.mapNotNull { (pronoun, person, plural) ->
            val forms =
                verb.forms[GermanCell(tense, person, plural)]
                    .orEmpty()
                    .map { decorate(it, person, plural) }
                    .distinct()
            if (forms.isEmpty()) null else GermanConjugationRow(pronoun, forms)
        }
    // A separable prefix leaves the verb and ends the clause (ich stehe auf); a reflexive pronoun follows the verb.
    val finite = { form: String, person: Int, plural: Boolean ->
        val core = if (reflexive) form + " " + reflexivePronoun(person, plural) else form
        if (verb.separable) core + " " + verb.prefix else core
    }
    val tables = ArrayList<GermanConjugationTable>()
    rows(GermanTense.PRESENT, finite).takeIf { it.size == PERSONS.size }?.let {
        tables +=
            GermanConjugationTable(GermanTableTense.PRESENT, it)
    }
    rows(GermanTense.PRETERITE, finite).takeIf { it.size == PERSONS.size }?.let {
        tables +=
            GermanConjugationTable(GermanTableTense.PRETERITE, it)
    }
    val auxiliaries = listOfNotNull(if ('h' in verb.auxiliaries) "haben" else null, if ('s' in verb.auxiliaries) "sein" else null)
    val participles = verb.participles
    if (auxiliaries.isNotEmpty()) {
        for ((table, tense) in listOf(
            GermanTableTense.PERFECT to GermanTense.PRESENT,
            GermanTableTense.PLUPERFECT to GermanTense.PRETERITE,
        )) {
            val perfect =
                PERSONS.mapNotNull { (pronoun, person, plural) ->
                    val forms =
                        auxiliaries
                            .flatMap { aux ->
                                val auxiliary = lexicon.verbs[aux] ?: return@flatMap emptyList()
                                auxiliary.forms[GermanCell(tense, person, plural)].orEmpty().take(1).map {
                                    val tail =
                                        if (reflexive) {
                                            reflexivePronoun(
                                                person,
                                                plural,
                                            ) + " " + participles.first()
                                        } else {
                                            participles.first()
                                        }
                                    "$it $tail"
                                }
                            }.distinct()
                    if (forms.isEmpty()) null else GermanConjugationRow(pronoun, forms)
                }
            if (perfect.size == PERSONS.size) tables += GermanConjugationTable(table, perfect)
        }
    }
    return GermanConjugation(verb, germanVerbKind(verb), tables, reflexive, participles, auxiliaries)
}

/** The conjugation of a tapped verb, with the table and row the tapped form belongs to when the line decides it. */
internal class GermanSelectionConjugation(
    val conjugation: GermanConjugation,
    val tense: GermanTableTense?,
    val row: Int?,
)

private fun rowOf(cell: GermanCell): Int = (if (cell.plural) 3 else 0) + cell.person - 1

internal fun germanSelectionConjugation(
    sentence: GermanSentence,
    lexicon: GermanLexicon,
    start: Int,
    end: Int,
): GermanSelectionConjugation? {
    val details = germanWordDetails(sentence, start, end) ?: return null
    val verb = details.verb ?: return null
    val word = sentence.words.firstOrNull { !it.punctuation && it.start < end && it.end > start } ?: return null
    val reflexive =
        sentence.clauseWords(word.index).any { germanWordRole(sentence, it.start, it.end)?.role == GermanRole.REFLEXIVE_PRONOUN }
    val conjugation = germanConjugation(verb, lexicon, reflexive)
    val finite = details.cells.firstOrNull { it.person > 0 && it.tense in setOf(GermanTense.PRESENT, GermanTense.PRETERITE) }
    if (finite != null) {
        val tense = if (finite.tense == GermanTense.PRESENT) GermanTableTense.PRESENT else GermanTableTense.PRETERITE
        return GermanSelectionConjugation(conjugation, tense, rowOf(finite))
    }
    if (details.cells.any { it.tense == GermanTense.PARTICIPLE_II }) {
        // A participle belongs to the perfect tense of the auxiliary in its clause: haben or sein in the present, or in the past.
        val aux =
            sentence.clauseRange(word.index).map { sentence.words[it] }.firstNotNullOfOrNull { w ->
                w.finite.firstOrNull { it.verb.infinitive == "haben" || it.verb.infinitive == "sein" }?.cell
            }
        val tense =
            when (aux?.tense) {
                GermanTense.PRESENT -> GermanTableTense.PERFECT
                GermanTense.PRETERITE -> GermanTableTense.PLUPERFECT
                else -> null
            }
        return GermanSelectionConjugation(conjugation, tense, aux?.let { rowOf(it) })
    }
    return GermanSelectionConjugation(conjugation, null, null)
}
