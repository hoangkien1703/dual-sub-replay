package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap

@Composable
internal fun CompactSubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
    activeWordIndex: Int = -1,
    originalColor: Color = subtitleColor(DEFAULT_ORIGINAL_COLOR_KEY),
    translatedColor: Color = subtitleColor(DEFAULT_TRANSLATED_COLOR_KEY),
    highlightColor: Color = subtitleColor(DEFAULT_HIGHLIGHT_COLOR_KEY),
    wordLearningEnabled: Boolean = false,
    wordLearningTarget: String = "both",
    wordLearningActiveOnly: Boolean = true,
    tapToLearnEnabled: Boolean = true,
    resolvedSourceLanguage: String? = null,
    targetLanguage: String = "vi",
    isDownloadingTranslationModel: Boolean = false,
    onWordClick: (WordTap) -> Unit = {},
    replayEnabled: Boolean = true,
    showOriginal: Boolean = true,
    showTranslation: Boolean = true,
) {
    if (!showOriginal && !showTranslation) return
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (active) "Active subtitle" else "Subtitle" }
                .clickable(enabled = replayEnabled, onClick = onReplay),
        border =
            BorderStroke(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF183034),
            ),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = if (active) Color(0xFF0A2B30) else Color(0xFF081D20),
                contentColor = Color(0xFFF3FAFA),
            ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (replayEnabled) {
                Card(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    colors =
                        CardDefaults.cardColors(
                            containerColor = if (active) MaterialTheme.colorScheme.primary else Color(0xFF24383B),
                        ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Replay this paragraph",
                            modifier = Modifier.size(22.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (replayEnabled) Spacer(Modifier.size(9.dp))
            Column(Modifier.weight(1f)) {
                val isSentenceEligibleForPos = !wordLearningActiveOnly || active
                if (showOriginal) {
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
                if (showTranslation) {
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
                        isDownloadingTranslationModel,
                        onWordClick,
                        onReplay,
                    )
                }
            }
        }
    }
}

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
        )
    if (wordLearningEnabled && tapToLearnEnabled) {
        ClickableText(
            text = annotatedOriginal,
            style =
                TextStyle(
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = originalColor,
                ),
            onClick = { offset ->
                val token = findWordAtOffset(segment.originalText, offset, resolvedSourceLanguage)
                if (token != null) {
                    onWordClick(WordTap(token, segment, false))
                } else {
                    onReplay()
                }
            },
        )
    } else {
        Text(
            text = annotatedOriginal,
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
    isDownloadingTranslationModel: Boolean,
    onWordClick: (WordTap) -> Unit,
    onReplay: () -> Unit,
) {
    val shouldHighlightTrans =
        wordLearningEnabled && isSentenceEligibleForPos && (wordLearningTarget == "translation" || wordLearningTarget == "both")
    val translatedText = segment.translatedText
    val fallbackText =
        if (isDownloadingTranslationModel) {
            "Downloading translation model…"
        } else {
            "Translating…"
        }
    val originalTokens: List<AnalyzedToken> =
        remember(segment.originalText, resolvedSourceLanguage) {
            LanguageAwareTokenizer.tokenize(segment.originalText, resolvedSourceLanguage)
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
        ClickableText(
            text = annotatedTrans,
            style =
                TextStyle(
                    fontSize = (14 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp,
                    color = translatedColor,
                ),
            onClick = { offset ->
                val token =
                    findWordAtOffset(
                        text = translatedText,
                        charOffset = offset,
                        languageCode = targetLanguage,
                        alignedOriginalTokens = originalTokens,
                    )
                if (token != null) {
                    onWordClick(WordTap(token, segment, true))
                } else {
                    onReplay()
                }
            },
        )
    } else {
        Text(
            text = annotatedTrans,
            fontSize = (14 * fontScale).sp,
            lineHeight = (18 * fontScale).sp,
            color = translatedColor,
        )
    }
}
