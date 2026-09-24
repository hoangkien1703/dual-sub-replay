package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

internal data class CaptionTranslationUnit(
    val text: String,
    val indices: List<Int>,
)

/**
 * Unpunctuated auto captions give no sentence end, so joining stops at a readable size. Longer
 * limits produced rows that ran two spoken sentences together ("…this model can That is…").
 */
internal const val MAX_UNIT_CHARACTERS = 120
internal const val MAX_UNIT_DURATION_MS = 8_000L

/** Display splitting must not remove the sentence context passed to ML Kit. */
internal fun sentenceCaptionUnits(
    display: List<SubtitleSegment>,
    source: List<SubtitleSegment>,
    natural: Boolean,
): List<CaptionTranslationUnit> {
    if (!natural) return display.mapIndexed { index, segment -> CaptionTranslationUnit(segment.originalText, listOf(index)) }
    val ordered =
        if (display === source) {
            display.mapIndexed { index, segment -> CaptionTranslationUnit(segment.originalText, listOf(index)) }
        } else {
            mappedUnits(display, source)
        }
    val coherent = mutableListOf<CaptionTranslationUnit>()
    val sentenceEnd = Regex("[.!?。！？…][\\\"'’”)]*$")
    ordered.forEach { next ->
        val previous = coherent.lastOrNull()
        // Auto-caption lines overlap in time (a negative gap), which means continuous speech.
        val join =
            previous != null && !sentenceEnd.containsMatchIn(previous.text.trim()) &&
                next.indices.first() == previous.indices.last() + 1 &&
                display[next.indices.first()].startMs - display[previous.indices.last()].endMs <= 1200 &&
                display[next.indices.last()].endMs - display[previous.indices.first()].startMs <= MAX_UNIT_DURATION_MS &&
                previous.text.length + next.text.length <= MAX_UNIT_CHARACTERS
        if (join) {
            val left = checkNotNull(previous).text
            val separator = if (left.lastOrNull()?.code in 0x3000..0x9FFF && next.text.firstOrNull()?.code in 0x3000..0x9FFF) "" else " "
            coherent[coherent.lastIndex] = CaptionTranslationUnit(left + separator + next.text, previous.indices + next.indices)
        } else {
            coherent.add(next)
        }
    }
    return coherent
}

/**
 * Maps display rows onto separately supplied source sentences. When the display rows are the
 * source itself, [sentenceCaptionUnits] uses them directly: a short overlapping auto-caption piece
 * can fit inside its neighbour's time range and must not be absorbed without its text.
 */
private fun mappedUnits(
    display: List<SubtitleSegment>,
    source: List<SubtitleSegment>,
): List<CaptionTranslationUnit> {
    val assigned = mutableSetOf<Int>()
    val units =
        source.mapNotNull { sentence ->
            val indices = displayIndicesForSource(sentence, display, assigned)
            if (indices.isEmpty()) null else CaptionTranslationUnit(sentence.originalText, indices).also { assigned.addAll(indices) }
        }
    val unmatched = display.indices.filterNot { it in assigned }.map { CaptionTranslationUnit(display[it].originalText, listOf(it)) }
    return (units + unmatched).sortedBy { it.indices.first() }
}

private fun displayIndicesForSource(
    sentence: SubtitleSegment,
    display: List<SubtitleSegment>,
    assigned: Set<Int>,
): List<Int> {
    var low = 0
    var high = display.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (display[middle].startMs < sentence.startMs) low = middle + 1 else high = middle
    }
    val result = mutableListOf<Int>()
    while (low < display.size && display[low].startMs < sentence.endMs) {
        if (low !in assigned && display[low].endMs <= sentence.endMs) result.add(low)
        low++
    }
    return result
}
