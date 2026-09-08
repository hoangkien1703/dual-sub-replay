package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CaptionTranslationUnit(
    val text: String,
    val indices: List<Int>,
)

/** Display splitting must not remove the sentence context passed to ML Kit. */
internal fun sentenceCaptionUnits(
    display: List<SubtitleSegment>,
    source: List<SubtitleSegment>,
    natural: Boolean,
): List<CaptionTranslationUnit> {
    if (!natural) return display.mapIndexed { index, segment -> CaptionTranslationUnit(segment.originalText, listOf(index)) }
    val assigned = mutableSetOf<Int>()
    val units =
        source.mapNotNull { sentence ->
            val indices = displayIndicesForSource(sentence, display, assigned)
            if (indices.isEmpty()) null else CaptionTranslationUnit(sentence.originalText, indices).also { assigned.addAll(indices) }
        }
    val ordered =
        (
            units +
                display.indices.filterNot { it in assigned }.map {
                    CaptionTranslationUnit(display[it].originalText, listOf(it))
                }
        ).sortedBy { it.indices.first() }
    val coherent = mutableListOf<CaptionTranslationUnit>()
    val sentenceEnd = Regex("[.!?。！？…][\\\"'’”)]*$")
    ordered.forEach { next ->
        val previous = coherent.lastOrNull()
        val join =
            previous != null && !sentenceEnd.containsMatchIn(previous.text.trim()) &&
                next.indices.first() == previous.indices.last() + 1 &&
                display[next.indices.first()].startMs - display[previous.indices.last()].endMs in 0..1200 &&
                display[next.indices.last()].endMs - display[previous.indices.first()].startMs <= 12_000 &&
                previous.text.length + next.text.length <= 240
        if (join) {
            val left = previous.text
            val separator = if (left.lastOrNull()?.code in 0x3000..0x9FFF && next.text.firstOrNull()?.code in 0x3000..0x9FFF) "" else " "
            coherent[coherent.lastIndex] = CaptionTranslationUnit(left + separator + next.text, previous.indices + next.indices)
        } else {
            coherent.add(next)
        }
    }
    return coherent
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

internal suspend fun translateCaptionUnits(
    translator: OnDeviceTranslator,
    sourceLanguage: String,
    targetLanguage: String,
    display: List<SubtitleSegment>,
    playbackTime: () -> Long,
    onDownloading: (Boolean) -> Unit,
    onProgress: (List<SubtitleSegment>, Int, Int) -> Unit,
) {
    translateDisplayCaptions(display, playbackTime, { text ->
        var result = ""
        translator.translateAll(sourceLanguage, targetLanguage, listOf(text), onDownloading) { _, translated -> result = translated }
        result
    }, onProgress)
}

internal suspend fun translateDisplayCaptions(
    display: List<SubtitleSegment>,
    playbackTime: () -> Long,
    translate: suspend (String) -> String,
    onProgress: (List<SubtitleSegment>, Int, Int) -> Unit,
) {
    val working = display.toMutableList()
    val pending = display.indices.filter { display[it].translatedText == null }.toMutableList()
    val total = pending.size
    var completed = 0
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val position = nearestSegmentIndex(display, playbackTime())
        val index = pending.minBy { kotlin.math.abs(it - position) }
        val text = translate(display[index].originalText)
        currentCoroutineContext().ensureActive()
        working[index] = working[index].copy(translatedText = text.trim())
        completed++
        onProgress(working.toList(), completed, total)
        pending.remove(index)
    }
}
