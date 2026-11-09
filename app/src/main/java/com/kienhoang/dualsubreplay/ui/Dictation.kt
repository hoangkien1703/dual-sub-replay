package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.DictationCheck
import com.kienhoang.dualsubreplay.data.DictationResult
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.SubtitleSegment

/**
 * Settings → Study tools → Play mode → Dictation (owner pick 2026-11-09): which lines the learner
 * revealed, what they typed and how it checked, per video and line. Kept only while the app runs,
 * above the subtitle panel, so turning the phone or reopening the panel keeps it.
 */
internal class DictationState {
    private val answers = mutableStateMapOf<DictationKey, String>()
    private val results = mutableStateMapOf<DictationKey, DictationResult>()
    private val revealed = mutableStateMapOf<DictationKey, Boolean>()

    fun answer(key: DictationKey): String = answers[key].orEmpty()

    fun result(key: DictationKey): DictationResult? = results[key]

    fun isRevealed(key: DictationKey): Boolean = revealed[key] == true

    fun type(
        key: DictationKey,
        text: String,
    ) {
        answers[key] = text
    }

    fun reveal(key: DictationKey) {
        revealed[key] = true
    }

    fun check(
        key: DictationKey,
        line: SubtitleSegment,
        languageCode: String?,
    ) {
        val tokens =
            if (DictationCheck.modeFor(line.originalText, languageCode) == DictationCheck.Mode.JAPANESE) {
                LanguageAwareTokenizer.tokenize(line.originalText, languageCode)
            } else {
                emptyList()
            }
        results[key] = DictationCheck.check(line.originalText, answer(key), languageCode, tokens)
        reveal(key)
    }
}

/** One line of one video, for [DictationState]. */
internal data class DictationKey(
    val videoId: String?,
    val lineId: Long,
)

internal val LocalDictation = staticCompositionLocalOf<DictationState?> { null }

/** Dictation shows its box in the subtitle panel; live subtitles have no lines to hide, so it acts like Listen first there. */
internal fun DualSubUiState.dictationInPanel(): Boolean =
    studyPlayMode == StudyPlayMode.DICTATION && !liveFallback &&
        (originalVisibility != CaptionVisibility.NEVER || translatedVisibility != CaptionVisibility.NEVER)

/**
 * The line Dictation asks about: the playing one, or the last one played while the video is
 * between lines. [Remembered] holds that last line across recompositions.
 */
@Composable
internal fun rememberDictationLine(state: DualSubUiState): SubtitleSegment? {
    val last = remember(state.activeVideoId) { Remembered() }
    val playing = state.segments.getOrNull(state.currentIndex)
    if (playing != null) last.lineId = playing.id
    return playing ?: last.lineId?.let { id -> state.segments.firstOrNull { it.id == id } }
}

private class Remembered {
    var lineId: Long? = null
}

/**
 * The box under the transcript: type what you heard, then Check (or Show, to write on paper).
 * Replay plays the line again; after Check, Next plays the next line.
 */
@Composable
internal fun DictationBox(
    dictation: DictationState,
    videoId: String?,
    line: SubtitleSegment?,
    nextLine: SubtitleSegment?,
    languageCode: String?,
    onPlayLine: (SubtitleSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = line?.let { DictationKey(videoId, it.id) }
    val revealed = key != null && dictation.isRevealed(key)
    val answer = key?.let(dictation::answer).orEmpty()
    val canCheck = key != null && !revealed && answer.isNotBlank()
    val check = { if (canCheck && line != null) dictation.check(key, line, languageCode) }
    val next = { nextLine?.let(onPlayLine) }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .imePadding()
            .testTag("dictation_box"),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            key?.let(dictation::result)?.let { DictationResultText(it) }
            OutlinedTextField(
                value = answer,
                onValueChange = { text -> key?.let { dictation.type(it, text) } },
                enabled = key != null,
                modifier = Modifier.fillMaxWidth().testTag("dictation_answer"),
                placeholder = { Text(stringResource(R.string.dictation_type_what_you_heard)) },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = if (revealed) ImeAction.Next else ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { check() }, onNext = { next() }),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { line?.let(onPlayLine) },
                    enabled = line != null,
                    modifier = Modifier.testTag("dictation_replay"),
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.dictation_replay))
                }
                Spacer(Modifier.weight(1f))
                if (revealed) {
                    Button(onClick = { next() }, enabled = nextLine != null, modifier = Modifier.testTag("dictation_next")) {
                        Text(stringResource(R.string.dictation_next))
                    }
                } else {
                    TextButton(
                        onClick = { key?.let(dictation::reveal) },
                        enabled = key != null,
                        modifier = Modifier.testTag("dictation_show"),
                    ) { Text(stringResource(R.string.dictation_show)) }
                    Button(onClick = { check() }, enabled = canCheck, modifier = Modifier.testTag("dictation_check")) {
                        Text(stringResource(R.string.dictation_check))
                    }
                }
            }
        }
    }
}

/** The checked line: heard words in the accent colour, missed ones in the error colour and underlined. */
@Composable
private fun DictationResultText(result: DictationResult) {
    val right = MaterialTheme.colorScheme.primary
    val wrong = MaterialTheme.colorScheme.error
    Text(
        text =
            buildAnnotatedString {
                result.parts.forEachIndexed { index, part ->
                    if (index > 0 && result.spaced) append(' ')
                    withStyle(partStyle(part.right, right, wrong)) { append(part.text) }
                }
            },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().testTag("dictation_result"),
    )
    Text(
        text =
            if (result.perfect) {
                stringResource(R.string.dictation_perfect)
            } else {
                stringResource(R.string.dictation_score, result.rightCount, result.parts.size)
            },
        style = MaterialTheme.typography.labelMedium,
        color = if (result.perfect) right else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp).testTag("dictation_score"),
    )
}

private fun partStyle(
    right: Boolean,
    rightColor: Color,
    wrongColor: Color,
): SpanStyle =
    if (right) {
        SpanStyle(color = rightColor, fontWeight = FontWeight.SemiBold)
    } else {
        SpanStyle(color = wrongColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
    }
