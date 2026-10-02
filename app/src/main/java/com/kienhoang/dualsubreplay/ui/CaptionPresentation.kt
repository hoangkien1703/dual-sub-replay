package com.kienhoang.dualsubreplay.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord

internal data class LearningOverlayContent(
    val originalText: String?,
    val translatedText: String?,
    val statusText: String?,
    val activeWordIndex: Int = -1,
    val segment: SubtitleSegment? = null,
    val words: List<SubtitleWord> = segment?.words.orEmpty(),
)

/** Looks up the app's own texts, so plain JVM tests can pass a stub instead of Android resources. */
internal interface UiStrings {
    fun get(
        @StringRes id: Int,
        vararg formatArgs: Any,
    ): String
}

/** [UiStrings] read from these resources, in the interface language. */
internal fun Resources.uiStrings(): UiStrings =
    object : UiStrings {
        override fun get(
            id: Int,
            vararg formatArgs: Any,
        ): String = if (formatArgs.isEmpty()) getString(id) else getString(id, *formatArgs)
    }

/** [learningOverlayContent] with the app's own texts in the interface language. */
@Composable
internal fun learningOverlayContent(state: DualSubUiState): LearningOverlayContent? =
    learningOverlayContent(state, LocalResources.current.uiStrings())

internal fun learningOverlayContent(
    state: DualSubUiState,
    strings: UiStrings,
): LearningOverlayContent? {
    val content = unfilteredLearningOverlayContent(state, strings) ?: return null
    return content.copy(
        originalText = content.originalText.takeIf { state.showOriginal() },
        translatedText = content.translatedText.takeIf { state.showTranslation() },
        statusText = content.statusText.takeIf { state.showOriginal() || state.showTranslation() },
    )
}

private fun unfilteredLearningOverlayContent(
    state: DualSubUiState,
    strings: UiStrings,
): LearningOverlayContent? {
    if (state.activeVideoId == null) return null
    if (state.liveFallback) {
        return LearningOverlayContent(
            activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1,
            words = state.liveOriginal?.let(::liveCaptionWords).orEmpty(),
            originalText = state.liveOriginal,
            translatedText = state.liveTranslated ?: if (state.liveOriginal != null) strings.get(R.string.player_translating) else null,
            statusText = strings.get(R.string.player_live_subtitles_status, state.statusMessage.orEmpty()),
        )
    }
    val active = state.segments.getOrNull(state.currentIndex)
    if (active != null) {
        return LearningOverlayContent(
            originalText = active.originalText,
            translatedText = active.translatedText ?: strings.get(pendingTranslationTextRes(false, state.translationError != null)),
            statusText = null,
            activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1,
            segment = active,
        )
    }
    val status =
        state.errorMessage ?: state.statusMessage
            ?: if (state.segments.isNotEmpty()) strings.get(R.string.player_waiting_next_caption) else null
    return status?.let {
        LearningOverlayContent(originalText = null, translatedText = null, statusText = it)
    }
}

internal fun LearningOverlayContent.isEmpty(): Boolean = originalText == null && translatedText == null && statusText == null
