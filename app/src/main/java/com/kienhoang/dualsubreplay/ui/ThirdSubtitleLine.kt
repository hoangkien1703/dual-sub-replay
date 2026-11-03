package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.awaitWithin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException

// Settings → Languages → Third line: one more translation under each subtitle, in a third language.
// Ported from mrAlishah's Triple Sub Replay fork (docs/specs/2026-11-02-saved-word-notes-and-third-line.md):
// the lines live beside SubtitleSegment, by row id, so the two-line pipeline and the subtitle store stay as they are.

internal const val THIRD_LANGUAGE_PREFERENCE = "third_subtitle_language"

/** Settings → Layout & format → Third subtitle line; off by default (owner request 2026-11-02). */
internal const val THIRD_LINE_ENABLED_PREFERENCE = "third_subtitle_line_enabled"

/** The switch and the language it remembers; the language is kept while the switch is off. */
data class ThirdLineChoice(
    val enabled: Boolean = false,
    val language: String? = null,
) {
    /** The language the line shows in, or null while it is off or no language is picked. */
    val shownLanguage: String? get() = language.takeIf { enabled }
}

internal val DualSubUiState.thirdLine: ThirdLineChoice get() = ThirdLineChoice(thirdLineEnabled, thirdLanguage)

/** The saved third language, or null (off, the default) when nothing valid is saved. */
internal fun storedThirdLanguage(raw: String?): String? =
    raw
        ?.takeIf(String::isNotBlank)
        ?.let(TranslationLanguages::normalize)
        ?.takeIf(TranslationLanguages::isSupported)

/** The third lines of [videoId]'s rows near playback, by row id, in [language]. */
data class ThirdLines(
    val videoId: String? = null,
    val language: String? = null,
    val lines: Map<Long, String> = emptyMap(),
)

/** The language to translate the third line into: null while it is off, or when it would repeat the original or the translation. */
internal fun thirdLineLanguage(
    third: String?,
    source: String?,
    target: String,
): String? {
    val language = third?.let(TranslationLanguages::normalize) ?: return null
    val shown = listOfNotNull(source, target).map(TranslationLanguages::normalize)
    return language.takeIf { it !in shown }
}

/** Row [segment]'s third line, only for the open video and the language chosen now; null until it arrives. */
internal fun DualSubUiState.thirdLineFor(segment: SubtitleSegment?): String? {
    if (segment == null || liveFallback) return null
    val language = thirdLineLanguage(thirdLine.shownLanguage, resolvedSourceLanguage, targetLanguage) ?: return null
    return thirdLines
        .takeIf { it.videoId == activeVideoId && it.language == language }
        ?.lines
        ?.get(segment.id)
        ?.takeIf(String::isNotBlank)
}

/** [selection] with its line's third subtitle line, when it has none yet and that line is on screen for its video. */
internal fun DualSubUiState.withThirdLineOnScreen(selection: LearningWordSelection): LearningWordSelection {
    if (selection.thirdText != null || selection.videoId == null || selection.videoId != activeVideoId) return selection
    val text = thirdLineFor(selection.segment) ?: return selection
    return selection.copy(thirdText = text, thirdLanguage = thirdLines.language)
}

/**
 * Translates the rows near playback into the third language with the same window loop as the second
 * line, on the same display store so the row ids match. Google first (an unanswered or blank line goes
 * to the device when it has a model), or only the device with "On this device only". A failure only
 * stops this line until the next "Retry translation": the other two lines never wait for it.
 */
internal class ThirdLineTranslation(
    private val state: MutableStateFlow<DualSubUiState>,
    private val requests: StateFlow<CaptionPlaybackRequest>,
    private val google: GoogleWebTranslator,
    private val onDevice: OnDeviceTranslator,
    private val googleWaitMs: () -> Long,
    private val isCurrentLoad: (DualSubUiState, String, Long) -> Boolean,
) {
    suspend fun follow(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        source: String,
        googleRequests: CoroutineScope,
    ) {
        state
            .map { thirdLineLanguage(it.thirdLine.shownLanguage, source, it.targetLanguage) to it.translationEngine }
            .distinctUntilChanged()
            .collectLatest { (third, engine) ->
                publish(videoId, generation, ThirdLines(videoId, third))
                if (third == null) return@collectLatest
                val publishRows = { rows: List<SubtitleSegment>, _: Boolean ->
                    val lines = rows.mapNotNull { row -> row.translatedText?.let { row.id to it } }.toMap()
                    publish(videoId, generation, ThirdLines(videoId, third, lines))
                }
                val deviceCan = onDevice.supports(source, third)
                if (engine == TranslationEngine.ON_DEVICE) {
                    // No model for this pair and no going online: the line stays off.
                    if (!deviceCan) return@collectLatest
                    onDevice.withSession(
                        source,
                        third,
                    ) { translate -> translatePlaybackWindow(store, requests, translate, onWindow = publishRows) }
                    return@collectLatest
                }
                translatePlaybackWindow(
                    store,
                    requests,
                    translate = { text -> viaGoogle(source, third, text, googleRequests, deviceCan) },
                    prefetch = { texts -> prefetchGoogle(source, third, texts, googleRequests) },
                    onWindow = publishRows,
                )
            }
    }

    private suspend fun viaGoogle(
        source: String,
        third: String,
        text: String,
        googleRequests: CoroutineScope,
        deviceCan: Boolean,
    ): String {
        val online =
            try {
                awaitWithin(googleRequests, googleWaitMs()) { google.translate(source, third, text) }
            } catch (error: IOException) {
                if (!deviceCan) throw error
                null
            }
        return online ?: if (deviceCan) onDevice.translateSingle(source, third, text) else ""
    }

    /** Upcoming lines in one Google request, so rows then read them from the cache; a failure here is left to [viaGoogle]. */
    private suspend fun prefetchGoogle(
        source: String,
        third: String,
        texts: List<String>,
        googleRequests: CoroutineScope,
    ) {
        try {
            awaitWithin(googleRequests, googleWaitMs()) { google.translateAll(source, third, texts) }
        } catch (_: IOException) {
            // The row's own request tries again and falls back to the device.
        }
    }

    private fun publish(
        videoId: String,
        generation: Long,
        lines: ThirdLines,
    ) {
        state.update { current -> if (isCurrentLoad(current, videoId, generation)) current.copy(thirdLines = lines) else current }
    }
}
