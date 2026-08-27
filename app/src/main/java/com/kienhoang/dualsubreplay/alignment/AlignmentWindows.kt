package com.kienhoang.dualsubreplay.alignment

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleTimingSource
import com.kienhoang.dualsubreplay.data.SubtitleWord
import kotlin.math.abs

/**
 * A core range is emitted once. The wider context range deliberately overlaps
 * neighbouring plans so CTC never has to infer a word at an artificial visual
 * subtitle boundary.
 */
internal data class AlignmentWindowPlan(
    val contextIndices: IntRange,
    val outputIndices: IntRange,
)

internal fun alignmentWindowPlans(
    segments: List<SubtitleSegment>,
    preferredIndex: Int,
    maxCoreSpanMs: Long = 12_000L,
    overlapContextMs: Long = 2_500L,
    maxContextSpanMs: Long = 20_000L,
    maxContextWords: Int = 72,
): List<AlignmentWindowPlan> {
    if (segments.isEmpty()) return emptyList()
    require(maxCoreSpanMs > 0L)
    require(overlapContextMs >= 0L)
    require(maxContextSpanMs >= maxCoreSpanMs)
    require(maxContextWords > 0)

    val coreRanges = mutableListOf<IntRange>()
    var coreStart = 0
    while (coreStart < segments.size) {
        var coreEnd = coreStart
        var wordCount = segments[coreStart].words.size
        while (coreEnd + 1 < segments.size) {
            val candidate = segments[coreEnd + 1]
            val candidateSpan = candidate.endMs - segments[coreStart].startMs
            val candidateWords = wordCount + candidate.words.size
            if (candidateSpan > maxCoreSpanMs || candidateWords > maxContextWords) break
            coreEnd += 1
            wordCount = candidateWords
        }
        coreRanges += coreStart..coreEnd
        coreStart = coreEnd + 1
    }

    val plans = coreRanges.map { outputRange ->
        var contextStart = outputRange.first
        var contextEnd = outputRange.last
        var contextWords = segments
            .subList(contextStart, contextEnd + 1)
            .sumOf { it.words.size }

        fun canInclude(candidateIndex: Int): Boolean {
            val newStart = minOf(contextStart, candidateIndex)
            val newEnd = maxOf(contextEnd, candidateIndex)
            val span = segments[newEnd].endMs - segments[newStart].startMs
            return span <= maxContextSpanMs &&
                contextWords + segments[candidateIndex].words.size <= maxContextWords
        }

        while (
            contextStart > 0 &&
            segments[contextStart - 1].endMs >=
                segments[outputRange.first].startMs - overlapContextMs &&
            canInclude(contextStart - 1)
        ) {
            contextStart -= 1
            contextWords += segments[contextStart].words.size
        }
        while (
            contextEnd + 1 < segments.size &&
            segments[contextEnd + 1].startMs <=
                segments[outputRange.last].endMs + overlapContextMs &&
            canInclude(contextEnd + 1)
        ) {
            contextEnd += 1
            contextWords += segments[contextEnd].words.size
        }

        AlignmentWindowPlan(
            contextIndices = contextStart..contextEnd,
            outputIndices = outputRange,
        )
    }

    val focus = preferredIndex.coerceIn(segments.indices)
    return plans.sortedWith(
        compareBy<AlignmentWindowPlan> { plan ->
            when {
                focus < plan.outputIndices.first -> plan.outputIndices.first - focus
                focus > plan.outputIndices.last -> focus - plan.outputIndices.last
                else -> 0
            }
        }.thenBy { plan -> abs(plan.outputIndices.first - focus) },
    )
}

internal fun splitAlignedWindow(
    segments: List<SubtitleSegment>,
    plan: AlignmentWindowPlan,
    alignedWords: List<SubtitleWord>,
): List<Pair<Int, SubtitleSegment>> {
    val contextIndices = plan.contextIndices.filter(segments.indices::contains)
    val expectedWords = contextIndices.sumOf { segments[it].words.size }
    if (alignedWords.size != expectedWords) return emptyList()

    var wordOffset = 0
    return buildList {
        contextIndices.forEach { index ->
            val original = segments[index]
            val wordCount = original.words.size
            val segmentWords = alignedWords.subList(wordOffset, wordOffset + wordCount)
            wordOffset += wordCount
            if (index !in plan.outputIndices) return@forEach
            if (segmentWords.none { it.timingSource == SubtitleTimingSource.ACOUSTIC_ALIGNED }) {
                return@forEach
            }
            add(
                index to original.copy(
                    startMs = segmentWords.minOf(SubtitleWord::startMs),
                    endMs = segmentWords.maxOf(SubtitleWord::endMs),
                    words = segmentWords.toList(),
                ),
            )
        }
    }
}
