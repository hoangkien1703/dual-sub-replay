package com.kienhoang.dualsubreplay.data

import kotlin.math.abs

/**
 * The German grammar points of a line: compound tenses, moods, voice, separable and reflexive verbs,
 * infinitive constructions, case after prepositions and verbs, adjective endings, comparison, clause
 * types and question forms. Points may overlap and may be made of words that are not next to each
 * other, so each hit records the words that are its evidence (see [GrammarMatch.evidence]). Levels are
 * the author's CEFR placement (docs/specs/2026-11-14-german-grammar.md).
 */
internal fun germanGrammar(sentence: GermanSentence): List<GrammarMatch> = GermanRules(sentence).matches()

private class Hit(
    val meanings: List<GrammarMeaning>,
    val form: String,
    val level: CefrLevel,
    val words: List<Int>,
    val tentative: Boolean = false,
)

/** Verbs with a fixed preposition and the case it takes: infinitive (without sich), preposition, case. */
private val VERB_PREPOSITIONS =
    listOf(
        Triple("warten", "auf", GermanCase.ACCUSATIVE),
        Triple("denken", "an", GermanCase.ACCUSATIVE),
        Triple("glauben", "an", GermanCase.ACCUSATIVE),
        Triple("hoffen", "auf", GermanCase.ACCUSATIVE),
        Triple("achten", "auf", GermanCase.ACCUSATIVE),
        Triple("antworten", "auf", GermanCase.ACCUSATIVE),
        Triple("freuen", "auf", GermanCase.ACCUSATIVE),
        Triple("freuen", "über", GermanCase.ACCUSATIVE),
        Triple("interessieren", "für", GermanCase.ACCUSATIVE),
        Triple("ärgern", "über", GermanCase.ACCUSATIVE),
        Triple("erinnern", "an", GermanCase.ACCUSATIVE),
        Triple("kümmern", "um", GermanCase.ACCUSATIVE),
        Triple("vorbereiten", "auf", GermanCase.ACCUSATIVE),
        Triple("bewerben", "um", GermanCase.ACCUSATIVE),
        Triple("verlieben", "in", GermanCase.ACCUSATIVE),
        Triple("danken", "für", GermanCase.ACCUSATIVE),
        Triple("sorgen", "für", GermanCase.ACCUSATIVE),
        Triple("entscheiden", "für", GermanCase.ACCUSATIVE),
        Triple("bitten", "um", GermanCase.ACCUSATIVE),
        Triple("diskutieren", "über", GermanCase.ACCUSATIVE),
        Triple("berichten", "über", GermanCase.ACCUSATIVE),
        Triple("nachdenken", "über", GermanCase.ACCUSATIVE),
        Triple("lachen", "über", GermanCase.ACCUSATIVE),
        Triple("beschweren", "über", GermanCase.ACCUSATIVE),
        Triple("träumen", "von", GermanCase.DATIVE),
        Triple("fragen", "nach", GermanCase.DATIVE),
        Triple("suchen", "nach", GermanCase.DATIVE),
        Triple("riechen", "nach", GermanCase.DATIVE),
        Triple("teilnehmen", "an", GermanCase.DATIVE),
        Triple("bestehen", "aus", GermanCase.DATIVE),
        Triple("gehören", "zu", GermanCase.DATIVE),
        Triple("helfen", "bei", GermanCase.DATIVE),
        Triple("abhängen", "von", GermanCase.DATIVE),
        Triple("warnen", "vor", GermanCase.DATIVE),
        Triple("fürchten", "vor", GermanCase.DATIVE),
        Triple("halten", "von", GermanCase.DATIVE),
        Triple("anfangen", "mit", GermanCase.DATIVE),
        Triple("aufhören", "mit", GermanCase.DATIVE),
        Triple("beginnen", "mit", GermanCase.DATIVE),
        Triple("telefonieren", "mit", GermanCase.DATIVE),
        Triple("erzählen", "von", GermanCase.DATIVE),
        Triple("sprechen", "mit", GermanCase.DATIVE),
        Triple("sprechen", "über", GermanCase.ACCUSATIVE),
        Triple("sprechen", "von", GermanCase.DATIVE),
    )

/** Adjectives with a fixed preposition and the case it takes: adjective, preposition, case. */
private val ADJECTIVE_PREPOSITIONS =
    listOf(
        Triple("stolz", "auf", GermanCase.ACCUSATIVE),
        Triple("gespannt", "auf", GermanCase.ACCUSATIVE),
        Triple("neugierig", "auf", GermanCase.ACCUSATIVE),
        Triple("eifersüchtig", "auf", GermanCase.ACCUSATIVE),
        Triple("neidisch", "auf", GermanCase.ACCUSATIVE),
        Triple("wütend", "auf", GermanCase.ACCUSATIVE),
        Triple("sauer", "auf", GermanCase.ACCUSATIVE),
        Triple("böse", "auf", GermanCase.ACCUSATIVE),
        Triple("angewiesen", "auf", GermanCase.ACCUSATIVE),
        Triple("verliebt", "in", GermanCase.ACCUSATIVE),
        Triple("glücklich", "über", GermanCase.ACCUSATIVE),
        Triple("froh", "über", GermanCase.ACCUSATIVE),
        Triple("traurig", "über", GermanCase.ACCUSATIVE),
        Triple("verantwortlich", "für", GermanCase.ACCUSATIVE),
        Triple("bekannt", "für", GermanCase.ACCUSATIVE),
        Triple("typisch", "für", GermanCase.ACCUSATIVE),
        Triple("dankbar", "für", GermanCase.ACCUSATIVE),
        Triple("gewöhnt", "an", GermanCase.ACCUSATIVE),
        Triple("allergisch", "gegen", GermanCase.ACCUSATIVE),
        Triple("interessiert", "an", GermanCase.DATIVE),
        Triple("reich", "an", GermanCase.DATIVE),
        Triple("arm", "an", GermanCase.DATIVE),
        Triple("beteiligt", "an", GermanCase.DATIVE),
        Triple("schuld", "an", GermanCase.DATIVE),
        Triple("überzeugt", "von", GermanCase.DATIVE),
        Triple("begeistert", "von", GermanCase.DATIVE),
        Triple("abhängig", "von", GermanCase.DATIVE),
        Triple("zufrieden", "mit", GermanCase.DATIVE),
        Triple("verheiratet", "mit", GermanCase.DATIVE),
        Triple("verwandt", "mit", GermanCase.DATIVE),
        Triple("fertig", "mit", GermanCase.DATIVE),
        Triple("bereit", "zu", GermanCase.DATIVE),
        Triple("fähig", "zu", GermanCase.DATIVE),
        Triple("freundlich", "zu", GermanCase.DATIVE),
        Triple("nett", "zu", GermanCase.DATIVE),
        Triple("höflich", "zu", GermanCase.DATIVE),
    )

