package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import kotlinx.coroutines.flow.StateFlow

/** What the no-captions card's buttons do: make the subtitles, or open Settings → AI assistant to add a key. */
internal class AiSubtitleActions(
    val make: () -> Unit,
    val openAiSettings: () -> Unit,
    /**
     * "AI transcript (Gemini)" in the Original language list, or the song card's button: the open
     * video's Gemini transcript, made now when missing (Settings → AI assistant without a key).
     */
    val useTranscript: () -> Unit = make,
    /** The song card's "Get real lyrics": free synced lyrics only, never AI (owner pick 2026-11-24). */
    val findLyrics: () -> Unit = useTranscript,
    /** "Write lyrics with AI" (or "Write the rest with AI") after free lyrics fell short: Gemini now. */
    val writeWithAi: () -> Unit = useTranscript,
    /** The song card's X: no more offers for this video. */
    val closeSuggestion: () -> Unit = {},
    /** True while a video is open, so the Original language list can offer the AI transcript. */
    val videoOpen: () -> Boolean = { true },
)

internal val LocalAiSubtitleActions = staticCompositionLocalOf<AiSubtitleActions?> { null }

/**
 * Shown in place of live subtitles when YouTube has no captions at all for the video (owner pick
 * 2026-11-08): says so, and offers AI subtitles with the AI assistant's Gemini key, or a way to add one.
 * A [song] offers its free synced lyrics first, and AI only once none matched (owner pick 2026-11-25).
 */
