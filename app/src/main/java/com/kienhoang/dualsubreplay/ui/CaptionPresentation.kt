package com.kienhoang.dualsubreplay.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import com.kienhoang.dualsubreplay.data.VideoKeys

internal data class LearningOverlayContent(
    val originalText: String?,
    val translatedText: String?,
    val statusText: String?,
    val activeWordIndex: Int = -1,
    val segment: SubtitleSegment? = null,
    val words: List<SubtitleWord> = segment?.words.orEmpty(),
    /** Settings → Languages → Third line, under the translation; null while off or not translated yet. */
    val thirdText: String? = null,
    /** A video with no captions: what the box's button offers (see [noCaptionsOverlayAction]); null shows Retry. */
    val noCaptionsAction: NoCaptionsOverlayAction? = null,
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
        thirdText = content.thirdText.takeIf { state.showTranslation() },
        statusText = content.statusText.takeIf { state.showOriginal() || state.showTranslation() },
    )
}

private fun unfilteredLearningOverlayContent(
    state: DualSubUiState,
    strings: UiStrings,
): LearningOverlayContent? {
    if (state.activeVideoId == null) return null
    if (state.showsNoCaptions()) {
        // Nothing to wait for: say so, never "Live subtitles · Waiting for YouTube captions".
        return LearningOverlayContent(originalText = null, translatedText = null, statusText = noCaptionsStatus(state, strings))
    }
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
            thirdText = state.thirdLineFor(active),
        )
    }
    val status =
        state.errorMessage ?: state.statusMessage
            ?: if (state.segments.isNotEmpty()) strings.get(R.string.player_waiting_next_caption) else null
    return status?.let {
        LearningOverlayContent(originalText = null, translatedText = null, statusText = it)
    }
}

/**
 * True while YouTube has no captions at all for the open video and its player shows none: the
 * subtitle panel shows the no-captions card, and the box on the video says so in one line.
 */
internal fun DualSubUiState.showsNoCaptions(): Boolean = liveFallback && noCaptions && liveOriginal == null

/** The open video's AI subtitles run, if any. */
internal fun DualSubUiState.openVideoAiSubtitles(): AiSubtitleStatus? = aiSubtitles?.takeIf { it.videoId == activeVideoId }

/** What the button on the box over the video offers for a video with no captions (see [noCaptionsOverlayAction]). */
internal enum class NoCaptionsOverlayAction {
    /** A song: free synced lyrics first, never AI (owner pick 2026-11-25). */
    GET_REAL_LYRICS,

    /** The AI assistant has a Gemini key: make the subtitles now (for a song, after no free lyrics matched). */
    MAKE_AI_SUBTITLES,

    /** No Gemini key yet: open Settings → AI assistant. Nothing turns on by itself. */
    ADD_GEMINI_KEY,
}

/**
 * The button the box over the video shows for a YouTube video with no captions, like the panel's
 * no-captions card: none for a live stream (Gemini can't listen to one), a Bilibili video (Gemini
 * reads YouTube links only) or while AI subtitles are being made. A [song] offers its free synced
 * lyrics first.
 */
internal fun noCaptionsOverlayAction(
    state: DualSubUiState,
    geminiReady: Boolean,
    song: Boolean = false,
): NoCaptionsOverlayAction? {
    if (!state.showsNoCaptions() || state.liveStream || VideoKeys.isBilibili(state.activeVideoId)) return null
    if (state.openVideoAiSubtitles()?.working == true) return null
    if (song && !state.noFreeLyrics()) return NoCaptionsOverlayAction.GET_REAL_LYRICS
    return if (geminiReady) NoCaptionsOverlayAction.MAKE_AI_SUBTITLES else NoCaptionsOverlayAction.ADD_GEMINI_KEY
}

/** Free synced lyrics were looked for and none matched the open video. */
internal fun DualSubUiState.noFreeLyrics(): Boolean = openVideoAiSubtitles()?.problem == AiSubtitleProblem.NO_FREE_LYRICS

/** "Retry full transcript" on the box over the video: only while live subtitles may still come, or for Bilibili's Try again. */
internal fun showsOverlayRetry(state: DualSubUiState): Boolean =
    state.liveFallback && !state.retryingTranscript && (!state.showsNoCaptions() || VideoKeys.isBilibili(state.activeVideoId))

private fun noCaptionsStatus(
    state: DualSubUiState,
    strings: UiStrings,
): String {
    // Bilibili's own words (sign in, or no subtitles) are already the status.
    if (VideoKeys.isBilibili(state.activeVideoId)) return state.statusMessage ?: strings.get(R.string.bilibili_no_subtitles_title)
    if (state.liveStream) return strings.get(R.string.live_no_captions_title)
    val ai = state.openVideoAiSubtitles()
    if (ai?.working == true) return aiSubtitleProgressText(ai, strings)
    ai?.problem?.let { return strings.get(aiSubtitleProblemText(it)) }
    return strings.get(R.string.ai_subtitles_no_captions_body)
}

internal fun LearningOverlayContent.isEmpty(): Boolean = originalText == null && translatedText == null && statusText == null