private val DATIVE_VERBS =
    setOf(
        "helfen",
        "danken",
        "gefallen",
        "gehören",
        "antworten",
        "folgen",
        "passen",
        "schmecken",
        "fehlen",
        "gratulieren",
        "begegnen",
        "gelingen",
        "zuhören",
    )

private val DITRANSITIVE_VERBS =
    setOf(
        "geben",
        "zeigen",
        "schenken",
        "bringen",
        "schicken",
        "sagen",
        "erklären",
        "kaufen",
        "leihen",
        "empfehlen",
        "erzählen",
        "schreiben",
        "verkaufen",
    )

private val MODAL_BASES = setOf("können", "müssen", "wollen", "sollen", "dürfen", "mögen")

private val VERBISH_TAGS = setOf("VERB", "AUX", "ADJ")

/** Conjunctions that always send the verb to the end, whatever the model tags them (als, da, damit and the like need SCONJ). */
private val CLEAR_SUBORDINATORS =
    setOf("dass", "weil", "obwohl", "obgleich", "ob", "nachdem", "bevor", "falls", "sobald", "sodass", "indem", "wenn")

/** The model sometimes tags a participle or infinitive ADV (schon gegessen), so those are accepted for non-finite forms. */
private val NON_FINITE_TAGS = VERBISH_TAGS + "ADV"

/** The prefixes of separable verbs; a verb whose "prefix" is anything else (spazieren, hier) is not read as separable. */
private val SEPARABLE_PREFIXES =
    setOf(
        "ab",
        "an",
        "auf",
        "aus",
        "bei",
        "ein",
        "mit",
        "nach",
        "vor",
        "zu",
        "weg",
        "her",
        "hin",
        "los",
        "fest",
        "fort",
        "weiter",
        "zurück",
        "zusammen",
        "um",
        "durch",
        "über",
        "unter",
        "wieder",
        "voran",
        "vorbei",
        "vorüber",
        "vorher",
        "empor",
        "entgegen",
        "entlang",
        "heraus",
        "herein",
        "herauf",
        "herunter",
        "hinaus",
        "hinein",
        "hinauf",
        "hinunter",
        "herüber",
        "hinüber",
        "hervor",
        "heran",
        "herum",
        "umher",
        "nieder",
        "hoch",
        "fern",
        "frei",
        "bereit",
        "dabei",
        "dazu",
        "davon",
        "dahin",
        "daher",
        "dar",
        "gegenüber",
        "gleich",
        "statt",
        "teil",
        "nebenher",
        "auseinander",
        "aneinander",
        "beisammen",
        "zurecht",
        "zuvor",
        "vorweg",
        "hinterher",
        "überein",
    )

/** Words that open a sentence as an adverb even when the model tags them as nouns (Morgen werde ich kommen). */
private val FRONT_ADVERBS =
    setOf(
        "heute",
        "morgen",
        "gestern",
        "jetzt",
        "dann",
        "danach",
        "hier",
        "dort",
        "vielleicht",
        "manchmal",
        "oft",
        "immer",
        "deshalb",
        "deswegen",
        "trotzdem",
        "außerdem",
        "zuerst",
        "später",
        "bald",
        "plötzlich",
        "leider",
        "natürlich",
        "zuletzt",
    )

/** Nouns of time: in dem Jahr is a time, not a place, so a Wechselpräposition card (wo?) would be wrong. */
private val TIME_NOUNS =
    setOf(
        "Jahr",
        "Monat",
        "Woche",
        "Tag",
        "Stunde",
        "Minute",
        "Sekunde",
        "Moment",
        "Augenblick",
        "Wochenende",
        "Abend",
        "Morgen",
        "Nacht",
        "Mittag",
        "Nachmittag",
        "Vormittag",
        "Sommer",
        "Winter",
        "Frühling",
        "Herbst",
        "Januar",
        "Februar",
        "März",
        "April",
        "Mai",
        "Juni",
        "Juli",
        "August",
        "September",
        "Oktober",
        "November",
        "Dezember",
        "Montag",
        "Dienstag",
        "Mittwoch",
        "Donnerstag",
        "Freitag",
        "Samstag",
        "Sonntag",
        "Zeit",
        "Jahrhundert",
        "Semester",
    )

