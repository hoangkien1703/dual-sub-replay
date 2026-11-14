package com.kienhoang.dualsubreplay.data

/** The Spanish grammar points of a line's [morphemes]: the catalogue, then the rules below (levels in [SPANISH_RULE_POINTS]). */
internal fun spanishGrammar(morphemes: List<Morpheme>): List<GrammarMatch> =
    matchGrammar(morphemes, SPANISH_GRAMMAR_CATALOGUE, SPANISH_RULES) { CefrLevel.A1 }

private val SPANISH_RULES: List<GrammarRule> =
    listOf(::discourseMarker, ::verbForm, ::negation, ::objectPronouns, ::attachedPronouns, ::conditional, ::cuando, ::aunque, ::esQue)

/** A hit whose form and level are those of its first meaning. */
private fun hit(
    length: Int,
    meanings: List<GrammarMeaning>,
): RuleHit {
    val (form, level) = SPANISH_RULE_POINTS.getValue(meanings.first())
    return RuleHit(length, form, meanings, level)
}

private fun hit(
    length: Int,
    vararg meanings: GrammarMeaning,
) = hit(length, meanings.toList())

private val TENSE_MEANINGS =
    mapOf(
        "PRES" to GrammarMeaning.ES_PRESENT,
        "PRET" to GrammarMeaning.ES_PRETERITE,
        "IMPF" to GrammarMeaning.ES_IMPERFECT,
        "FUT" to GrammarMeaning.ES_FUTURE,
        "COND" to GrammarMeaning.ES_CONDITIONAL,
        "SUBJ_PRES" to GrammarMeaning.ES_PRESENT_SUBJUNCTIVE,
        "SUBJ_IMPF" to GrammarMeaning.ES_IMPERFECT_SUBJUNCTIVE,
        "CMD" to GrammarMeaning.ES_IMPERATIVE,
        "GER" to GrammarMeaning.ES_GERUND,
        "PART" to GrammarMeaning.ES_PARTICIPLE,
    )

private fun readings(morpheme: Morpheme): List<String> =
    if (morpheme.conjugationForm == "*") emptyList() else morpheme.conjugationForm.split('|')

private fun persons(morpheme: Morpheme): List<String> = if (morpheme.detail == "*") emptyList() else morpheme.detail.split('|')

private fun isVerb(morpheme: Morpheme) = morpheme.pos == "VERB" || morpheme.pos == "AUX"

private val SENTENCE_ENDS = setOf(".", "!", "?", "…")

private fun startsSentence(
    morphemes: List<Morpheme>,
    index: Int,
): Boolean {
    val before = morphemes.take(index).dropLastWhile { it.pos == "INTJ" || it.surface == "," }
    return before.isEmpty() || before.last().surface in SENTENCE_ENDS + setOf("¡", "¿", ":", ";", "\"", "«", "“", "—", "-")
}

private fun isQuestion(
    morphemes: List<Morpheme>,
    index: Int,
): Boolean {
    val before = morphemes.take(index).lastOrNull { it.surface in SENTENCE_ENDS || it.surface == "¿" || it.surface == "¡" }
    val after = morphemes.drop(index).firstOrNull { it.surface in SENTENCE_ENDS }
    return before?.surface == "¿" || after?.surface == "?"
}

/**
 * The tense of a verb (every reading, likeliest first; hablamos is present or preterite), with ser
 * or estar first for those verbs. A subjunctive that starts a sentence (Hable más despacio) is also
 * a command for usted, ustedes or nosotros.
 */
private fun verbForm(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val verb = morphemes[index]
    val tenses = readings(verb).distinct().filter { it != "INF" }
    val participleOnly = verb.pos == "ADJ" && tenses == listOf("PART")
    if (readings(verb).isEmpty() || (!isVerb(verb) && !participleOnly)) return null
    // An infinitive is just that; other verbs spelled the same never are.
    val meanings =
        if (readings(verb).first() ==
            "INF"
        ) {
            mutableListOf(GrammarMeaning.ES_INFINITIVE)
        } else {
            tenses.mapNotNull(TENSE_MEANINGS::get).toMutableList()
        }
    if (meanings.first() == GrammarMeaning.ES_PRESENT_SUBJUNCTIVE && startsSentence(morphemes, index) && !isQuestion(morphemes, index)) {
        meanings.add(0, GrammarMeaning.ES_IMPERATIVE)
    }
    when (verb.baseForm) {
        "ser" -> meanings.add(0, GrammarMeaning.ES_SER)
        "estar" -> meanings.add(0, GrammarMeaning.ES_ESTAR)
    }
    return hit(1, meanings.distinct().take(MAX_MEANINGS))
}

private const val MAX_MEANINGS = 3

