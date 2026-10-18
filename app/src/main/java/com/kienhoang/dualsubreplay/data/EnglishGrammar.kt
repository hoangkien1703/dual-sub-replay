package com.kienhoang.dualsubreplay.data

/** The English grammar points of a line's [morphemes]: the catalogue, then the rules below (levels as in the catalogue). */
internal fun englishGrammar(morphemes: List<Morpheme>): List<GrammarMatch> =
    matchGrammar(morphemes, ENGLISH_GRAMMAR_CATALOGUE, ENGLISH_RULES) { CefrLevel.A1 }

private val ENGLISH_RULES: List<GrammarRule> = listOf(::conditional, ::questionWord, ::helperQuestion, ::imperative)

/** Verbs after which "if" means "whether" (I don't know if…). */
private val WHETHER_VERBS = setOf("know", "ask", "wonder", "see", "check", "sure", "decide", "tell", "doubt")

/** If: the tenses in its clause tell a real condition, an unreal present and an unreal past apart. */
private fun conditional(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "if") return null
    val asked = morphemes.take(index).lastOrNull { it.pos != "PRON" && it.pos != "PROPN" }
    if (asked != null && asked.baseForm in WHETHER_VERBS) return RuleHit(1, "whether", listOf(GrammarMeaning.EN_WHETHER), CefrLevel.B2)
    val clause = morphemes.drop(index + 1).takeWhile { it.pos != "PUNCT" && it.surface != "then" }
    val pastPerfect =
        clause.indices.any { at ->
            clause[at].baseForm == "have" && clause[at].conjugationForm == "PAST" &&
                clause.drop(at + 1).take(MAX_HELPER_GAP).any { it.conjugationForm == "PP" }
        }
    val past = clause.any { (it.pos == "VERB" || it.pos == "AUX") && it.conjugationForm == "PAST" }
    return when {
        pastPerfect -> RuleHit(1, "if + had done", listOf(GrammarMeaning.EN_IF_PAST_UNREAL), CefrLevel.B2)
        past -> RuleHit(1, "if + past", listOf(GrammarMeaning.EN_IF_UNREAL), CefrLevel.B1)
        else -> RuleHit(1, "if + present", listOf(GrammarMeaning.EN_IF_REAL), CefrLevel.A2)
    }
}

private const val MAX_HELPER_GAP = 3

private val QUESTION_WORDS = setOf("what", "where", "when", "why", "who", "whom", "which", "how", "whose")

private val SENTENCE_ENDS = setOf(".", "!", "?")

/** A question word that starts a question; "when" that starts a time clause instead. */
private fun questionWord(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val word = morphemes[index]
    if (word.surface !in QUESTION_WORDS) return null
    val rest = morphemes.drop(index + 1)
    val asks =
        startsSentence(morphemes, index) &&
            (rest.takeWhile { it.surface != "." && it.surface != "!" }.any { it.surface == "?" } || rest.firstOrNull()?.pos == "AUX")
    return when {
        asks -> RuleHit(1, "what / where / why…?", listOf(GrammarMeaning.EN_WH_QUESTION), CefrLevel.A1)
        word.surface == "when" -> RuleHit(1, "when + sentence", listOf(GrammarMeaning.EN_WHEN_CLAUSE), CefrLevel.A1)
        else -> null
    }
}

private fun startsSentence(
    morphemes: List<Morpheme>,
    index: Int,
) = index == 0 || morphemes[index - 1].pos == "PUNCT"

private val SUBJECT_TAGS = setOf("PRON", "PROPN", "DET", "NOUN")

/** Is it…? Have you…?: be or have first, then the subject (do and the modals have their own points). */
private fun helperQuestion(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val helper = morphemes[index]
    if (helper.pos != "AUX" || helper.baseForm !in setOf("be", "have") || !startsSentence(morphemes, index)) return null
    val negated = morphemes.getOrNull(index + 1)?.baseForm == "not"
    val subject = morphemes.getOrNull(index + if (negated) 2 else 1) ?: return null
    if (subject.pos !in SUBJECT_TAGS) return null
    return RuleHit(if (negated) 3 else 2, "is / have + subject?", listOf(GrammarMeaning.EN_YES_NO_QUESTION), CefrLevel.A1)
}

/** Fixed phrases that start with a plain verb but give no order: thank you, bless you. */
private val NOT_COMMANDS = setOf("thank", "thanks", "welcome", "bless")

/** A plain verb with no subject at the start of a sentence (not a question): an order or a request. */
private fun imperative(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val verb = morphemes[index]
    val afterPlease = index > 0 && (morphemes[index - 1].surface == "please" || morphemes[index - 1].pos == "INTJ")
    if (!startsSentence(morphemes, index) && !afterPlease) return null
    if (verb.conjugationForm != "BASE" || verb.baseForm in NOT_COMMANDS) return null
    if (verb.pos != "VERB" && !(verb.pos == "AUX" && verb.baseForm == "be")) return null
    if (morphemes.drop(index + 1).firstOrNull { it.surface in SENTENCE_ENDS }?.surface == "?") return null
    return RuleHit(1, "verb (command)", listOf(GrammarMeaning.EN_IMPERATIVE), CefrLevel.A1)
}
