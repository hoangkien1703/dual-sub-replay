package com.kienhoang.dualsubreplay.data

/**
 * Word-level timing helpers shared by the caption parser, the merger, and the
 * real-time spoken-word highlight in the UI.
 */

/** Splits [text] into words and spreads them across the cue duration by length. */
internal fun estimateWordTimings(text: String, startMs: Long, endMs: Long): List<SubtitleWord> {
    val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty() || endMs <= startMs) return emptyList()
    val weights = tokens.map { token -> token.length.coerceAtLeast(1).toLong() }
    val totalWeight = weights.sum().coerceAtLeast(1L)
    val duration = endMs - startMs
    var consumedWeight = 0L
    var cursor = startMs
    return tokens.mapIndexed { index, token ->
        consumedWeight += weights[index]
        val proportionalEnd = if (index == tokens.lastIndex) {
            endMs
        } else {
            startMs + (duration * consumedWeight / totalWeight)
        }
        // Broken/very dense captions can contain more tokens than their tiny cue
        // duration reasonably allows. Never let estimated timings run past the
        // cue or become negative; zero-length slices are safer than overflow.
        val safeEnd = proportionalEnd.coerceIn(cursor, endMs)
        val word = SubtitleWord(text = token, startMs = cursor, endMs = safeEnd)
        cursor = safeEnd
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
