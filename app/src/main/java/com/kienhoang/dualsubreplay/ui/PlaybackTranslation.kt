package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class CaptionPlaybackRequest(
    val timeMs: Long = 0,
    val paused: Boolean = true,
    val enabled: Boolean = false,
    val seekGeneration: Long = 0,
)

/** Event driven: sleeps when the window is ready, paused, or the app is backgrounded. */
internal suspend fun translatePlaybackWindow(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> String,
    onWindow: (List<SubtitleSegment>, Boolean) -> Unit,
) {
    var indices = IntRange.EMPTY
    var rows = emptyList<SubtitleSegment>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val request = requests.value
        if (!request.enabled) {
            requests.first { it != request }
            continue
        }
        val nextIndices = store.windowIndices(request.timeMs)
        if (nextIndices != indices) {
            val previous = rows.associateBy { it.id }
            rows =
                withContext(Dispatchers.IO) { store.read(nextIndices) }.map { row ->
                    row.copy(translatedText = previous[row.id]?.translatedText)
                }
            indices = nextIndices
        }
        // Reading disk can suspend across a seek. Never show the old position afterwards.
        if (requests.value != request) continue
        val next = nextWindowTranslation(rows, request)
        onWindow(rows, next != null)
        if (next == null) {
            requests.first { it != request }
            continue
        }
        val sentence = rows[next].sentence
        val translated = translate(sentence?.text ?: rows[next].originalText).trim()
        // Translating each row's sentence prefix locates where that row ends in the full translation.
        val prefixes = sentence?.cuts?.map { cut -> translate(sentence.text.substring(0, cut).trim()) }
        currentCoroutineContext().ensureActive()
        rows = withTranslation(rows, next, translated, prefixes)
        // An in-flight task may finish after a seek/pause. Cache it, but never publish at the wrong position.
        if (requests.value.enabled && requests.value.seekGeneration == request.seekGeneration) {
            onWindow(rows, nextWindowTranslation(rows, requests.value) != null)
        }
    }
}

/**
 * A short row is translated through its whole sentence, so every row of that sentence (including
 * identical repeats in the window) receives its own slice of the one context-aware translation.
 */
internal fun withTranslation(
    rows: List<SubtitleSegment>,
    index: Int,
    translated: String,
    prefixTranslations: List<String>? = null,
): List<SubtitleSegment> {
    val sentence = rows[index].sentence
    if (sentence == null) return rows.toMutableList().also { it[index] = it[index].copy(translatedText = translated) }
    val slices = translationSlices(translated, sentence.text.length, sentence.cuts, prefixTranslations)
    return rows.map { row ->
        val other = row.sentence
        if (other != null && other.text == sentence.text && other.cuts == sentence.cuts) {
            row.copy(translatedText = slices.getOrElse(other.index) { "" })
        } else {
            row
        }
    }
}

internal fun nextWindowTranslation(
    rows: List<SubtitleSegment>,
    request: CaptionPlaybackRequest,
): Int? {
    if (!request.enabled || request.paused || rows.isEmpty()) return null
    val current = nearestSegmentIndex(rows, request.timeMs)
    val ahead = request.timeMs + com.kienhoang.dualsubreplay.data.SUBTITLE_LOOK_AHEAD_MS
    val behind = request.timeMs - com.kienhoang.dualsubreplay.data.SUBTITLE_LOOK_BEHIND_MS
    // Current cue first, then upcoming speech, then rewind history. Gaps do not pull in distant cues.
    return (current..rows.lastIndex).firstOrNull {
        rows[it].translatedText == null && rows[it].startMs <= ahead && rows[it].endMs > request.timeMs
    } ?: (current downTo 0).firstOrNull {
        rows[it].translatedText == null && rows[it].endMs > behind && rows[it].startMs <= request.timeMs
    }
}
