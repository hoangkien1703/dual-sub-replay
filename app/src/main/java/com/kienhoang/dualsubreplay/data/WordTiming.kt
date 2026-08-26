package com.kienhoang.dualsubreplay.data

/**
 * Word-level timing helpers shared by the caption parser, the merger, and the
 * real-time spoken-word highlight in the UI.
 */

/**
 * Small visual lead that compensates for WebView playback polling + bridge/render latency.
 * It only advances transitions between already-visible words; it never highlights the first word
 * before its real start time.
 */
internal const val KARAOKE_HIGHLIGHT_LEAD_MS = 75L

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
 * started word stays highlighted so short gaps do not flicker. After the first
 * word has really started, upcoming word transitions are allowed to lead the
 * playback clock slightly to hide WebView/polling latency.
 */
internal fun activeWordIndex(words: List<SubtitleWord>, timeMs: Long): Int {
    val firstWord = words.firstOrNull() ?: return -1
    if (timeMs < firstWord.startMs) return -1

    val syncTimeMs = timeMs + KARAOKE_HIGHLIGHT_LEAD_MS
    var result = 0
    for (index in 1 until words.size) {
        if (words[index].startMs <= syncTimeMs) result = index else break
    }
    return result
}
