package com.kienhoang.dualsubreplay.ui

/**
 * Splits one whole-sentence translation across the sentence's short display rows.
 *
 * [cuts] are the source-character offsets where rows 2..n start inside a source sentence of
 * [sourceLength] characters. When [prefixTranslations] (the translation of the source text before
 * each cut) are available, a cut is placed after as many translated words as its prefix produced;
 * otherwise it is placed proportionally. Each cut then snaps to nearby punctuation, else to a word
 * edge (a character edge for scripts without spaces). Boundaries only move forward, so the slices
 * keep the translation's word order and joining them reproduces it.
 */
internal fun translationSlices(
    translated: String,
    sourceLength: Int,
    cuts: List<Int>,
    prefixTranslations: List<String>? = null,
): List<String> {
    val text = translated.trim()
    if (cuts.isEmpty() || sourceLength <= 0) return listOf(text)
    val weak = weakBreaks(text)
    val strong = strongBreaks(text)
    val window = maxOf(4, text.length / (cuts.size + 1) / 2)
    val boundaries = mutableListOf<Int>()
    cuts.forEachIndexed { index, cut ->
        val previous = boundaries.lastOrNull() ?: 0
        val ideal = idealBoundary(text, weak, sourceLength, cut, prefixTranslations?.getOrNull(index))
        val remaining = cuts.size - index - 1
        // Prefer leaving one break for every later row, so later rows are not left empty.
        val roomy = weak.filter { it > previous && weak.count { later -> later > it } >= remaining }
        val open = roomy.ifEmpty { weak.filter { it > previous } }
        boundaries +=
            strong.filter { it in open && kotlin.math.abs(it - ideal) <= window }.minByOrNull { kotlin.math.abs(it - ideal) }
                ?: open.minByOrNull { kotlin.math.abs(it - ideal) }
                ?: text.length
    }
    val starts = listOf(0) + boundaries
    val ends = boundaries + text.length
    return starts.indices.map { index -> text.substring(starts[index], maxOf(starts[index], ends[index])).trim() }
}

private fun idealBoundary(
    text: String,
    weak: List<Int>,
    sourceLength: Int,
    cut: Int,
    prefixTranslation: String?,
): Int {
    val proportional = (cut.coerceIn(0, sourceLength).toLong() * text.length / sourceLength).toInt()
    val prefix = prefixTranslation?.trim()?.trimEnd { it in BREAK_PUNCTUATION }?.trim()
    if (prefix.isNullOrEmpty()) return proportional
    if (text.none(Char::isWhitespace)) return prefix.length.coerceAtMost(text.length)
    val words = prefix.split(Regex("\\s+")).size
    return weak.getOrNull(words - 1) ?: text.length
}

/** Positions right after clause or sentence punctuation (skipping following spaces). */
private fun strongBreaks(text: String): List<Int> =
    text.indices
        .filter { index -> text[index] in BREAK_PUNCTUATION && index < text.lastIndex }
        .map { index -> skipSpaces(text, index + 1) }
        .filter { it in 1 until text.length }
        .distinct()

/** Word starts; without spaces (Japanese, Chinese, Thai) any character edge is a break. */
private fun weakBreaks(text: String): List<Int> {
    if (text.none(Char::isWhitespace)) {
        return (1 until text.length).filterNot { Character.isLowSurrogate(text[it]) }
    }
    return text.indices
        .filter { index -> index > 0 && !text[index].isWhitespace() && text[index - 1].isWhitespace() }
}

private fun skipSpaces(
    text: String,
    from: Int,
): Int {
    var index = from
    while (index < text.length && text[index].isWhitespace()) index++
    return index
}

private const val BREAK_PUNCTUATION = ",;:.!?，、。！？；：…"
