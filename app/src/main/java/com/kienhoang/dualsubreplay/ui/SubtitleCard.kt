package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.ui.theme.brandBackground
import com.kienhoang.dualsubreplay.ui.theme.brandColors

@Composable
internal fun CompactSubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
    activeWordIndex: Int = -1,
    originalColor: Color = subtitleColor(DEFAULT_ORIGINAL_COLOR_KEY, appThemeIsLight()),
    translatedColor: Color = subtitleColor(DEFAULT_TRANSLATED_COLOR_KEY, appThemeIsLight()),
    highlightColor: Color = subtitleColor(DEFAULT_HIGHLIGHT_COLOR_KEY, appThemeIsLight()),
    wordLearningEnabled: Boolean = false,
    wordLearningTarget: String = DEFAULT_WORD_LEARNING_TARGET,
    wordLearningActiveOnly: Boolean = true,
    tapToLearnEnabled: Boolean = true,
    resolvedSourceLanguage: String? = null,
    targetLanguage: String = "vi",
    isDownloadingTranslationModel: Boolean = false,
    translationUnavailable: Boolean = false,
    onWordClick: (WordTap) -> Unit = {},
    replayEnabled: Boolean = true,
    showOriginal: Boolean = true,
    showTranslation: Boolean = true,
    translationPosition: TranslationPosition = TranslationPosition.BELOW,
    /** Goes before the row's click, for a focus requester (TV layout). */
    focusModifier: Modifier = Modifier,
    /** Settings → Languages → Third line: plain text under the other lines; null shows nothing. */
    thirdText: String? = null,
    /** The third line's language; with it, the third line's words can be tapped like the other lines'. */
    thirdLanguage: String? = null,
    /** The loop button at the row's end (Study tools → Loop button on the playing line), or null for none. */
    loop: LineLoopButton? = null,
    /** Dictation: the line is hidden until revealed, and this (one dot per word) shows instead. */
    hiddenHint: String? = null,
) {
    if (hiddenHint == null && !showOriginal && !showTranslation) return
    val rowState = stringResource(if (active) R.string.player_active_subtitle else R.string.player_subtitle)
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = rowState }
                .then(focusModifier)
                .tvFocusRing()
                .clickable(enabled = replayEnabled, onClick = onReplay),
        border = if (active) activeLineBorder() else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (replayEnabled) ReplayCircle(active)
            if (replayEnabled) Spacer(Modifier.size(9.dp))
            if (hiddenHint != null) {
                HiddenLineText(hiddenHint, fontScale, Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    val isSentenceEligibleForPos = !wordLearningActiveOnly || active
                    // true marks the translation line.
                    val lines = subtitleLineOrder(false.takeIf { showOriginal }, true.takeIf { showTranslation }, translationPosition)
                    lines.forEach { translated ->
                        if (translated) {
                            TranslatedCardText(
                                segment,
                                active,
                                activeWordIndex,
                                fontScale,
                                originalColor,
                                translatedColor,
                                highlightColor,
                                wordLearningEnabled,
                                wordLearningTarget,
                                isSentenceEligibleForPos,
                                tapToLearnEnabled,
                                resolvedSourceLanguage,
                                targetLanguage,
                                stringResource(pendingTranslationTextRes(isDownloadingTranslationModel, translationUnavailable)),
                                onWordClick,
                                onReplay,
                            )
                        } else {
                            OriginalCardText(
                                segment,
                                active,
                                activeWordIndex,
                                fontScale,
                                originalColor,
                                translatedColor,
                                highlightColor,
                                wordLearningEnabled,
                                wordLearningTarget,
                                isSentenceEligibleForPos,
                                tapToLearnEnabled,
                                resolvedSourceLanguage,
                                targetLanguage,
                                isDownloadingTranslationModel,
                                onWordClick,
                                onReplay,
                            )
                        }
                    }
                    thirdText?.let { third ->
                        if (wordLearningEnabled && tapToLearnEnabled && thirdLanguage != null) {
                            TappableThirdLineText(
                                third,
                                thirdLanguage,
                                segment,
                                fontScale,
                                translatedColor,
                                highlightColor,
                                onWordClick,
                                onReplay,
                            )
                        } else {
                            ThirdLineText(third, fontScale, translatedColor, Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            loop?.let { LineLoopIconButton(it) }
        }
    }
}

/**
 * The playing row's outline in the logo's gradient (owner pick 2026-11-21): the accent, with sapphire
 * fading in from the bottom-left corner; a plain accent outline when the person picked another accent.
 */
@Composable
private fun activeLineBorder(): BorderStroke =
    BorderStroke(
        2.dp,
        brandColors().brush(reachFraction = ACTIVE_LINE_SAPPHIRE_WIDTH_SHARE, maxReachInHeights = ACTIVE_LINE_SAPPHIRE_MAX_HEIGHTS),
    )

/** How far along the playing row's outline the sapphire reaches, as a share of the row's width. */
private const val ACTIVE_LINE_SAPPHIRE_WIDTH_SHARE = 0.45f
private const val ACTIVE_LINE_SAPPHIRE_MAX_HEIGHTS = 2f

/** The round play icon at a row's start: filled with the logo's gradient on the playing row. */
@Composable
private fun ReplayCircle(active: Boolean) {
    val brand = brandColors()
    Card(
        modifier = Modifier.size(34.dp).then(if (active) Modifier.brandBackground(brand, CircleShape) else Modifier),
        shape = CircleShape,
        colors =
            CardDefaults.cardColors(
                // The playing row's circle shows the logo's gradient drawn behind it.
                containerColor = if (active) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.player_replay_paragraph),
                modifier = Modifier.size(22.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A line Dictation hides: dots in place of its words, read out as "Hidden line". */
@Composable
private fun HiddenLineText(
    dots: String,
    fontScale: Float,
    modifier: Modifier,
) {
    val description = stringResource(R.string.dictation_hidden_line)
    Text(
        text = dots,
        modifier = modifier.semantics { contentDescription = description }.testTag("dictation_hidden_line"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = (16 * fontScale).sp,
        letterSpacing = 1.sp,
    )
}

/** The loop button of one transcript row: whether its line loops, and what a tap does. */
internal data class LineLoopButton(
    val looping: Boolean,
    val onToggle: () -> Unit,
)

/** A small repeat-one icon; on a filled accent circle while the line loops. */
@Composable
private fun LineLoopIconButton(button: LineLoopButton) {
    val description = stringResource(if (button.looping) R.string.player_stop_loop_line else R.string.player_loop_line)
    IconButton(
        onClick = button.onToggle,
        modifier = Modifier.padding(start = 4.dp).size(40.dp).testTag("line_loop_button"),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(
                        if (button.looping) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.RepeatOne,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (button.looping) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The third line with tap-to-learn (25kiuo, Discussion #81, 2026-11-21): its words select like the
 * translation's, in the third line's own language. The tapped word's segment carries the third line
 * as its translation, so the word card's sentence is the third line, under the original.
 */
@Composable
private fun TappableThirdLineText(
    text: String,
    language: String,
    segment: SubtitleSegment,
    fontScale: Float,
    color: Color,
    highlightColor: Color,
    onWordClick: (WordTap) -> Unit,
    onReplay: () -> Unit,
) {
    SelectableSubtitleText(
        text = text,
        annotated = AnnotatedString(text),
        style =
            TextStyle(
                fontSize = (13 * fontScale).sp,
                lineHeight = (17 * fontScale).sp,
                color = color.copy(alpha = color.alpha * THIRD_LINE_ALPHA),
            ),
        languageCode = language,
        segment = segment.copy(translatedText = text),
        translated = true,
        highlightColor = highlightColor,
        onWordClick = onWordClick,
        onBlankTap = onReplay,
        modifier = Modifier.padding(top = 2.dp).testTag("third_line"),
        lineLanguage = language,
    )
}

/** The third line without tap-to-learn: plain text in the translation's color, a little smaller; right to left by its own script. */
@Composable
internal fun ThirdLineText(
    text: String,
    fontScale: Float,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val annotated = AnnotatedString(text)
    Text(
        text = annotated,
        modifier =
            (
                if (textAlign ==
                    TextAlign.Unspecified
                ) {
                    modifier.lineSide(annotated, LocalLayoutDirection.current)
                } else {
                    modifier
                }
            ).testTag("third_line"),
        style = LocalTextStyle.current.inLineDirection(),
        textAlign = textAlign,
        fontSize = (13 * fontScale).sp,
        lineHeight = (17 * fontScale).sp,
        color = color.copy(alpha = color.alpha * THIRD_LINE_ALPHA),
    )
}

/** The third line is a touch lighter than the translation, so the eye still reads the first two first. */
private const val THIRD_LINE_ALPHA = 0.85f

@Composable
private fun OriginalCardText(
    segment: SubtitleSegment,
    active: Boolean,
    activeWordIndex: Int,
    fontScale: Float,
    originalColor: Color,
    translatedColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean,
    wordLearningTarget: String,
    isSentenceEligibleForPos: Boolean,
    tapToLearnEnabled: Boolean,
    resolvedSourceLanguage: String?,
    targetLanguage: String,
    isDownloadingTranslationModel: Boolean,
    onWordClick: (WordTap) -> Unit,
    onReplay: () -> Unit,
) {
    val shouldHighlightPos =
        wordLearningEnabled && isSentenceEligibleForPos && (wordLearningTarget == "original" || wordLearningTarget == "both")
    val annotatedOriginal =
        rememberAnnotatedSubtitleText(
            text = segment.originalText,
            words = segment.words,
            activeWordIndex = if (active) activeWordIndex else -1,
            baseColor = originalColor,
            highlightColor = highlightColor,
            wordLearningEnabled = shouldHighlightPos,
            languageCode = resolvedSourceLanguage,
            markNewWords = true,
        )
    if (wordLearningEnabled && tapToLearnEnabled) {
        SelectableSubtitleText(
            text = segment.originalText,
            annotated = annotatedOriginal,
            style =
                TextStyle(
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = originalColor,
                ),
            languageCode = resolvedSourceLanguage,
            segment = segment,
            translated = false,
            highlightColor = highlightColor,
            onWordClick = onWordClick,
            onBlankTap = onReplay,
        )
    } else {
        Text(
            text = annotatedOriginal,
            modifier = Modifier.lineSide(annotatedOriginal, LocalLayoutDirection.current),
            style = LocalTextStyle.current.inLineDirection(),
            fontSize = (17 * fontScale).sp,
            lineHeight = (22 * fontScale).sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = originalColor,
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun TranslatedCardText(
    segment: SubtitleSegment,
    active: Boolean,
    activeWordIndex: Int,
    fontScale: Float,
    originalColor: Color,
    translatedColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean,
    wordLearningTarget: String,
    isSentenceEligibleForPos: Boolean,
    tapToLearnEnabled: Boolean,
    resolvedSourceLanguage: String?,
    targetLanguage: String,
    fallbackText: String,
    onWordClick: (WordTap) -> Unit,
    onReplay: () -> Unit,
) {
    val shouldHighlightTrans =
        wordLearningEnabled && isSentenceEligibleForPos && (wordLearningTarget == "translation" || wordLearningTarget == "both")
    val translatedText = segment.translatedText
    // Tokens only feed word colors and tap-to-learn, so skip tokenizing when neither is on.
    val needsOriginalTokens = shouldHighlightTrans || (wordLearningEnabled && tapToLearnEnabled)
    val originalTokens: List<AnalyzedToken> =
        remember(segment.originalText, resolvedSourceLanguage, needsOriginalTokens, tokenizerRevision()) {
            if (needsOriginalTokens) {
                LanguageAwareTokenizer.tokenize(segment.originalText, resolvedSourceLanguage)
            } else {
                emptyList()
            }
        }
    val annotatedTrans =
        if (shouldHighlightTrans && translatedText != null) {
            rememberAnnotatedSubtitleText(
                text = translatedText,
                words = emptyList(),
                activeWordIndex = -1,
                baseColor = translatedColor,
                highlightColor = highlightColor,
                wordLearningEnabled = true,
                languageCode = targetLanguage,
                alignedOriginalTokens = originalTokens,
            )
        } else {
            AnnotatedString(translatedText ?: fallbackText)
        }
    if (wordLearningEnabled && tapToLearnEnabled && translatedText != null) {
        SelectableSubtitleText(
            text = translatedText,
            annotated = annotatedTrans,
            style =
                TextStyle(
                    fontSize = (14 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp,
                    color = translatedColor,
                ),
            languageCode = targetLanguage,
            segment = segment,
            translated = true,
            highlightColor = highlightColor,
            onWordClick = onWordClick,
            onBlankTap = onReplay,
            alignedOriginalTokens = originalTokens,
        )
    } else {
        Text(
            text = annotatedTrans,
            modifier = Modifier.lineSide(annotatedTrans, LocalLayoutDirection.current),
            style = LocalTextStyle.current.inLineDirection(),
            fontSize = (14 * fontScale).sp,
            lineHeight = (18 * fontScale).sp,
            color = translatedColor,
        )
    }
}

/** What a row shows under its original line until its translation arrives. */
@StringRes
internal fun pendingTranslationTextRes(
    downloadingModel: Boolean,
    translationUnavailable: Boolean,
): Int =
    when {
        translationUnavailable -> R.string.player_translation_unavailable
        downloadingModel -> R.string.player_downloading_translation_model
        else -> R.string.player_translating
    }
