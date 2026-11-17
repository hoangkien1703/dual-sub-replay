package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import com.kienhoang.dualsubreplay.data.AI_SUBTITLE_TRACK_ID
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import com.kienhoang.dualsubreplay.playback.WebVideoPlayback
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

/**
 * Words in a title that mark a song: lyric and music videos, official audio, covers, karaoke, and
 * "lyrics" in the languages the app's users watch most.
 */
private val SONG_TITLE =
    Regex(
        "\\b(lyrics?|lyric video|music video|official video|official audio|official mv|m/?v|karaoke|" +
            "visuali[sz]er|cover|letra|paroles|testo|lời bài hát|vietsub)\\b|歌詞|歌词|가사|\\(audio\\)|\\[audio\\]",
        RegexOption.IGNORE_CASE,
    )

/** YouTube's own artist channels ("Artist - Topic") and VEVO channels upload only music. */
private val SONG_CHANNEL = Regex("( - Topic$|vevo|lyrics)", RegexOption.IGNORE_CASE)

/**
 * True when the open video looks like a song: YouTube files it under Music, or its title or channel
 * says so. YouTube's automatic captions cut songs into short, often wrong pieces (seen 2026-11-17).
 */
internal fun looksLikeSong(playback: WebVideoPlayback?): Boolean {
    if (playback == null) return false
    return playback.music || SONG_TITLE.containsMatchIn(playback.title) || SONG_CHANNEL.containsMatchIn(playback.channel)
}

/**
 * Whether the panel offers better lyrics (owner picks 2026-11-17): the open video looks like a song,
 * shows YouTube's auto-generated captions with no human-made track in that language, and the card
 * was not closed for this video. It shows without a Gemini key too, because LRCLIB's synced lyrics
 * are free; nothing is sent until the button is tapped.
 */
internal fun shouldSuggestAiTranscript(
    state: DualSubUiState,
    song: Boolean,
): Boolean {
    val videoId = state.activeVideoId ?: return false
    if (!song || videoId in state.aiTranscriptSuggestionClosed) return false
    if (state.liveFallback || state.segments.isEmpty() || state.loadedTrackId == AI_SUBTITLE_TRACK_ID) return false
    if (!state.generatedCaptions) return false
    val loaded = state.resolvedSourceLanguage?.let(TranslationLanguages::normalize)
    val humanTrack =
        state.availableSourceLanguages.any { track ->
            !track.generated && track.trackId != AI_SUBTITLE_TRACK_ID &&
                (loaded == null || TranslationLanguages.normalize(track.code) == loaded)
        }
    return !humanTrack
}

/** The Original language list's AI transcript choice, in builds with the AI assistant while a video is open. */
@Composable
internal fun aiTranscriptChoiceLabel(): String? {
    val actions = LocalAiSubtitleActions.current ?: return null
    if (LocalAiAssistant.current == null || !actions.videoOpen()) return null
    return stringResource(R.string.ai_transcript_choice)
}

/**
 * Above the transcript: progress while lyrics are found or Gemini writes a transcript for a video
 * that already shows subtitles, a problem with Try again, or the better-lyrics card for a song.
 */
@Composable
internal fun AiTranscriptBanner(state: DualSubUiState) {
    val actions = LocalAiSubtitleActions.current ?: return
    val host = LocalAiAssistant.current ?: return
    val videoId = state.activeVideoId ?: return
    val assistant by host.controller.state.collectAsStateWithLifecycle()
    val playback by WebVideoBridge.playback.collectAsStateWithLifecycle()
    AiTranscriptBannerContent(state, looksLikeSong(playback?.takeIf { it.videoId == videoId }), geminiReady(assistant), actions)
}

/** [AiTranscriptBanner] with the song check and the key state already read (tests draw it directly). */
@Composable
internal fun AiTranscriptBannerContent(
    state: DualSubUiState,
    song: Boolean,
    geminiReady: Boolean,
    actions: AiSubtitleActions,
) {
    val videoId = state.activeVideoId ?: return
    val status = state.aiSubtitles?.takeIf { it.videoId == videoId }
    val closed = videoId in state.aiTranscriptSuggestionClosed
    when {
        status?.working == true -> AiTranscriptProgress(status)
        status?.problem != null && !closed -> AiTranscriptProblem(status, geminiReady, actions)
        shouldSuggestAiTranscript(state, song) -> AiTranscriptOffer(actions)
    }
}

@Composable
private fun BannerSurface(
    tag: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp).testTag(tag),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun AiTranscriptOffer(actions: AiSubtitleActions) {
    BannerSurface("ai_transcript_offer") {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.ai_transcript_offer),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            CloseIconButton(actions.closeSuggestion, Modifier.size(36.dp), tag = "ai_transcript_offer_close")
        }
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = actions.useTranscript, modifier = Modifier.testTag("ai_transcript_make")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_transcript_make))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.ai_transcript_offer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AiTranscriptProgress(status: AiSubtitleStatus) {
    BannerSurface("ai_transcript_progress") {
        Text(aiSubtitleProgressText(status), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        val total = status.totalMs
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (status.doneMs.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    }
}

@Composable
private fun AiTranscriptProblem(
    status: AiSubtitleStatus,
    geminiReady: Boolean,
    actions: AiSubtitleActions,
) {
    val problem = status.problem ?: return
    BannerSurface("ai_transcript_problem") {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                stringResource(aiSubtitleProblemText(problem)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            CloseIconButton(actions.closeSuggestion, Modifier.size(36.dp), tag = "ai_transcript_problem_close")
        }
        val needsKey = problem == AiSubtitleProblem.NO_LYRICS_NEEDS_KEY || problem == AiSubtitleProblem.SOME_LYRICS_NEEDS_KEY
        if (needsKey && !geminiReady) {
            TextButton(onClick = actions.openAiSettings, modifier = Modifier.testTag("ai_transcript_add_key")) {
                Text(stringResource(R.string.ai_subtitles_add_key))
            }
        } else {
            TextButton(onClick = actions.useTranscript, modifier = Modifier.testTag("ai_transcript_retry")) {
                Text(stringResource(R.string.ai_subtitles_try_again))
            }
        }
    }
}
