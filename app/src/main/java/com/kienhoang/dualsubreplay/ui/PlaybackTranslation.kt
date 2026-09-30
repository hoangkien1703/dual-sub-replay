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
    /** Raised by "Retry translation". After a failure, translation waits for this to change. */
    val translationAttempt: Long = 0,
)

/** Panel status while the original captions play without translation. */
internal const val ORIGINAL_CAPTIONS_ONLY_STATUS = "Original captions only · translation unavailable"

/** A short reason for the translation bar; translator errors can be empty or very technical. */
internal fun translationFailureMessage(error: Exception): String =
    error.message?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TRANSLATION_ERROR_LENGTH }
        ?: "Translation is unavailable right now. Check your connection, then retry."

private const val MAX_TRANSLATION_ERROR_LENGTH = 160

/** The stored captions are showing and only their translation stopped, so a retry need not reload them. */
internal fun onlyTranslationFailed(state: DualSubUiState): Boolean =
    state.translationError != null && state.errorMessage == null && !state.liveFallback && state.segments.isNotEmpty()

/**
 * Event driven: sleeps when the window is ready, paused, or the app is backgrounded.
 *
 * Original rows keep following playback when translation fails: [onTranslationFailure] reports the
 * error once, and no further row is translated until [CaptionPlaybackRequest.translationAttempt]
 * changes, so a failing model is not retried in a loop.
 */
internal suspend fun translatePlaybackWindow(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> String,
    onTranslationFailure: (Exception) -> Unit = {},
    onWindow: (List<SubtitleSegment>, Boolean) -> Unit,
) {
    var indices = IntRange.EMPTY
    var rows = emptyList<SubtitleSegment>()
    var failedAttempt: Long? = null
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
        val next = nextWindowTranslation(rows, request).takeIf { failedAttempt != request.translationAttempt }
        onWindow(rows, next != null)
        if (next == null) {
            requests.first { it != request }
            continue
        }
        rows =
            try {
                translateRow(rows, next, translate)
            } catch (error: Exception) {
                // Cancelling this loop still ends it; a translator error only stops translating.
                currentCoroutineContext().ensureActive()
                failedAttempt = request.translationAttempt
                onTranslationFailure(error)
                continue
            }
        // An in-flight task may finish after a seek/pause. Cache it, but never publish at the wrong position.
        if (requests.value.enabled && requests.value.seekGeneration == request.seekGeneration) {
            onWindow(rows, nextWindowTranslation(rows, requests.value) != null)
        }
    }
}

/** Translates row [index] through its whole sentence. A failed prefix only loses the finer row split. */
private suspend fun translateRow(
    rows: List<SubtitleSegment>,
    index: Int,
    translate: suspend (String) -> String,
): List<SubtitleSegment> {
    val sentence = rows[index].sentence
    val translated = translate(sentence?.text ?: rows[index].originalText).trim()
    // Translating each row's sentence prefix locates where that row ends in the full translation.
    val prefixes =
        try {
            sentence?.cuts?.map { cut -> translate(sentence.text.substring(0, cut).trim()) }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null // Split the sentence's translation proportionally instead.
        }
    currentCoroutineContext().ensureActive()
    return withTranslation(rows, index, translated, prefixes)
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

/**
 * Rows [rows] dropped from the start of [previous] when the playback window slid forward, 0 for the
 * same window, or null when [rows] does not start inside [previous] (a seek, a reload, the first window).
 * Row ids are store indices, so equal ids are the same row in both windows.
 */
internal fun windowShift(
    previous: List<SubtitleSegment>,
    rows: List<SubtitleSegment>,
): Int? {
    val firstId = rows.firstOrNull()?.id ?: return null
    return previous.indexOfFirst { it.id == firstId }.takeIf { it >= 0 }
}
