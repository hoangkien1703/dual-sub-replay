package com.kienhoang.dualsubreplay.data

/** How advanced a grammar point is, on the scale learners of its language know. */
internal sealed interface GrammarLevel {
    /** The level as tests and the assistant name it: N3, HSK 2, B1. */
    val code: String
}

/** The HSK level a Chinese grammar point is usually taught at, from 1 (first) to 6. */
internal enum class HskLevel(
    val number: Int,
) : GrammarLevel {
    HSK1(1),
    HSK2(2),
    HSK3(3),
    HSK4(4),
    HSK5(5),
    HSK6(6),
    ;

    override val code: String get() = "HSK $number"
}

/** The CEFR level an English grammar point is usually taught at, from A1 (first) to C2. */
internal enum class CefrLevel : GrammarLevel {
    A1,
    A2,
    B1,
    B2,
    C1,
    C2,
    ;

    override val code: String get() = name
}

/**
 * One grammar point in a sentence: its usual written [form] (〜 or … stands for the words it joins),
 * the characters it covers, its [meanings] (the likeliest in this sentence first) and the [level]
 * it is taught at.
 */
internal data class GrammarMatch(
    val form: String,
    val start: Int,
    val end: Int,
    val meanings: List<GrammarMeaning>,
    val level: GrammarLevel = JlptLevel.N5,
)

/** A rule's hit: how many morphemes from the start index it covers, and what it is. */
internal class RuleHit(
    val length: Int,
    val form: String,
    val meanings: List<GrammarMeaning>,
    val level: GrammarLevel? = null,
)

/** A grammar point that needs code rather than a pattern: a hit at the index, or null. */
internal typealias GrammarRule = (List<Morpheme>, Int) -> RuleHit?

/** A point found at [index]; a lower [priority] wins between hits of the same length. */
private class Candidate(
    val priority: Int,
    val index: Int,
    val hit: RuleHit,
)

/**
 * The grammar points of a sentence: every [catalogue] pattern and [rules] hit, then the longest
 * first so each morpheme belongs to one point. Between hits of the same length, the earlier
 * catalogue pattern wins, and the catalogue wins over the rules (the rules win with [rulesFirst]).
 * A rule hit without a level gets [ruleLevel] of its form.
 */
internal fun matchGrammar(
    morphemes: List<Morpheme>,
    catalogue: List<GrammarPattern>,
    rules: List<GrammarRule>,
    rulesFirst: Boolean = false,
    ruleLevel: (form: String) -> GrammarLevel,
): List<GrammarMatch> {
    val catalogueOffset = if (rulesFirst) rules.size else 0
    val ruleOffset = if (rulesFirst) 0 else catalogue.size
    val patterns =
        catalogue.flatMapIndexed { priority, pattern ->
            morphemes.indices.mapNotNull { index ->
                pattern
                    .lengthAt(morphemes, index)
                    .takeIf { it > 0 }
                    ?.let { Candidate(catalogueOffset + priority, index, RuleHit(it, pattern.form, pattern.meanings, pattern.level)) }
            }
        }
    val ruled =
        rules.flatMapIndexed { priority, rule ->
            morphemes.indices.mapNotNull { index ->
                rule(morphemes, index)?.let { hit -> Candidate(ruleOffset + priority, index, hit) }
            }
        }
    val accepted = mutableListOf<Pair<IntRange, GrammarMatch>>()
    val ordered = (patterns + ruled).sortedWith(compareBy({ -it.hit.length }, { it.priority }, { it.index }))
    for (candidate in ordered) {
        val range = candidate.index until candidate.index + candidate.hit.length
        if (accepted.any { (taken, _) -> range.first <= taken.last && range.last >= taken.first }) continue
        val hit = candidate.hit
        val level = hit.level ?: ruleLevel(hit.form)
        accepted += range to GrammarMatch(hit.form, morphemes[range.first].start, morphemes[range.last].end, hit.meanings, level)
    }
    return accepted.map { it.second }.sortedWith(compareBy({ it.start }, { it.end }))
}

/**
 * The grammar points that belong to a selection from [start] to [end]: those inside or overlapping
 * it, and one that starts right after it (体育館 + に), since particles and endings follow their word.
 */
internal fun grammarForSelection(
    matches: List<GrammarMatch>,
    start: Int,
    end: Int,
): List<GrammarMatch> {
    val inside = matches.filter { it.start < end && it.end > start }
    val following = matches.filter { it.start == end }.take(1)
    return (inside + following).distinct()
}
