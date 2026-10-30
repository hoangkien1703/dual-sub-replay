package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap

/** The words of a line that get a button: punctuation and symbols are left out. */
internal fun tvLineWords(tokens: List<AnalyzedToken>): List<AnalyzedToken> =
    tokens.filter { token -> token.text.any(Char::isLetterOrDigit) }

/**
 * A subtitle line picked with the remote (TV layout): Replay line, then each word of the line as a
 * big button, in place of selecting words by touch. A word opens the usual word card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvLineWordsDialog(
    segment: SubtitleSegment,
    languageCode: String?,
    onReplay: () -> Unit,
    onWord: (WordTap) -> Unit,
    onDismiss: () -> Unit,
) {
    val words = remember(segment.originalText, languageCode) { tvLineWords(subtitleWordTokens(segment.originalText, languageCode)) }
    val replayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { replayFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 760.dp).testTag("tv_line_words"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(segment.originalText, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                segment.translatedText?.let { translation ->
                    Text(translation, fontSize = 20.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            onDismiss()
                            onReplay()
                        },
                        modifier = Modifier.focusRequester(replayFocus).tvFocusRing(RoundedCornerShape(24.dp)).testTag("tv_replay_line"),
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.tv_replay_line))
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.tvFocusRing(RoundedCornerShape(24.dp))) {
                        Text(stringResource(R.string.tv_close))
                    }
                }
                if (words.isNotEmpty()) {
                    Text(
                        stringResource(R.string.tv_pick_a_word),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        words.forEachIndexed { index, word ->
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onWord(WordTap(word, segment, translated = false))
                                },
                                modifier = Modifier.tvFocusRing(RoundedCornerShape(24.dp)).testTag("tv_word_$index"),
                            ) {
                                Text(word.text, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