/** Clitic pronouns that go before a verb (me lo dio) or after it (dámelo). */
private val CLITICS = setOf("me", "te", "se", "nos", "os", "lo", "la", "los", "las", "le", "les")

private fun isClitic(morpheme: Morpheme) = morpheme.pos == "PRON" && morpheme.surface in CLITICS

/**
 * No before a verb. No + subjunctive at the start of a sentence is a negative command (no hables),
 * covering the pronouns and the verb; otherwise the point is the "no" alone.
 */
private fun negation(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "no") return null
    var at = index + 1
    while (at < morphemes.size && isClitic(morphemes[at])) at++
    val verb = morphemes.getOrNull(at) ?: return null
    if (!isVerb(verb)) return null
    val command =
        readings(verb).firstOrNull() == "SUBJ_PRES" &&
            persons(verb).any { it == "2S" || it == "2P" || it == "3S" || it == "3P" } &&
            startsSentence(morphemes, index) &&
            !isQuestion(morphemes, index)
    return if (command) {
        hit(at - index + 1, GrammarMeaning.ES_NEGATIVE_COMMAND, GrammarMeaning.ES_PRESENT_SUBJUNCTIVE)
    } else {
        hit(1, GrammarMeaning.ES_NO)
    }
}

private val PERSON_OF_PRONOUN = mapOf("me" to "1S", "te" to "2S", "nos" to "1P", "os" to "2P")

private val SUBJECT_TAGS = setOf("NOUN", "PROPN", "PRON")

private val GENERAL_TENSES = setOf("PRES", "IMPF", "FUT", "COND")

/**
 * Pronouns before a verb: se lo (le turned into se), se me (something happened to me), se alone
 * (reflexive, or impersonal when no subject comes before it), me / te / nos / os with a verb of
 * the same person (reflexive: me llamo), and object pronouns (lo vi).
 */
private fun objectPronouns(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val first = morphemes[index]
    if (!isClitic(first) || attachedToVerb(morphemes, index)) return null
    val second = morphemes.getOrNull(index + 1)?.takeIf(::isClitic)
    val count = if (second == null) 1 else 2
    val verb = morphemes.getOrNull(index + count)?.takeIf(::isVerb) ?: return null
    return when {
        first.surface == "se" && second != null && second.surface.startsWith("l") && second.surface != "le" && second.surface != "les" ->
            hit(2, GrammarMeaning.ES_SE_LO)
        first.surface == "se" && second != null -> hit(2, GrammarMeaning.ES_SE_ACCIDENTAL, GrammarMeaning.ES_REFLEXIVE)
        second != null -> hit(2, GrammarMeaning.ES_OBJECT_PRONOUN)
        first.surface == "se" -> {
            val clause = morphemes.take(index).takeLastWhile { it.surface !in SENTENCE_ENDS && it.surface != "," && it.pos != "SCONJ" }
            // Impersonal se tells general truths (se habla español, se dice que…): a simple tense, no subject.
            val general = readings(verb).firstOrNull() in GENERAL_TENSES && verb.baseForm != "haber"
            val thirdPerson = persons(verb).any { it == "3S" || it == "3P" }
            val impersonal = general && thirdPerson && clause.none { it.pos in SUBJECT_TAGS }
            if (impersonal) {
                hit(1, GrammarMeaning.ES_SE_IMPERSONAL, GrammarMeaning.ES_REFLEXIVE)
            } else {
                hit(1, GrammarMeaning.ES_REFLEXIVE, GrammarMeaning.ES_SE_IMPERSONAL)
            }
        }
        PERSON_OF_PRONOUN[first.surface] in persons(verb) -> hit(1, GrammarMeaning.ES_REFLEXIVE, GrammarMeaning.ES_OBJECT_PRONOUN)
        else -> hit(1, GrammarMeaning.ES_OBJECT_PRONOUN)
    }
}

/** A pronoun written onto the end of the verb before it (dá|me|lo, decír|se|lo). */
private fun attachedToVerb(
    morphemes: List<Morpheme>,
    index: Int,
): Boolean {
    var at = index - 1
    while (at >= 0 && isClitic(morphemes[at]) && morphemes[at].end == morphemes[at + 1].start) at--
    return at >= 0 && isVerb(morphemes[at]) && morphemes[at].end == morphemes[at + 1].start
}

/** The pronouns on the end of an infinitive, gerund or command, from the first one. */
private fun attachedPronouns(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (!isClitic(morphemes[index]) || index == 0 || !isVerb(morphemes[index - 1])) return null
    if (morphemes[index - 1].end != morphemes[index].start) return null
    var end = index + 1
    while (end < morphemes.size && isClitic(morphemes[end]) && morphemes[end].start == morphemes[end - 1].end) end++
    return hit(end - index, GrammarMeaning.ES_ATTACHED_PRONOUNS)
}

