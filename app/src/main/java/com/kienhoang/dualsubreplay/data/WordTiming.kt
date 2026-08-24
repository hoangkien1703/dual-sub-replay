package com.kienhoang.dualsubreplay.data

/**
 * Word-level timing helpers shared by the caption parser, the merger, and the
 * real-time spoken-word highlight in the UI.
 */

/** Splits [text] into words and spreads them across the cue duration by length. */
internal fun estimateWordTimings(text: String, startMs: Long, endMs: Long): List<SubtitleWord> {
    val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty() || endMs <= startMs) return emptyList()
    val weights = tokens.map { token -> token.length.coerceAtLeast(1).toFloat() }
    val totalWeight = weights.sum()
    val duration = (endMs - startMs).toFloat()
    var cursor = startMs
    return tokens.mapIndexed { index, token ->
        val share = if (index == tokens.lastIndex) {
            endMs - cursor
        } else {
            (duration * weights[index] / totalWeight).toLong().coerceAtLeast(60L)
        }
        val word = SubtitleWord(text = token, startMs = cursor, endMs = (cursor + share))
        cursor = word.endMs
        word
    }
}

/**
 * Index of the word being spoken at [timeMs]. Between words the previously
 * started word stays highlighted so short gaps do not flicker.
 */
internal fun activeWordIndex(words: List<SubtitleWord>, timeMs: Long): Int {
    var result = -1
    for ((index, word) in words.withIndex()) {
        if (word.startMs <= timeMs) result = index else break
    }
    return result
}
