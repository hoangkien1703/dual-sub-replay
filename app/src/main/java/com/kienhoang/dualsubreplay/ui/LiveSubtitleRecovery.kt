package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

internal const val LIVE_TRANSLATION_DEBOUNCE_MS = 300L

internal suspend fun debouncedLiveTranslation(
    key: LiveTranslationKey,
    isCurrent: () -> Boolean,
    translate: suspend (String, String, String) -> String,
): String? {
    kotlinx.coroutines.delay(LIVE_TRANSLATION_DEBOUNCE_MS)
    if (!isCurrent()) return null
    val result = translate(key.language, key.target, key.text)
    return result.takeIf { isCurrent() }
}

internal data class LiveTranslationKey(
    val videoId: String,
    val language: String,
    val target: String,
    val text: String,
)

internal fun liveTranslationKey(
    sample: LiveCaptionSample?,
    videoId: String,
    target: String,
): LiveTranslationKey? {
    if (sample == null || !sample.present || sample.videoId != videoId) return null
    val language =
        sample.languageCode
            ?.takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*")) }
            ?.let(TranslationLanguages::normalize) ?: return null
    val text = sample.text.replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty() || text.length > 4_000) return null
    return LiveTranslationKey(videoId, language, target, text)
}

/** Revision numbers may change without the displayed words changing. */
internal class LiveTranslationGate {
    var key: LiveTranslationKey? = null
        private set
    var generation = 0L
        private set

    fun update(
        next: LiveTranslationKey?,
        seek: Boolean = false,
    ): Boolean {
        if (!seek && next == key) return false
        key = next
        generation++
        return true
    }

    fun accepts(
        ticket: Long,
        expected: LiveTranslationKey,
    ) = generation == ticket && key == expected

    fun reset() {
        key = null
        generation++
    }
}

@Composable
internal fun LiveSubtitlePanel(
    state: DualSubUiState,
    onRetry: () -> Unit,
    onWordClick: (WordTap) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Live subtitles", style = MaterialTheme.typography.labelMedium)
        Text(state.statusMessage ?: "Current captions only; paragraph replay is unavailable.", style = MaterialTheme.typography.bodySmall)
        state.liveOriginal?.let { original ->
            CompactSubtitleCard(
                segment = SubtitleSegment(0, 0, 0, original, state.liveTranslated, labCaptionWords(original)),
                activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1,
                showOriginal = state.showOriginal(),
                showTranslation = state.showTranslation(),
                active = true,
                fontScale = state.fontScale,
                onReplay = {},
                replayEnabled = false,
                wordLearningEnabled = state.wordLearningEnabled && state.resolvedSourceLanguage != null,
                wordLearningTarget = state.wordLearningTarget,
                tapToLearnEnabled = state.tapToLearnEnabled,
                resolvedSourceLanguage = state.resolvedSourceLanguage,
                targetLanguage = state.targetLanguage,
                onWordClick = { onWordClick(it.copy(segment = null)) },
            )
        }
        TextButton(onClick = onRetry, enabled = !state.retryingTranscript) {
            Text(if (state.retryingTranscript) "Retrying full transcript…" else "Retry full transcript")
        }
    }
}