/** The words of the clause after [index], up to the next punctuation. */
private fun clauseAfter(
    morphemes: List<Morpheme>,
    index: Int,
): List<Morpheme> = morphemes.drop(index + 1).takeWhile { it.pos != "PUNCT" }

/** Verbs after which si means whether (no sé si viene). */
private val WHETHER_VERBS =
    setOf("saber", "preguntar", "preguntarse", "ver", "decidir", "dudar", "comprobar", "mirar", "entender", "recordar")

/** Si: the verb in its clause tells a real condition, an unreal present, an unreal past and "whether" apart. */
private fun conditional(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "si") return null
    val asked = morphemes.take(index).lastOrNull { it.pos != "PRON" && it.surface != "no" }
    if (asked != null && isVerb(asked) && asked.baseForm in WHETHER_VERBS) return hit(1, GrammarMeaning.ES_SI_WHETHER)
    val clause = clauseAfter(morphemes, index)
    val pastPerfect =
        clause.indices.any { at ->
            clause[at].baseForm == "haber" && "SUBJ_IMPF" in readings(clause[at]) &&
                clause.drop(at + 1).take(MAX_GAP).any { "PART" in readings(it) }
        }
    val unreal = clause.firstOrNull(::isVerb)?.let { "SUBJ_IMPF" in readings(it) } == true
    return when {
        pastPerfect -> hit(1, GrammarMeaning.ES_SI_PAST_UNREAL)
        unreal -> hit(1, GrammarMeaning.ES_SI_UNREAL)
        else -> hit(1, GrammarMeaning.ES_SI_REAL)
    }
}

private const val MAX_GAP = 2

/** Whether the first verb of the clause after [index] can only be a subjunctive. */
private fun subjunctiveClause(
    morphemes: List<Morpheme>,
    index: Int,
): Boolean {
    val verb = clauseAfter(morphemes, index).firstOrNull { isVerb(it) && readings(it).isNotEmpty() } ?: return false
    return readings(verb).all { it.startsWith("SUBJ") }
}

/** Cuando: with a subjunctive it is about the future (cuando llegues); otherwise about what happens or happened. */
private fun cuando(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "cuando") return null
    return if (subjunctiveClause(morphemes, index)) hit(1, GrammarMeaning.ES_CUANDO_FUTURE) else hit(1, GrammarMeaning.ES_CUANDO)
}

/** Aunque: with the indicative "although" (a fact), with the subjunctive "even if". */
private fun aunque(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "aunque") return null
    return if (subjunctiveClause(morphemes, index)) hit(1, GrammarMeaning.ES_AUNQUE_SUBJ) else hit(1, GrammarMeaning.ES_AUNQUE)
}

/** Es que at the start of a sentence: the speaker is explaining or excusing. */
private fun esQue(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "es" || morphemes.getOrNull(index + 1)?.surface != "que") return null
    return if (startsSentence(morphemes, index)) hit(2, GrammarMeaning.ES_ES_QUE) else null
}

private val MARKER_WORDS = setOf("bueno", "vale", "venga", "vaya", "anda", "hale", "mira", "oye", "oiga", "eh")

private val MARKER_PHRASES = setOf("a ver", "o sea", "en fin", "por cierto", "es decir")

private val COURTESY_WORDS = setOf("gracias", "porfa", "perdón", "perdona", "vale", "bueno", "venga", "sí", "no")

/** Whether [index] is followed by punctuation, the end of the line or a courtesy word (vale, gracias). */
private fun standsAlone(
    morphemes: List<Morpheme>,
    index: Int,
): Boolean {
    val next = morphemes.getOrNull(index + 1) ?: return true
    return next.pos == "PUNCT" || next.surface in COURTESY_WORDS
}

/**
 * A conversation word: pues anywhere; vale, bueno, venga, mira, oye or eh when they start a
 * sentence and stand alone (Vale, nos vemos; ¿vale?; Mira, …, but not ¿Cuánto vale? or Mira la
 * casa); and a ver, o sea, en fin, por cierto, es decir (a ver not after a verb: voy a ver).
 */
private fun discourseMarker(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val word = morphemes[index].surface
    val pair = morphemes.getOrNull(index + 1)?.let { "$word ${it.surface}" }
    if (pair in MARKER_PHRASES) {
        val afterVerb = pair == "a ver" && morphemes.getOrNull(index - 1)?.let(::isVerb) == true
        return if (afterVerb) null else hit(2, GrammarMeaning.ES_DISCOURSE_MARKER)
    }
    val marker = word == "pues" || (word in MARKER_WORDS && startsSentence(morphemes, index) && standsAlone(morphemes, index))
    return if (marker) hit(1, GrammarMeaning.ES_DISCOURSE_MARKER) else null
}
