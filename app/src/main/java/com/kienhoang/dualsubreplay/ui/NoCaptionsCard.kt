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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import kotlinx.coroutines.flow.StateFlow

/** What the no-captions card's buttons do: make the subtitles, or open Settings → AI assistant to add a key. */
internal class AiSubtitleActions(
    val make: () -> Unit,
    val openAiSettings: () -> Unit,
)

internal val LocalAiSubtitleActions = staticCompositionLocalOf<AiSubtitleActions?> { null }

/**
 * Shown in place of live subtitles when YouTube has no captions at all for the video (owner pick
 * 2026-11-08): says so, and offers AI subtitles with the AI assistant's Gemini key, or a way to add one.
 */
@Composable
internal fun NoCaptionsCard(
    status: AiSubtitleStatus?,
    geminiReady: Boolean,
    actions: AiSubtitleActions?,
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
        status?.problem?.let { problem ->
            Text(
                stringResource(aiSubtitleProblemText(problem)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ai_subtitles_problem"),
            )
        }
        if (geminiReady) {
            Button(onClick = actions.make, modifier = Modifier.testTag("ai_subtitles_make")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (status?.problem != null) R.string.ai_subtitles_try_again else R.string.ai_subtitles_make))
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

@Composable
private fun aiSubtitleProgressText(status: AiSubtitleStatus): String {
    val total = status.totalMs
    return when {
        status.doneMs <= 0L -> stringResource(R.string.ai_subtitles_starting)
        total != null -> stringResource(R.string.ai_subtitles_progress, formatPlaybackClock(status.doneMs), formatPlaybackClock(total))
        else -> stringResource(R.string.ai_subtitles_progress_unknown_length, formatPlaybackClock(status.doneMs))
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
    }

/** The no-captions card with the assistant's key state: it updates as soon as a key is added in settings. */
@Composable
private fun NoCaptionsSectionWithAssistant(
    state: DualSubUiState,
    assistant: StateFlow<AiAssistantUiState>,
) {
    val aiState by assistant.collectAsStateWithLifecycle()
    NoCaptionsCard(
        status = state.aiSubtitles?.takeIf { it.videoId == state.activeVideoId },
        geminiReady = geminiReady(aiState),
        actions = LocalAiSubtitleActions.current,
    )
}

/** True when the AI assistant is on and has a Gemini key, whichever service it uses now. */
internal fun geminiReady(state: AiAssistantUiState): Boolean =
    state.settings.enabled && state.keys[AiProvider.GEMINI].orEmpty().isNotEmpty()

/** The no-captions card with this screen's actions and the assistant's key state. */
@Composable
internal fun NoCaptionsSection(state: DualSubUiState) {
    val host = LocalAiAssistant.current
    if (host != null) {
        NoCaptionsSectionWithAssistant(state, host.controller.state)
    } else {
        // Builds without the assistant: no key to use, so only the explanation and the settings button.
        NoCaptionsCard(state.aiSubtitles?.takeIf { it.videoId == state.activeVideoId }, geminiReady = false, LocalAiSubtitleActions.current)
    }
}