@Composable
internal fun NoCaptionsCard(
    status: AiSubtitleStatus?,
    geminiReady: Boolean,
    actions: AiSubtitleActions?,
    song: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().testTag("no_captions_card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_subtitles_no_captions_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_subtitles_no_captions_body), style = MaterialTheme.typography.bodyMedium)
        if (actions == null) return@Column
        if (status?.working == true) {
            val total = status.totalMs
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (status.doneMs.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("ai_subtitles_progress"),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ai_subtitles_progress"))
            }
            Text(aiSubtitleProgressText(status), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val noFreeLyrics = status?.problem == AiSubtitleProblem.NO_FREE_LYRICS
        status?.problem?.let { problem ->
            Text(
                stringResource(if (noFreeLyrics && geminiReady) R.string.ai_transcript_no_free_lyrics else aiSubtitleProblemText(problem)),
                style = MaterialTheme.typography.bodySmall,
                color = if (noFreeLyrics) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ai_subtitles_problem"),
            )
        }
        if (song && !noFreeLyrics) {
            FreeLyricsButton(actions.findLyrics)
        } else if (geminiReady) {
            Button(onClick = actions.make, modifier = Modifier.testTag("ai_subtitles_make")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val label =
                    when {
                        noFreeLyrics -> R.string.ai_transcript_write_with_ai
                        status?.problem != null -> R.string.ai_subtitles_try_again
                        else -> R.string.ai_subtitles_make
                    }
                Text(stringResource(label))
            }
            Text(
                stringResource(R.string.ai_subtitles_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = actions.openAiSettings, modifier = Modifier.testTag("ai_subtitles_add_key")) {
                Text(stringResource(R.string.ai_subtitles_add_key))
            }
            Text(
                stringResource(R.string.ai_subtitles_needs_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A live stream with no captions now. AI subtitles can't listen to a live stream (Gemini can't open
 * one), so there is no button: the player's own captions show up here if the stream starts sending them.
 */
@Composable
internal fun LiveStreamNoCaptionsCard() {
    Column(Modifier.fillMaxWidth().testTag("live_no_captions_card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.live_no_captions_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.live_no_captions_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun aiSubtitleProgressText(status: AiSubtitleStatus): String = aiSubtitleProgressText(status, LocalResources.current.uiStrings())

internal fun aiSubtitleProgressText(
    status: AiSubtitleStatus,
    strings: UiStrings,
): String {
    val total = status.totalMs
    return when {
        status.lookingForLyrics && status.lyricsTotal > 1 ->
            strings.get(R.string.ai_transcript_looking_for_song_lyrics, status.lyricsChecked + 1, status.lyricsTotal)
        status.lookingForLyrics -> strings.get(R.string.ai_transcript_looking_for_lyrics)
        status.doneMs <= 0L -> strings.get(R.string.ai_subtitles_starting)
        total != null -> strings.get(R.string.ai_subtitles_progress, formatPlaybackClock(status.doneMs), formatPlaybackClock(total))
        else -> strings.get(R.string.ai_subtitles_progress_unknown_length, formatPlaybackClock(status.doneMs))
    }
}

@StringRes
internal fun aiSubtitleProblemText(problem: AiSubtitleProblem): Int =
    when (problem) {
        AiSubtitleProblem.NO_KEY -> R.string.ai_subtitles_problem_key
        AiSubtitleProblem.VIDEO_UNAVAILABLE -> R.string.ai_subtitles_problem_video
        AiSubtitleProblem.LIMIT -> R.string.ai_subtitles_problem_limit
        AiSubtitleProblem.BUSY -> R.string.ai_subtitles_problem_busy
        AiSubtitleProblem.NETWORK -> R.string.ai_subtitles_problem_network
        AiSubtitleProblem.REFUSED -> R.string.ai_subtitles_problem_refused
        AiSubtitleProblem.NO_SPEECH -> R.string.ai_subtitles_problem_no_speech
        AiSubtitleProblem.FAILED -> R.string.ai_subtitles_problem_failed
        AiSubtitleProblem.NO_FREE_LYRICS -> R.string.ai_transcript_no_lyrics_needs_key
        AiSubtitleProblem.SOME_FREE_LYRICS -> R.string.ai_transcript_some_lyrics_needs_key
    }

/** A song's first step on the no-captions card: its free synced lyrics, with no key and no AI. */
@Composable
private fun FreeLyricsButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.testTag("ai_subtitles_find_lyrics")) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ai_transcript_make))
    }
    Text(
        stringResource(R.string.ai_transcript_offer_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The no-captions card with the assistant's key state: it updates as soon as a key is added in settings. */
@Composable
private fun NoCaptionsSectionWithAssistant(
    state: DualSubUiState,
    assistant: StateFlow<AiAssistantUiState>,
    song: Boolean,
) {
    val aiState by assistant.collectAsStateWithLifecycle()
    NoCaptionsCard(
        status = state.aiSubtitles?.takeIf { it.videoId == state.activeVideoId },
        geminiReady = geminiReady(aiState),
        actions = LocalAiSubtitleActions.current,
        song = song,
    )
}

/** True when the AI assistant is on and has a Gemini key, whichever service it uses now. */
internal fun geminiReady(state: AiAssistantUiState): Boolean =
    state.settings.enabled && state.keys[AiProvider.GEMINI].orEmpty().isNotEmpty()

/** The no-captions card with this screen's actions and the assistant's key state. */
@Composable
internal fun NoCaptionsSection(state: DualSubUiState) {
    if (state.liveStream) {
        LiveStreamNoCaptionsCard()
        return
    }
    val host = LocalAiAssistant.current
    val playback by WebVideoBridge.playback.collectAsStateWithLifecycle()
    val song = looksLikeSong(playback?.takeIf { it.videoId == state.activeVideoId })
    val status = state.aiSubtitles?.takeIf { it.videoId == state.activeVideoId }
    if (host != null) {
        NoCaptionsSectionWithAssistant(state, host.controller.state, song)
    } else {
        // Builds without the assistant: no key to use, so the explanation, a song's free lyrics and the settings button.
        NoCaptionsCard(status, geminiReady = false, LocalAiSubtitleActions.current, song)
    }
}

/**
 * A Bilibili video (beta) without subtitles: Bilibili China shows most of them only to signed-in
 * viewers, so the card asks for a sign-in on the page; Try again loads them again. No AI subtitles: Gemini
 * reads YouTube links only.
 */
@Composable
internal fun BilibiliNoSubtitlesCard(
    signInNeeded: Boolean,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("bilibili_no_subtitles_card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (signInNeeded) R.string.bilibili_sign_in_title else R.string.bilibili_no_subtitles_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(if (signInNeeded) R.string.bilibili_sign_in_body else R.string.bilibili_no_subtitles_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag("bilibili_try_again")) {
            Text(stringResource(R.string.bilibili_try_again))
        }
    }
}