private class GermanRules(
    private val s: GermanSentence,
) {
    private val w = s.words
    private val hits = ArrayList<Hit>()
    private val consumed = HashSet<Int>()
    private val governedPrepositions = HashSet<Int>()

    fun matches(): List<GrammarMatch> {
        compoundTenses()
        modalAndInfinitives()
        separableAndReflexive()
        verbPrepositions()
        adjectivePrepositions()
        simpleTenses()
        moods()
        sentenceForms()
        subordinateClauses()
        prepositions()
        objects()
        nounPhrases()
        comparison()
        small()
        return hits
            .distinctBy { it.meanings to it.words.distinct().sorted() }
            .map(::toMatch)
            .sortedWith(compareBy({ it.start }, { -it.end }))
    }

    private fun toMatch(hit: Hit): GrammarMatch {
        val ordered = hit.words.distinct().sorted()
        val ranges = ordered.map { w[it].start until w[it].end }
        return GrammarMatch(
            hit.form,
            w[ordered.first()].start,
            w[ordered.last()].end,
            hit.meanings,
            GERMAN_RULE_LEVELS[hit.meanings.first()] ?: hit.level,
            ranges,
            hit.tentative,
        )
    }

    private fun add(
        meaning: GrammarMeaning,
        form: String,
        level: CefrLevel,
        words: List<Int>,
        tentative: Boolean = false,
        vararg also: GrammarMeaning,
    ) {
        hits += Hit(listOf(meaning) + also, form, level, words, tentative)
    }

    // ---- helpers

    private fun aux(
        i: Int,
        lemma: String,
    ): List<GermanVerbReading> = w[i].finite.filter { it.verb.infinitive == lemma && !it.attached }

    private fun isModalForm(i: Int): Boolean = w[i].finite.any { it.verb.modal && it.verb.base in MODAL_BASES }

    private fun nearest(
        clause: IntRange,
        near: Int,
        test: (GermanWord) -> Boolean,
    ): Int? =
        clause.filter { it != near && !w[it].punctuation && w[it].tag in NON_FINITE_TAGS && test(w[it]) }.minByOrNull { abs(it - near) }

    private fun participle(
        clause: IntRange,
        near: Int,
    ) = nearest(clause, near) { it.verbWith(GermanTense.PARTICIPLE_II).isNotEmpty() && it.lower != "worden" }

    private fun infinitive(
        clause: IntRange,
        near: Int,
        after: Boolean = false,
    ): Int? = nearest(clause, near) { it.verbWith(GermanTense.INFINITIVE).isNotEmpty() && (!after || it.index > near) }

    private fun tensesOf(readings: List<GermanVerbReading>) = readings.map { it.cell.tense }.toSet()

    // ---- compound tenses, voice

    private fun compoundTenses() {
        for (i in w.indices) {
            if (w[i].punctuation) continue
            val clause = s.clauseRange(i)
            val haben = aux(i, "haben")
            val sein = aux(i, "sein")
            val werden = aux(i, "werden")
            if (haben.isNotEmpty()) {
                val tenses = tensesOf(haben)
                val doubleInfinitive = doubleInfinitive(clause, i)
                val part = participle(clause, i)
                when {
                    doubleInfinitive != null && w[i].verbWith(GermanTense.SUBJUNCTIVE_II).isNotEmpty() -> {
                        add(
                            GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT,
                            "hätte … Infinitiv + Modalverb",
                            CefrLevel.B2,
                            listOf(i) + doubleInfinitive,
                            false,
                            GrammarMeaning.DE_ERSATZINFINITIV,
                        )
                        consumed += i
                        consumed += doubleInfinitive
                    }
                    doubleInfinitive != null && (GermanTense.PRESENT in tenses || GermanTense.PRETERITE in tenses) -> {
                        add(GrammarMeaning.DE_ERSATZINFINITIV, "haben … Infinitiv + Modalverb", CefrLevel.B2, listOf(i) + doubleInfinitive)
                        consumed += i
                        consumed += doubleInfinitive
                    }
                    part != null -> {
                        if (pastWith(tenses, "haben", i, part)) consumed += i
                    }
                }
            }
            if (sein.isNotEmpty()) compoundWithSein(i, sein, clause)
            if (werden.isNotEmpty()) compoundWithWerden(i, werden, clause)
        }
    }

    /** The two infinitives that end a double-infinitive clause (wissen müssen), or null. */
    private fun doubleInfinitive(
        clause: IntRange,
        near: Int,
    ): List<Int>? {
        val infinitives =
            clause.filter {
                it > near && !w[it].punctuation && w[it].verbWith(GermanTense.INFINITIVE).isNotEmpty() &&
                    w[it].tag in NON_FINITE_TAGS
            }
        for (k in 0 until infinitives.size - 1) {
            val modal = infinitives[k + 1]
            if (infinitives[k] + 1 == modal || infinitives[k] < modal) {
                if (w[modal]
                        .verbWith(
                            GermanTense.INFINITIVE,
                        ).any { it.verb.modal && it.verb.base in MODAL_BASES }
                ) {
                    return listOf(infinitives[k], modal)
                }
            }
        }
        return null
    }

    private fun pastWith(
        tenses: Set<GermanTense>,
        auxiliary: String,
        i: Int,
        part: Int,
        vararg also: GrammarMeaning,
    ): Boolean {
        val form = if (auxiliary == "haben") "haben + Partizip II" else "sein + Partizip II"
        val done =
            when {
                GermanTense.SUBJUNCTIVE_II in tenses && GermanTense.PRETERITE !in tenses -> {
                    add(
                        GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT,
                        if (auxiliary ==
                            "haben"
                        ) {
                            "hätte + Partizip II"
                        } else {
                            "wäre + Partizip II"
                        },
                        CefrLevel.B2,
                        listOf(i, part),
                        false,
                        *also,
                    )
                    true
                }
                GermanTense.PRESENT in tenses -> {
                    add(GrammarMeaning.DE_PERFEKT, form, CefrLevel.A2, listOf(i, part), false, *also)
                    true
                }
                GermanTense.PRETERITE in tenses -> {
                    add(
                        GrammarMeaning.DE_PLUSQUAMPERFEKT,
                        if (auxiliary ==
                            "haben"
                        ) {
                            "hatte + Partizip II"
                        } else {
                            "war + Partizip II"
                        },
                        CefrLevel.B1,
                        listOf(i, part),
                        false,
                        *also,
                    )
                    true
                }
                else -> false
            }
        // The participle belongs to the perfect, so it gets no simple-tense card of its own (erreicht is also present 3rd person).
        if (done) consumed += part
        return done
    }

    private fun isAdjectival(part: Int): Boolean {
        val intensified = part > 0 && w[part - 1].lower in GERMAN_INTENSIFIERS
        val governs = ADJECTIVE_PREPOSITIONS.any { it.first == w[part].lower && w.getOrNull(part + 1)?.lower == it.second }
        return intensified || governs
    }

    private fun compoundWithSein(
        i: Int,
        sein: List<GermanVerbReading>,
        clause: IntRange,
    ) {
        val part = participle(clause, i) ?: return
        val tenses = tensesOf(sein)
        val worden = clause.firstOrNull { w[it].lower == "worden" }
        if (worden != null) {
            add(GrammarMeaning.DE_PASSIV_PERFEKT, "sein + Partizip II + worden", CefrLevel.B2, listOf(i, part, worden))
            consumed += i
            return
        }
        val verbs = w[part].verbWith(GermanTense.PARTICIPLE_II).map { it.verb }
        val movesWithSein = verbs.any { it.takesSein }
        if (movesWithSein) {
            // A verb that takes sein (gehen, fahren, kommen) makes the perfect; one that takes both can also be read as a state.
            val alsoState = verbs.any { it.takesSein && 'h' in it.auxiliaries }
            if (pastWith(tenses, "sein", i, part, *(if (alsoState) arrayOf(GrammarMeaning.DE_ZUSTANDSPASSIV) else emptyArray()))) {
                consumed +=
                    i
            }
        } else if (GermanTense.PRESENT in tenses || GermanTense.PRETERITE in tenses) {
            // A participle with a degree word (sehr erschöpft) or an adjective's own preposition (begeistert von) is an adjective.
            if (isAdjectival(part)) return
            // sein + Partizip II of a haben-verb is a state (Das Haus ist gebaut), but a predicate adjective reads the same.
            add(GrammarMeaning.DE_ZUSTANDSPASSIV, "sein + Partizip II", CefrLevel.B2, listOf(i, part), true)
            consumed += i
        }
    }

    private fun compoundWithWerden(
        i: Int,
        werden: List<GermanVerbReading>,
        clause: IntRange,
    ) {
        val tenses = tensesOf(werden)
        val part = participle(clause, i)
        val infinitive = infinitive(clause, i)
        if (part != null) {
            val after = clause.filter { it > part && w[it].lower in setOf("haben", "sein") }
            if (GermanTense.PRESENT in tenses && after.isNotEmpty()) {
                add(GrammarMeaning.DE_FUTUR_II, "werden + Partizip II + haben/sein", CefrLevel.B2, listOf(i, part, after.first()))
            } else if (GermanTense.PRESENT in tenses || GermanTense.PRETERITE in tenses || GermanTense.SUBJUNCTIVE_II in tenses) {
                add(GrammarMeaning.DE_VORGANGSPASSIV, "werden + Partizip II", CefrLevel.B1, listOf(i, part))
            } else {
                return
            }
            consumed += i
        } else if (infinitive != null && w[infinitive].lower != "werden") {
            when {
                GermanTense.SUBJUNCTIVE_II in tenses && GermanTense.PRETERITE !in tenses ->
                    add(GrammarMeaning.DE_WUERDE_INFINITIV, "würde + Infinitiv", CefrLevel.B1, listOf(i, infinitive))
                GermanTense.PRESENT in tenses -> add(GrammarMeaning.DE_FUTUR_I, "werden + Infinitiv", CefrLevel.A2, listOf(i, infinitive))
                else -> return
            }
            consumed += i
            consumed += infinitive
        }
    }

    private fun modalAndInfinitives() {
        for (i in w.indices) {
            if (i in consumed || !isModalForm(i)) continue
            val infinitive = infinitive(s.clauseRange(i), i) ?: continue
            add(GrammarMeaning.DE_MODALVERB, "Modalverb + Infinitiv", CefrLevel.A2, listOf(i, infinitive))
            consumed += i
            consumed += infinitive
        }
        for (i in 0 until w.size - 1) {
            if (w[i].lower != "zu" || w[i + 1].verbWith(GermanTense.INFINITIVE).isEmpty() || w[i + 1].tag !in NON_FINITE_TAGS) continue
            val clause = s.clauseRange(i)
            val lead = clause.firstOrNull { it < i && w[it].lower in setOf("um", "ohne", "statt", "anstatt") }
            when (lead?.let { w[it].lower }) {
                "um" -> add(GrammarMeaning.DE_UM_ZU, "um … zu + Infinitiv", CefrLevel.B1, listOf(lead, i, i + 1))
                "ohne" -> add(GrammarMeaning.DE_OHNE_ZU, "ohne … zu + Infinitiv", CefrLevel.B2, listOf(lead, i, i + 1))
                "statt", "anstatt" -> add(GrammarMeaning.DE_OHNE_ZU, "(an)statt … zu + Infinitiv", CefrLevel.B2, listOf(lead, i, i + 1))
                else -> add(GrammarMeaning.DE_ZU_INFINITIV, "zu + Infinitiv", CefrLevel.B1, listOf(i, i + 1))
            }
            consumed += i + 1
        }
    }

    private fun separableAndReflexive() {
        for (i in w.indices) {
            if (w[i].punctuation || w[i].tag == "NOUN") continue
            val clause = s.clauseRange(i)
            val separable =
                w[i].finite.filter {
                    it.verb.separable && it.verb.prefix in SEPARABLE_PREFIXES && !it.attached &&
                        it.cell.tense in
                        setOf(GermanTense.PRESENT, GermanTense.PRETERITE, GermanTense.IMPERATIVE, GermanTense.SUBJUNCTIVE_II)
                }
            for (reading in separable) {
                // The particle ends the clause (Ich stehe früh auf); a preposition with its noun phrase after it does not.
                val last = clause.last { !w[it].punctuation }
                val particle = clause.firstOrNull { it > i && it == last && w[it].lower == reading.verb.prefix } ?: continue
                add(GrammarMeaning.DE_TRENNBARES_VERB, reading.verb.infinitive, CefrLevel.A2, listOf(i, particle))
                consumed += i
                break
            }
            val reflexiveReadings =
                w[i].finite.filter {
                    it.verb.reflexive && !it.verb.modal && it.verb.infinitive !in setOf("haben", "sein", "werden", "tun") &&
                        (it.cell.tense != GermanTense.IMPERATIVE || i == firstOfSentence(i))
                }
            for (reading in reflexiveReadings) {
                val pronoun = clause.firstOrNull { it != i && w[it].lower in reflexiveFor(reading.cell) } ?: continue
                add(GrammarMeaning.DE_REFLEXIVES_VERB, "sich " + reading.verb.infinitive, CefrLevel.A2, listOf(i, pronoun))
                break
            }
        }
    }

    private fun reflexiveFor(cell: GermanCell): Set<String> =
        when {
            cell.person == 1 && !cell.plural -> setOf("mich", "mir")
            cell.person == 2 && !cell.plural -> setOf("dich", "dir")
            cell.person == 3 -> setOf("sich")
            cell.person == 1 -> setOf("uns")
            else -> setOf("euch")
        }

    // ---- verb + preposition, objects, prepositions

    /** The noun phrase or pronoun that follows the word at [i] (a preposition), with its words and cases; null if none. */
    private fun complementAfter(i: Int): Triple<List<Int>, Set<GermanCase>, Boolean>? {
        val j = i + 1
        if (j >= w.size || w[j].punctuation || s.clauseOf[j] != s.clauseOf[i]) return null
        GERMAN_PRONOUN_CASES[w[j].lower]?.let { return Triple(listOf(j), it, true) }
        val contraction = GERMAN_CONTRACTIONS[w[i].lower]?.second
        for (k in j until minOf(w.size, j + 5)) {
            if (w[k].punctuation || s.clauseOf[k] != s.clauseOf[i]) return null
            if (w[k].nouns.isNotEmpty()) {
                val phrase = germanNounPhrase(s, k, contraction) ?: return null
                if (phrase.first != j) return null
                return Triple(phrase.indices, phrase.cases, phrase.consistent)
            }
            if (w[k].lower !in GERMAN_DETERMINERS && w[k].adjectives.none { it.ending.isNotEmpty() }) return null
        }
        return null
    }

    private fun verbPrepositions() {
        for (i in w.indices) {
            if (w[i].punctuation) continue
            val clause = s.clauseRange(i)
            for ((lemma, preposition, case) in VERB_PREPOSITIONS) {
                if (w[i].verbs.none { it.verb.infinitive == lemma }) continue
                if (isPredicateAdjective(i)) continue
                val pronominal = (if (preposition.first() in "aeiouäöü") "dar" else "da") + preposition
                val at =
                    clause.firstOrNull {
                        it != i &&
                            (w[it].lower == pronominal || w[it].lower == preposition && w[it].tag in setOf("ADP", "ADV", "PART", "X"))
                    }
                        ?: continue
                // darauf, daran, ... stand for the preposition and its object, so the case is the verb's.
                val complement = if (w[at].lower == pronominal) Triple(emptyList<Int>(), setOf(case), true) else complementAfter(at)
                val ok = complement != null && case in complement.second
                add(
                    if (case == GermanCase.ACCUSATIVE) GrammarMeaning.DE_VERB_PREP_AKKUSATIV else GrammarMeaning.DE_VERB_PREP_DATIV,
                    "$lemma $preposition + " + if (case == GermanCase.ACCUSATIVE) "Akkusativ" else "Dativ",
                    CefrLevel.B1,
                    listOf(i, at) + (if (ok) complement!!.first else emptyList()),
                    !ok,
                )
                governedPrepositions += at
                break
            }
        }
    }

    /** Whether sein, werden or bleiben stands in the clause of [i], so that an adjective there is a predicate. */
    private fun hasLinkingVerb(i: Int): Boolean =
        s.clauseRange(i).any { c -> w[c].finite.any { it.verb.infinitive in setOf("sein", "werden", "bleiben") } }

    /** A word with an adjective reading of its own spelling after sein, werden or bleiben (ist verliebt). */
    private fun isPredicateAdjective(i: Int): Boolean =
        w[i].adjectives.any { it.degree == GermanDegree.POSITIVE && it.ending.isEmpty() } && hasLinkingVerb(i)

    private fun adjectivePrepositions() {
        for (i in 0 until w.size - 1) {
            if (w[i].punctuation || !hasLinkingVerb(i)) continue
            val entry = ADJECTIVE_PREPOSITIONS.firstOrNull { it.first == w[i].lower && it.second == w[i + 1].lower } ?: continue
            val complement = complementAfter(i + 1)
            val ok = complement != null && entry.third in complement.second
            val accusative = entry.third == GermanCase.ACCUSATIVE
            add(
                if (accusative) GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV else GrammarMeaning.DE_ADJEKTIV_PREP_DATIV,
                "${entry.first} ${entry.second} + " + if (accusative) "Akkusativ" else "Dativ",
                CefrLevel.B1,
                listOf(i, i + 1) + (if (ok) complement!!.first else emptyList()),
                !ok,
            )
            governedPrepositions += i + 1
        }
    }

    /** A preposition right after a predicate adjective is the adjective's (stolz auf), not a place or a direction. */
    private fun followsPredicateAdjective(i: Int): Boolean = i > 0 && w[i - 1].tag == "ADJ" && hasLinkingVerb(i)

    private fun prepositions() {
        for (i in w.indices) {
            if (i in governedPrepositions || w[i].punctuation) continue
            val government = governmentOf(i) ?: continue
            if (startsInfinitiveClause(i)) continue
            if (government == GermanGovernment.TWO_WAY && followsPredicateAdjective(i)) continue
            val (words, complementCases, _) = complementAfter(i) ?: continue
            // A prepositional complement is never the subject, so nominative is no reading here.
            val cases = complementCases - GermanCase.NOMINATIVE
            if (cases.isEmpty()) continue
            if (government == GermanGovernment.TWO_WAY && words.any { k -> w[k].nouns.any { it.noun.lemma in TIME_NOUNS } }) continue
            val form = (GERMAN_CONTRACTIONS[w[i].lower]?.first ?: w[i].lower) + " + "
            prepositionCard(government, form, cases, listOf(i) + words)
        }
    }

    /** What the preposition (or the one contracted into im, zum) governs, or null when the word is not used as one. */
    private fun governmentOf(i: Int): GermanGovernment? {
        val contraction = GERMAN_CONTRACTIONS[w[i].lower]
        val government = GERMAN_PREPOSITIONS[w[i].lower] ?: contraction?.first?.let { GERMAN_PREPOSITIONS[it] } ?: return null
        return government.takeIf { w[i].tag in setOf("ADP", "SCONJ", "PART", "ADV") || contraction != null }
    }

    /** ohne/(an)statt/um … zu + Infinitiv is a clause, not a prepositional phrase. */
    private fun startsInfinitiveClause(i: Int): Boolean =
        w[i].lower in setOf("ohne", "statt", "anstatt", "um") &&
            s.clauseRange(i).any {
                it > i && w[it].lower == "zu" &&
                    w.getOrNull(it + 1)?.verbWith(GermanTense.INFINITIVE)?.isNotEmpty() == true
            }

    private fun prepositionCard(
        government: GermanGovernment,
        form: String,
        cases: Set<GermanCase>,
        phrase: List<Int>,
    ) {
        when (government) {
            GermanGovernment.ACCUSATIVE ->
                if (GermanCase.ACCUSATIVE in cases) add(GrammarMeaning.DE_PREP_AKKUSATIV, form + "Akkusativ", CefrLevel.A2, phrase)
            GermanGovernment.DATIVE ->
                if (GermanCase.DATIVE in cases) add(GrammarMeaning.DE_PREP_DATIV, form + "Dativ", CefrLevel.A1, phrase)
            GermanGovernment.GENITIVE ->
                if (GermanCase.GENITIVE in cases) add(GrammarMeaning.DE_PREP_GENITIV, form + "Genitiv", CefrLevel.B1, phrase)
            GermanGovernment.TWO_WAY -> twoWayCard(form, cases, phrase)
        }
    }

    private fun twoWayCard(
        form: String,
        cases: Set<GermanCase>,
        phrase: List<Int>,
    ) {
        val accusative = GermanCase.ACCUSATIVE in cases
        val dative = GermanCase.DATIVE in cases
        when {
            accusative && !dative -> add(GrammarMeaning.DE_WECHSEL_AKKUSATIV, form + "Akkusativ (wohin?)", CefrLevel.A2, phrase)
            dative && !accusative -> add(GrammarMeaning.DE_WECHSEL_DATIV, form + "Dativ (wo?)", CefrLevel.A2, phrase)
            accusative && dative ->
                add(
                    GrammarMeaning.DE_WECHSEL_AKKUSATIV,
                    form + "Akkusativ / Dativ",
                    CefrLevel.A2,
                    phrase,
                    true,
                    GrammarMeaning.DE_WECHSEL_DATIV,
                )
        }
    }

    private fun phraseAt(i: Int): GermanNounPhrase? {
        if (i >= w.size || w[i].punctuation) return null
        val head =
            (
                i until
                    minOf(
                        w.size,
                        i + 5,
                    )
            ).takeWhile { !w[it].punctuation && s.clauseOf[it] == s.clauseOf[i] }.firstOrNull { w[it].nouns.isNotEmpty() }
                ?: return null
        val phrase = germanNounPhrase(s, head) ?: return null
        return phrase.takeIf { it.first == i }
    }

    private fun objects() {
        for (i in w.indices) {
            if (w[i].punctuation) continue
            val lemmas = w[i].finite.map { it.verb.infinitive }.toSet()
            if (lemmas.isEmpty()) continue
            // The noun phrases after the verb, in order, with their cases (pronouns count as phrases).
            val phrases = ArrayList<Pair<List<Int>, Set<GermanCase>>>()
            var j = i + 1
            while (j < w.size && s.clauseOf[j] == s.clauseOf[i] && !w[j].punctuation) {
                val phrase = phraseAt(j)
                when {
                    phrase != null -> {
                        phrases += phrase.indices to phrase.cases
                        j = phrase.noun + 1
                    }
                    w[j].lower in GERMAN_PRONOUN_CASES && w[j].lower !in setOf("er", "ich", "du", "wir") -> {
                        phrases += listOf(j) to GERMAN_PRONOUN_CASES.getValue(w[j].lower)
                        j++
                    }
                    else -> j++
                }
            }
            if (lemmas.any { it in DITRANSITIVE_VERBS }) {
                val dative = phrases.indexOfFirst { GermanCase.DATIVE in it.second }
                val accusative = phrases.indexOfFirst { GermanCase.ACCUSATIVE in it.second }
                if (dative >= 0 && accusative > dative) {
                    add(
                        GrammarMeaning.DE_DATIV_AKKUSATIV,
                        "Dativ + Akkusativ",
                        CefrLevel.A2,
                        listOf(i) + phrases[dative].first + phrases[accusative].first,
                    )
                    continue
                }
            }
            if (lemmas.any { it in DATIVE_VERBS }) {
                val dative = phrases.firstOrNull { GermanCase.DATIVE in it.second } ?: continue
                add(GrammarMeaning.DE_DATIV_VERB, lemmas.first { it in DATIVE_VERBS } + " + Dativ", CefrLevel.A2, listOf(i) + dative.first)
            }
        }
    }

    // ---- noun phrases, comparison

    private fun nounPhrases() {
        for (k in w.indices) {
            val base = germanNounPhrase(s, k) ?: continue
            val before = base.first - 1
            // im, zum, am are preposition and article in one (im nächsten Jahr): the contracted article is the determiner.
            val implied = if (base.determiner == null && before >= 0) GERMAN_CONTRACTIONS[w[before].lower]?.second else null
            val phrase = if (implied != null) germanNounPhrase(s, k, implied) ?: base else base
            val previous = phrase.first - 1
            // Adjective endings
            if (phrase.adjectives.isNotEmpty()) {
                val meaning =
                    when (phrase.declension) {
                        GermanDeclension.WEAK -> GrammarMeaning.DE_ADJEKTIV_SCHWACH
                        GermanDeclension.MIXED -> GrammarMeaning.DE_ADJEKTIV_GEMISCHT
                        GermanDeclension.STRONG -> GrammarMeaning.DE_ADJEKTIV_STARK
                    }
                val level = if (phrase.declension == GermanDeclension.STRONG) CefrLevel.B1 else CefrLevel.A2
                add(meaning, "Adjektivendung", level, phrase.indices, !phrase.consistent)
            }
            // A genitive attribute: Die Bücher der Kinder
            val noun = previous.takeIf { it >= 0 && w[it].nouns.isNotEmpty() && s.clauseOf[it] == s.clauseOf[k] } ?: continue
            val determiner = phrase.determiner ?: continue
            val genitiveDeterminer =
                GERMAN_DETERMINERS[w[determiner].lower].orEmpty().any { d ->
                    d.features.any {
                        it.case ==
                            GermanCase.GENITIVE
                    }
                }
            if (!genitiveDeterminer || GermanCase.GENITIVE !in phrase.cases) continue
            val unique = phrase.cases == setOf(GermanCase.GENITIVE)
            val head = germanNounPhrase(s, noun)
            add(
                GrammarMeaning.DE_GENITIVATTRIBUT,
                "Substantiv + Genitiv",
                CefrLevel.B1,
                (head?.indices ?: listOf(noun)) + phrase.indices,
                !unique || !phrase.consistent,
            )
        }
    }

    private fun comparison() {
        for (i in w.indices) {
            val readings = w[i].adjectives
            if (readings.isEmpty()) continue
            // leider and similar adverbs are no longer comparatives of their adjective.
            val comparative = if (w[i].lower in FRONT_ADVERBS) emptyList() else readings.filter { it.degree == GermanDegree.COMPARATIVE }
            if (comparative.isNotEmpty()) {
                val next = i + 1
                val als = next < w.size && w[next].lower == "als"
                val alsoPositive = readings.any { it.degree == GermanDegree.POSITIVE && it.ending.isNotEmpty() }
                val attributive = next < w.size && (w[next].nouns.isNotEmpty() || w[next].adjectives.isNotEmpty())
                // schöner Tag is a strong ending; the comparative needs als or a position where no noun follows.
                if (als || !alsoPositive ||
                    !attributive
                ) {
                    add(
                        GrammarMeaning.DE_KOMPARATIV,
                        "Komparativ",
                        CefrLevel.A2,
                        listOf(i) + if (als) listOf(next) else emptyList(),
                    )
                }
            }
            // nächste (Woche) is "next", a word of its own, not a comparison.
            if (readings.any { it.degree == GermanDegree.SUPERLATIVE } && !w[i].lower.startsWith("nächst")) {
                val am = i > 0 && w[i - 1].lower == "am"
                add(GrammarMeaning.DE_SUPERLATIV, "Superlativ", CefrLevel.A2, listOfNotNull(if (am) i - 1 else null, i))
            }
        }
    }

    // ---- simple tenses and moods

    private fun simpleTenses() {
        for (i in w.indices) {
            if (i in consumed || w[i].punctuation || w[i].tag !in VERBISH_TAGS || w[i].tag == "ADJ") continue
            val tenses = tensesOf(w[i].finite)
            if (GermanTense.PRESENT in tenses) {
                if (i == firstOfSentence(i) && GermanTense.IMPERATIVE in tenses && isCommand(i)) continue
                add(
                    GrammarMeaning.DE_PRAESENS,
                    "Präsens",
                    CefrLevel.A1,
                    listOf(i),
                    false,
                    *(
                        if (GermanTense.SUBJUNCTIVE_I in
                            tenses
                        ) {
                            arrayOf(GrammarMeaning.DE_KONJUNKTIV_I)
                        } else {
                            emptyArray()
                        }
                    ),
                )
            } else if (GermanTense.PRETERITE in tenses) {
                add(
                    GrammarMeaning.DE_PRAETERITUM,
                    "Präteritum",
                    CefrLevel.A2,
                    listOf(i),
                    false,
                    *(
                        if (GermanTense.SUBJUNCTIVE_II in
                            tenses
                        ) {
                            arrayOf(GrammarMeaning.DE_KONJUNKTIV_II)
                        } else {
                            emptyArray()
                        }
                    ),
                )
            } else if (GermanTense.SUBJUNCTIVE_II in tenses) {
                add(GrammarMeaning.DE_KONJUNKTIV_II, "Konjunktiv II", CefrLevel.B1, listOf(i))
            }
        }
    }

    private fun firstOfSentence(i: Int): Int = sentenceStarts().lastOrNull { it <= i } ?: 0

    private fun sentenceStarts(): List<Int> {
        val starts = ArrayList<Int>()
        var expect = true
        for (i in w.indices) {
            if (w[i].punctuation && w[i].text in "\"'„“«(") continue
            if (expect && !w[i].punctuation) {
                starts += i
                expect = false
            }
            if (w[i].punctuation && w[i].text in ".!?") expect = true
        }
        return starts
    }

    private fun isCommand(i: Int): Boolean {
        val next = w.getOrNull(i + 1)
        val subject = next != null && next.lower in setOf("ich", "er", "sie", "es", "wir", "man", "du", "ihr") && next.text != "Sie"
        return w[i].tag == "VERB" && s.endMark(i) != "?" && !subject
    }

    private fun moods() {
        for (i in w.indices) {
            if (w[i].punctuation) continue
            imperative(i)
            reportedSpeech(i)
        }
        unrealConditions()
    }

    /** Imperativ: a verb that opens the sentence, in imperative form, without a subject after it. */
    private fun imperative(i: Int) {
        if (i != firstOfSentence(i)) return
        if (w[i].verbWith(GermanTense.IMPERATIVE).isNotEmpty() && isCommand(i)) {
            add(GrammarMeaning.DE_IMPERATIV, "Imperativ", CefrLevel.A1, listOf(i))
        } else if (isPoliteCommand(i)) {
            add(GrammarMeaning.DE_IMPERATIV, "Imperativ (Sie)", CefrLevel.A1, listOf(i, i + 1))
        }
    }

    private fun isPoliteCommand(i: Int): Boolean =
        w[i].tag == "VERB" &&
            w[i].verbWith(GermanTense.PRESENT, 3).any { it.cell.plural } &&
            w.getOrNull(i + 1)?.text == "Sie" &&
            s.endMark(i) != "?"

    /** Konjunktiv I: a form that is not the indicative, after a third-person subject (Er sagte, er sei krank). */
    private fun reportedSpeech(i: Int) {
        if (w[i].verbWith(GermanTense.SUBJUNCTIVE_I, 3).none { !it.cell.plural }) return
        // A capital Sie inside a sentence is the polite you (Gehen Sie bitte), not a third person.
        val formalSie = i > 0 && w[i - 1].text == "Sie" && !s.isSentenceStart(i - 1)
        val indicative = w[i].verbWith(GermanTense.PRESENT, 3).any { !it.cell.plural }
        val subject = i > 0 && w[i - 1].lower in GERMAN_THIRD_PERSON_SUBJECTS
        if (formalSie || indicative || !subject || w[i].lower == "bitte" || w[i].tag !in VERBISH_TAGS) return
        add(GrammarMeaning.DE_KONJUNKTIV_I, "Konjunktiv I (indirekte Rede)", CefrLevel.B2, listOf(i - 1, i))
        consumed += i
    }

    /** An unreal condition: Wenn ich Zeit hätte, würde ich kommen. */
    private fun unrealConditions() {
        for (i in w.indices) {
            if (w[i].lower != "wenn" || w[i].punctuation) continue
            val clause = s.clauseRange(i)
            val subjunctive =
                clause.firstOrNull {
                    it > i && w[it].verbWith(GermanTense.SUBJUNCTIVE_II).isNotEmpty() && w[it].verbWith(GermanTense.PRETERITE).isEmpty()
                } ?: continue
            val main = (clause.last + 1 until w.size).firstOrNull { !w[it].punctuation && isSubjunctiveTwo(it) } ?: continue
            add(
                GrammarMeaning.DE_KONDITIONAL_IRREAL,
                "wenn … Konjunktiv II, … würde/Konjunktiv II",
                CefrLevel.B1,
                listOf(i, subjunctive, main),
            )
        }
    }

    private fun isSubjunctiveTwo(i: Int): Boolean =
        aux(i, "werden").any { it.cell.tense == GermanTense.SUBJUNCTIVE_II } || w[i].verbWith(GermanTense.SUBJUNCTIVE_II).isNotEmpty()

    // ---- questions, word order

    private fun sentenceForms() {
        for (start in sentenceStarts()) {
            val mark = s.endMark(start)
            val next = start + 1
            if (w[start].lower in GERMAN_QUESTION_WORDS && mark == "?") {
                val verb = (next until minOf(w.size, next + 3)).firstOrNull { w[it].finite.isNotEmpty() && !w[it].punctuation }
                if (verb != null) add(GrammarMeaning.DE_W_FRAGE, "W-Frage: Fragewort + Verb", CefrLevel.A1, listOf(start, verb))
            } else if (mark == "?" && w[start].finite.isNotEmpty() && w[start].tag in setOf("VERB", "AUX")) {
                add(GrammarMeaning.DE_JA_NEIN_FRAGE, "Ja/Nein-Frage: Verb am Anfang", CefrLevel.A1, listOf(start))
            } else {
                val verb = verbAfterOpener(start)
                if (verb != null) add(GrammarMeaning.DE_INVERSION, "Satzglied + Verb + Subjekt", CefrLevel.A2, listOf(start, verb))
            }
        }
    }

    /**
     * The finite verb that follows a sentence-opening adverb (Heute gehe ich), adverb and time noun (Heute Abend gehen wir)
     * or preposition with its noun phrase (Nach dem Essen trinken wir), when a subject follows it; null otherwise.
     */
    private fun verbAfterOpener(start: Int): Int? {
        val first = w[start]
        val end =
            when {
                (first.tag == "ADV" || first.lower in FRONT_ADVERBS) && first.lower !in GERMAN_QUESTION_WORDS ->
                    if (w.getOrNull(start + 1)?.nouns?.any { it.noun.lemma in TIME_NOUNS } == true) start + 1 else start
                first.lower in GERMAN_PREPOSITIONS || first.lower in GERMAN_CONTRACTIONS ->
                    complementAfter(start)?.first?.last()
                        ?: return null
                else -> return null
            }
        val verb = end + 1
        val finite = verb < w.size && !w[verb].punctuation && w[verb].finite.isNotEmpty() && w[verb].tag in setOf("VERB", "AUX")
        return verb.takeIf { finite && subjectFollows(verb) }
    }

    private fun subjectFollows(verb: Int): Boolean {
        val next = w.getOrNull(verb + 1) ?: return false
        return !next.punctuation &&
            (next.lower in GERMAN_SUBJECT_PRONOUNS || next.text == "Sie" || next.lower in GERMAN_DETERMINERS || next.nouns.isNotEmpty())
    }

    private fun clauseVerbCluster(clause: IntRange): List<Int> {
        val verbs = clause.filter { !w[it].punctuation && w[it].verbs.isNotEmpty() && w[it].tag in setOf("VERB", "AUX") }
        if (verbs.isEmpty()) return emptyList()
        val cluster = ArrayList<Int>()
        var expected = verbs.last()
        cluster += expected
        for (v in verbs.dropLast(1).reversed()) {
            if (v == expected - 1) {
                cluster += v
                expected = v
            } else {
                break
            }
        }
        return cluster.reversed()
    }

    private fun subordinateClauses() {
        for (i in w.indices) if (!w[i].punctuation) subordinator(i)
        for (comma in w.indices) if (w[comma].punctuation && w[comma].text == ",") commaClause(comma)
    }

    /** A conjunction that sends the verb to the end of its clause (dass, weil, wenn …). */
    private fun subordinator(i: Int) {
        val lower = w[i].lower
        if (lower !in GERMAN_SUBORDINATORS || (lower !in CLEAR_SUBORDINATORS && w[i].tag != "SCONJ")) return
        val cluster = clauseVerbCluster(s.clauseRange(i)).filter { it > i }
        val (meaning, level) = subordinatorMeaning(lower)
        add(meaning, "$lower … Verb am Ende", level, listOf(i) + cluster, cluster.none { w[it].finite.isNotEmpty() })
    }

    private fun subordinatorMeaning(lower: String): Pair<GrammarMeaning, CefrLevel> =
        when (lower) {
            "weil", "da" -> GrammarMeaning.DE_SUB_WEIL to CefrLevel.A2
            "dass" -> GrammarMeaning.DE_SUB_DASS to CefrLevel.A2
            "ob" -> GrammarMeaning.DE_SUB_OB to CefrLevel.B1
            "wenn", "falls" -> GrammarMeaning.DE_SUB_WENN to CefrLevel.A2
            "als" -> GrammarMeaning.DE_SUB_ALS to CefrLevel.A2
            "obwohl", "obgleich" -> GrammarMeaning.DE_SUB_OBWOHL to CefrLevel.B1
            "damit" -> GrammarMeaning.DE_SUB_DAMIT to CefrLevel.B1
            "sodass", "indem" -> GrammarMeaning.DE_SUB_FOLGE to CefrLevel.B2
            else -> GrammarMeaning.DE_SUB_ZEIT to CefrLevel.B1
        }

    /** The clause after a comma: a relative clause or an indirect question, when its verbs end it. */
    private fun commaClause(comma: Int) {
        val first = comma + 1
        if (first >= w.size || w[first].punctuation) return
        val clause = s.clauseRange(first)
        val cluster = clauseVerbCluster(clause).filter { it > first }
        if (cluster.none { w[it].finite.isNotEmpty() }) return
        val verbFinal =
            cluster.last() == clause.last ||
                (clause.last > cluster.last() && w[clause.last].punctuation && cluster.last() == clause.last - 1)
        if (!verbFinal) return
        val lower = w[first].lower
        if (startsRelativeClause(comma, first)) {
            add(GrammarMeaning.DE_RELATIVSATZ, "Relativsatz: Relativpronomen … Verb am Ende", CefrLevel.B1, listOf(first) + cluster)
        } else if (lower in GERMAN_QUESTION_WORDS && lower !in setOf("welche", "welcher", "welches")) {
            add(GrammarMeaning.DE_INDIREKTE_FRAGE, "indirekte Frage: Fragewort … Verb am Ende", CefrLevel.B1, listOf(first) + cluster)
        }
    }

    /** A relative pronoun after a noun and a comma, followed by a subject or a verb, not by a noun (that would be an article). */
    private fun startsRelativeClause(
        comma: Int,
        first: Int,
    ): Boolean {
        val lower = w[first].lower
        val afterWord = w.getOrNull(first + 1)
        val shape =
            lower in GERMAN_RELATIVE_PRONOUNS &&
                (
                    afterWord == null || afterWord.nouns.isEmpty() && afterWord.lower !in GERMAN_DETERMINERS ||
                        lower in setOf("dessen", "deren")
                )
        val before = comma - 1
        return shape && lower !in setOf("was", "wo") && before >= 0 && w[before].tag in setOf("NOUN", "PROPN", "PRON")
    }

    private fun small() {
        for (i in w.indices) {
            if (w[i].punctuation) continue
            val lower = w[i].lower
            if (lower in GERMAN_COORDINATORS &&
                w[i].tag == "CCONJ"
            ) {
                add(GrammarMeaning.DE_KOORDINATION, "und / aber / oder / denn / sondern", CefrLevel.A1, listOf(i))
            }
            if (lower == "nicht") add(GrammarMeaning.DE_NICHT, "nicht", CefrLevel.A1, listOf(i))
            if (lower.startsWith("kein") && lower in GERMAN_DETERMINERS) add(GrammarMeaning.DE_KEIN, "kein-", CefrLevel.A1, listOf(i))
            if (lower == "es" &&
                w.getOrNull(i + 1)?.lower == "gibt"
            ) {
                add(GrammarMeaning.DE_ES_GIBT, "es gibt + Akkusativ", CefrLevel.A2, listOf(i, i + 1))
            }
        }
    }
}
