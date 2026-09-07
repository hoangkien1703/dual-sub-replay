package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

internal data class LearningOverlayContent(
    val originalText: String?,
    val translatedText: String?,
    val statusText: String?,
    val activeWordIndex: Int = -1,
    val segment: SubtitleSegment? = null,
)

internal fun learningOverlayContent(state: DualSubUiState): LearningOverlayContent? {
    val content = unfilteredLearningOverlayContent(state) ?: return null
    return content.copy(
        originalText = content.originalText.takeIf { state.showOriginal() },
        translatedText = content.translatedText.takeIf { state.showTranslation() },
        statusText = content.statusText.takeIf { state.showOriginal() || state.showTranslation() },
    )
}

private fun unfilteredLearningOverlayContent(state: DualSubUiState): LearningOverlayContent? {
    if (state.activeVideoId == null) return null
    if (state.liveFallback) {
        return LearningOverlayContent(
            originalText = state.liveOriginal,
            translatedText = state.liveTranslated ?: if (state.liveOriginal != null) "Translating…" else null,
            statusText = "Live subtitles · ${state.statusMessage.orEmpty()}",
        )
    }
    val active = state.segments.getOrNull(state.currentIndex)
    if (active != null) {
        return LearningOverlayContent(
            originalText = active.originalText,
            translatedText = active.translatedText ?: "Translating…",
            statusText = null,
            activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1,
            segment = active,
        )
    }
    val status =
        state.errorMessage ?: state.statusMessage
            ?: if (state.segments.isNotEmpty()) "Waiting for the next caption…" else null
    return status?.let {
        LearningOverlayContent(originalText = null, translatedText = null, statusText = it)
    }
}

internal fun LearningOverlayContent.isEmpty(): Boolean = originalText == null && translatedText == null && statusText == null
