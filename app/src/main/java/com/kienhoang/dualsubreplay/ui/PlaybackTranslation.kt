package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.GOOGLE_RECHECK_INTERVAL_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

/**
 * A short reason for the translation bar; translator errors can be empty or very technical, so
 * those show [fallback] (the localized "Translation is unavailable right now…" text) instead.
 */
internal fun translationFailureMessage(
    error: Exception,
    fallback: String,
): String =
    error.message?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TRANSLATION_ERROR_LENGTH }
        ?: fallback

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
 *
 * [prefetch], when given, receives the texts of the next row and of the next minute's sentences first,
 * so an online engine can translate them in one request and [translate] then finds them cached.
 */
internal suspend fun translatePlaybackWindow(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    translate: suspend (String) -> String,
    onTranslationFailure: (Exception) -> Unit = {},
    prefetch: (suspend (List<String>) -> Unit)? = null,
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
                prefetch?.invoke(upcomingTranslationTexts(rows, next))
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
    val texts = rowTranslationTexts(rows[index])
    val translated = translate(texts.first()).trim()
    // Translating each row's sentence prefix locates where that row ends in the full translation.
    val prefixes =
        try {
            sentence?.let { texts.drop(1).map { prefix -> translate(prefix) } }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null // Split the sentence's translation proportionally instead.
        }
    currentCoroutineContext().ensureActive()
    return withTranslation(rows, index, translated, prefixes)
}

/** What translating [row] sends: its whole sentence, then each row's sentence prefix (or just the row's text). */
internal fun rowTranslationTexts(row: SubtitleSegment): List<String> {
    val sentence = row.sentence ?: return listOf(row.originalText)
    return listOf(sentence.text) + sentence.cuts.map { cut -> sentence.text.substring(0, cut).trim() }
}

/** How far past the row being translated an online engine translates in the same request. */
internal const val PREFETCH_AHEAD_MS = 60_000L

/**
 * The texts of row [index] and of every later untranslated sentence that starts within [aheadMs] of
 * it (in practice the next minute of the window), each sentence once.
 */
internal fun upcomingTranslationTexts(
    rows: List<SubtitleSegment>,
    index: Int,
    aheadMs: Long = PREFETCH_AHEAD_MS,
): List<String> {
    val horizon = rows[index].startMs + aheadMs
    val upcoming = (index..rows.lastIndex).map(rows::get).takeWhile { it.startMs <= horizon }
    return translationTexts(upcoming.filterIndexed { position, row -> position == 0 || row.translatedText == null })
}

/** Everything translating [rows] sends: each sentence (or lone row) once, with its row prefixes. */
internal fun translationTexts(rows: List<SubtitleSegment>): List<String> =
    rows.distinctBy { it.sentence?.text ?: it.originalText }.flatMap(::rowTranslationTexts).distinct()

/** How far ahead the online engine translates in the background, past the [PREFETCH_AHEAD_MS] the window asks for. */
internal const val BACKGROUND_PREFETCH_AHEAD_MS = 5 * 60_000L

/** The wait before each background request, so filling minutes 2 to 5 never sends a burst. */
internal const val BACKGROUND_PREFETCH_PAUSE_MS = 3_000L

/** Once everything ahead is translated, the background waits for this much playback (or a seek) before looking again. */
internal const val BACKGROUND_PREFETCH_STEP_MS = 30_000L

/** Five minutes of speech is far fewer rows; this only bounds one read. */
private const val MAX_BACKGROUND_PREFETCH_ROWS = 400

/**
 * While the video plays, hands the sentences starting 1 to 5 minutes ahead to [fetch], one step at a
 * time with [pauseMs] before each. [fetch] sends one request for the texts not translated yet and
 * returns the texts it sent (none when all were translated); a text is handed over at most once, so a
 * line Google leaves blank is not asked again here. A failed step waits [restMs]: the playing minute,
 * not this prefetch, decides when Google has stopped working.
 */
internal suspend fun prefetchInBackground(
    store: SubtitleStore,
    requests: StateFlow<CaptionPlaybackRequest>,
    pauseMs: Long = BACKGROUND_PREFETCH_PAUSE_MS,
    restMs: Long = GOOGLE_RECHECK_INTERVAL_MS,
    fetch: suspend (List<String>) -> List<String>,
) {
    val sent = HashSet<String>()
    while (true) {
        delay(pauseMs)
        val request = requests.value
        if (!request.enabled || request.paused) {
            requests.first { it.enabled && !it.paused }
            continue
        }
        val fetched =
            try {
                val indices =
                    store.indicesBetween(
                        request.timeMs + PREFETCH_AHEAD_MS,
                        request.timeMs + BACKGROUND_PREFETCH_AHEAD_MS,
                        MAX_BACKGROUND_PREFETCH_ROWS,
                    )
                val rows = withContext(Dispatchers.IO) { store.read(indices) }
                fetch(translationTexts(rows).filterNot(sent::contains))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(restMs)
                continue
            }
        sent += fetched
        if (fetched.isEmpty()) {
            requests.first {
                it.seekGeneration != request.seekGeneration ||
                    it.timeMs !in request.timeMs until request.timeMs + BACKGROUND_PREFETCH_STEP_MS
            }
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
