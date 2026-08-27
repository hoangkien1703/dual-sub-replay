package com.kienhoang.dualsubreplay.data

/**
 * Word-level timing helpers shared by the caption parser, the merger, and the
 * real-time spoken-word highlight in the UI.
 */

private const val MIN_ESTIMATED_WORD_MS = 60L
private val sentencePause = Regex("""[.!?。！？…]+["'’”)]*$""")
private val clausePause = Regex("""[,;:，；：]+["'’”)]*$""")
private val latinVowelGroups = Regex("(?i)[aeiouy]+")

/**
 * A speech-oriented fallback weight. It avoids giving very long written words
 * an unrealistically huge share of a cue and reserves a little time for
 * punctuation pauses. This is still explicitly ESTIMATED timing.
 */
private fun estimatedTimingWeight(token: String): Long {
    val spokenCharacters = token.count(Char::isLetterOrDigit).coerceAtLeast(1)
    val vowelGroups = latinVowelGroups.findAll(token).count()
    val roughSyllables = maxOf(vowelGroups, (spokenCharacters + 2) / 3)
        .coerceIn(1, 6)
    val pauseWeight = when {
        sentencePause.containsMatchIn(token) -> 3
        clausePause.containsMatchIn(token) -> 2
        else -> 0
    }
    return (roughSyllables + pauseWeight).toLong()
}

/**
 * Splits text into words and estimates their boundaries from speech-oriented
 * weights. When the cue is long enough, every word receives a small minimum
 * slice before the remaining duration is distributed by the weights.
 */
internal fun estimateWordTimings(text: String, startMs: Long, endMs: Long): List<SubtitleWord> {
    val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty() || endMs <= startMs) return emptyList()

    val duration = endMs - startMs
    val minimumPerWord = if (duration >= tokens.size * MIN_ESTIMATED_WORD_MS) {
        MIN_ESTIMATED_WORD_MS
    } else {
        0L
    }
    val reserved = minimumPerWord * tokens.size
    val distributable = (duration - reserved).coerceAtLeast(0L)
    val weights = tokens.map(::estimatedTimingWeight)
    val totalWeight = weights.sum().coerceAtLeast(1L)

    var consumedWeight = 0L
    var cursor = startMs
    return tokens.mapIndexed { index, token ->
        consumedWeight += weights[index]
        val proportionalEnd = if (index == tokens.lastIndex) {
            endMs
        } else {
            startMs +
                minimumPerWord * (index + 1L) +
                distributable * consumedWeight / totalWeight
        }
        val safeEnd = proportionalEnd.coerceIn(cursor, endMs)
        val word = SubtitleWord(
            text = token,
            startMs = cursor,
            endMs = safeEnd,
            timingSource = SubtitleTimingSource.ESTIMATED,
        )
        cursor = safeEnd
        word
    }
}

/**
 * Index of the word being spoken at [timeMs]. Between words the previously
 * started word stays highlighted so short gaps do not flicker.
 *
 * The render lead is deliberately runtime-adjustable under More settings so
 * device/WebView latency can be separated from genuinely wrong word timing.
 */
internal fun activeWordIndex(words: List<SubtitleWord>, timeMs: Long): Int {
    val firstWord = words.firstOrNull() ?: return -1
    if (timeMs < firstWord.startMs) return -1

    val syncTimeMs = timeMs + KaraokeSyncPreferences.highlightLeadMs()
    var result = 0
    for (index in 1 until words.size) {
        if (words[index].startMs <= syncTimeMs) result = index else break
    }
    return result
}
