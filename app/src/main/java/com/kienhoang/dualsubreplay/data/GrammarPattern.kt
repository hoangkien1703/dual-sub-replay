package com.kienhoang.dualsubreplay.data

/** The JLPT level a grammar point is usually taught at, from N5 (first) to N1 (last). */
internal enum class JlptLevel(
    val number: Int,
) {
    N5(5),
    N4(4),
    N3(3),
    N2(2),
    N1(1),
}

/**
 * A grammar point written as a sequence of analyzer morphemes, so hundreds of points can be listed
 * as data instead of one function each (see `JapaneseGrammarCatalogue.kt`).
 *
 * A [spec] is one or more alternatives separated by ` / `. Each alternative is a space-separated
 * list of morpheme conditions:
 *
 * - `わけ|訳` the surface or base form is one of these words; `_` or nothing means any word.
 *   `~まみれ` means the word ends with it; `~~合う` also needs a hiragana right before it
 *   (助け合う, not 似合う).
 * - `@名詞` the part of speech (any IPADIC level) is this; `@動詞,形容詞` is any of them, and
 *   several `@` groups must all hold.
 * - `%連用` the conjugation form starts with this (`%基本形,体言` is either).
 * - `!似合う` never this word, whatever else matches.
 * - `?` at the end makes the condition optional.
 * - A first condition starting with `^` checks the morpheme before the point, and a last one
 *   starting with `>` the morpheme after it; neither is part of the point. A `>` with only `!`
 *   words also allows the end of the sentence.
 * - `NEG` stands for `ます? ない|ぬ|ん|ず`, a negative ending in any politeness.
 */
internal class GrammarPattern(
    val level: JlptLevel,
    val form: String,
    spec: String,
    val meanings: List<GrammarMeaning>,
) {
    private val alternatives = spec.split(" / ").map(::PatternSequence)

    /** How many morphemes from [index] this point covers, or 0 when it does not start there. */
    fun lengthAt(
        morphemes: List<Morpheme>,
        index: Int,
    ): Int = alternatives.maxOf { it.lengthAt(morphemes, index) }
}

private val SPEC_MACROS = mapOf("NEG" to "ます? ない|ぬ|ん|ず")

private class PatternSequence(
    spec: String,
) {
    private val before: MorphemeCondition?
    private val after: MorphemeCondition?
    private val steps: List<MorphemeCondition>

    init {
        var parts = spec.split(' ').filter(String::isNotEmpty).flatMap { SPEC_MACROS[it]?.split(' ') ?: listOf(it) }
        before = parts.first().takeIf { it.startsWith("^") }?.let { MorphemeCondition(it.drop(1)) }
        if (before != null) parts = parts.drop(1)
        after = parts.last().takeIf { it.startsWith(">") }?.let { MorphemeCondition(it.drop(1)) }
        if (after != null) parts = parts.dropLast(1)
        steps = parts.map(::MorphemeCondition)
        require(steps.isNotEmpty()) { "Empty grammar pattern: $spec" }
    }

    fun lengthAt(
        morphemes: List<Morpheme>,
        index: Int,
    ): Int {
        if (before != null && (index == 0 || !before.matches(morphemes[index - 1]))) return 0
        return (longestEnd(morphemes, 0, index) - index).coerceAtLeast(0)
    }

    /** The furthest index the steps from [step] can reach starting at [at], or -1. */
    private fun longestEnd(
        morphemes: List<Morpheme>,
        step: Int,
        at: Int,
    ): Int {
        if (step == steps.size) return if (afterAllows(morphemes, at)) at else -1
        val condition = steps[step]
        val taken = if (at < morphemes.size && condition.matches(morphemes[at])) longestEnd(morphemes, step + 1, at + 1) else -1
        val skipped = if (condition.optional) longestEnd(morphemes, step + 1, at) else -1
        return maxOf(taken, skipped)
    }

    private fun afterAllows(
        morphemes: List<Morpheme>,
        at: Int,
    ): Boolean =
        when {
            after == null -> true
            at >= morphemes.size -> after.onlyExcludes
            else -> after.matches(morphemes[at])
        }
}

private class MorphemeCondition(
    raw: String,
) {
    val optional = raw.length > 1 && raw.endsWith("?")
    private val excluded: Set<String>
    private val suffixLevel: Int
    private val texts: Set<String>
    private val tagGroups: List<Set<String>>
    private val conjugations: List<String>

    init {
        val body = if (optional) raw.dropLast(1) else raw
        excluded = body.split('!').drop(1).toSet()
        val withoutExcludes = body.substringBefore('!')
        suffixLevel = withoutExcludes.takeWhile { it == '~' }.length
        val word = withoutExcludes.drop(suffixLevel)
        conjugations = word.substringAfter('%', "").split(',').filter(String::isNotEmpty)
        val tagged = word.substringBefore('%').split('@')
        texts =
            tagged
                .first()
                .takeUnless { it == "_" }
                ?.split('|')
                ?.filter(String::isNotEmpty)
                .orEmpty()
                .toSet()
        tagGroups = tagged.drop(1).map { it.split(',').toSet() }
    }

    /** True when only `!` words were given, so the end of the sentence also passes a `>` check. */
    val onlyExcludes: Boolean get() = texts.isEmpty() && tagGroups.isEmpty() && conjugations.isEmpty()

    fun matches(morpheme: Morpheme): Boolean =
        morpheme.surface !in excluded &&
            morpheme.baseForm !in excluded &&
            textMatches(morpheme) &&
            tagGroups.all { group -> morpheme.pos in group || morpheme.detail in group || morpheme.subDetail in group } &&
            (conjugations.isEmpty() || conjugations.any(morpheme.conjugationForm::startsWith))

    private fun textMatches(morpheme: Morpheme): Boolean =
        when {
            texts.isEmpty() -> true
            suffixLevel == 0 -> morpheme.surface in texts || morpheme.baseForm in texts
            else -> endsWithText(morpheme.surface) || endsWithText(morpheme.baseForm)
        }

    private fun endsWithText(word: String): Boolean =
        texts.any { text ->
            word.length > text.length &&
                word.endsWith(text) &&
                (suffixLevel == 1 || word[word.length - text.length - 1] in HIRAGANA)
        }
}

private val HIRAGANA = 'ぁ'..'ゟ'
