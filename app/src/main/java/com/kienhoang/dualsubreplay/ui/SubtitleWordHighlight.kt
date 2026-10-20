package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.ChineseMorphology
import com.kienhoang.dualsubreplay.data.EnglishMorphology
import com.kienhoang.dualsubreplay.data.JapaneseMorphology
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord

internal data class SubtitleWordSpan(
    val wordIndex: Int,
    val start: Int,
    val end: Int,
)

/**
 * Maps caption word timings onto character ranges of [text]. Broken auto-caption
 * chunks are skipped individually so one malformed timing token does not disable
 * karaoke highlighting for the whole subtitle line.
 */
internal fun subtitleWordSpans(
    text: String,
    words: List<SubtitleWord>,
): List<SubtitleWordSpan> {
    if (text.isBlank() || words.isEmpty()) return emptyList()
    val spans = ArrayList<SubtitleWordSpan>(words.size)
    var searchFrom = 0
    words.forEachIndexed { index, word ->
        val token = word.text.replace(Regex("\\s+"), " ").trim()
        if (token.isEmpty()) return@forEachIndexed
        val exactStart = text.indexOf(token, searchFrom)
        val start =
            if (exactStart >= 0) {
                exactStart
            } else {
                text.indexOf(token, searchFrom, ignoreCase = true)
            }
        if (start < 0) return@forEachIndexed
        spans += SubtitleWordSpan(index, start, start + token.length)
        searchFrom = start + token.length
    }
    return spans
}

/**
 * Renders subtitle text with optional Word Learning POS colors and real-time
 * spoken-word karaoke highlighting.
 *
 * When [wordLearningEnabled] is true, each word is tinted by its Part of Speech
 * (Issue #42 V1), and the currently spoken word is given a distinct bold underline
 * so grammar analysis and playback progress coexist cleanly without color conflict.
 */
internal fun annotatedSubtitleText(
    text: String,
    words: List<SubtitleWord>,
    activeWordIndex: Int,
    baseColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean = false,
    languageCode: String? = null,
    alignedOriginalTokens: List<AnalyzedToken>? = null,
    preparedTokens: List<AnalyzedToken>? = null,
    preparedSpans: List<SubtitleWordSpan>? = null,
    newWordRanges: List<IntRange> = emptyList(),
    newWordColor: Color = Color.Unspecified,
): AnnotatedString {
    if (text.isBlank()) return AnnotatedString("")
    CaptionTimingDiagnostics.record("presentation") { "presentation word=$activeWordIndex" }

    val activeSpan =
        if (activeWordIndex >= 0 && words.isNotEmpty()) {
            (preparedSpans ?: subtitleWordSpans(text, words)).firstOrNull { it.wordIndex == activeWordIndex }
        } else {
            null
        }

    val tokens =
        preparedTokens ?: if (wordLearningEnabled) {
            if (alignedOriginalTokens != null) {
                LanguageAwareTokenizer.alignAndTokenizeTranslation(
                    text,
                    alignedOriginalTokens,
                    languageCode,
                )
            } else {
                LanguageAwareTokenizer.tokenize(text, languageCode)
            }
        } else {
            emptyList()
        }

    // Dark text means a light card, where the part-of-speech colors need their darker shades.
    val onLight = baseColor.luminance() < DARK_TEXT_LUMINANCE
    return buildAnnotatedString {
        append(text)

        // Apply POS colors when Word Learning Mode is active
        if (wordLearningEnabled && tokens.isNotEmpty()) {
            tokens.forEach { token ->
                if (token.startIndex >= 0 && token.endIndex <= text.length && token.startIndex < token.endIndex) {
                    if (token.partOfSpeech != PartOfSpeech.OTHER) {
                        addStyle(
                            SpanStyle(color = partOfSpeechColor(token.partOfSpeech, onLight)),
                            token.startIndex,
                            token.endIndex,
                        )
                    }
                    // Tag token range for tap-to-learn click detection
                    addStringAnnotation(
                        tag = "WORD",
                        annotation = token.text,
                        start = token.startIndex,
                        end = token.endIndex,
                    )
                }
            }
        }

        // Study tools: words that are neither known nor saved get a light background.
        newWordRanges.forEach { range ->
            if (range.first >= 0 && range.last < text.length && range.first <= range.last) {
                addStyle(SpanStyle(background = newWordColor), range.first, range.last + 1)
            }
        }

        // Active spoken-word karaoke highlight
        if (activeSpan != null) {
            if (wordLearningEnabled) {
                // Keep POS color, add clean underline only (no background, no bolding, zero layout shift)
                addStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                    ),
                    activeSpan.start,
                    activeSpan.end,
                )
            } else {
                // Standard mode: highlight color tint and underline
                addStyle(
                    SpanStyle(
                        color = highlightColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                    activeSpan.start,
                    activeSpan.end,
                )
            }
        }
    }
}

/**
 * Changes once the Japanese, English or Chinese analyzer finishes loading in the background. Use
 * it as a `remember` key wherever subtitle text is tokenized, so lines shown before it loaded are
 * split again. Each revision only grows, so their sum changes whenever one does.
 */
@Composable
internal fun tokenizerRevision(): Int =
    JapaneseMorphology.revision.collectAsState().value +
        EnglishMorphology.revision.collectAsState().value +
        ChineseMorphology.revision.collectAsState().value

/** Expensive language analysis is independent of the changing spoken-word index. */
@Composable
internal fun rememberAnnotatedSubtitleText(
    text: String,
    words: List<SubtitleWord>,
    activeWordIndex: Int,
    baseColor: Color,
    highlightColor: Color,
    wordLearningEnabled: Boolean = false,
    languageCode: String? = null,
    alignedOriginalTokens: List<AnalyzedToken>? = null,
    markNewWords: Boolean = false,
): AnnotatedString {
    val spans = remember(text, words) { subtitleWordSpans(text, words) }
    val study = LocalStudyTools.current?.takeIf { markNewWords && it.marksNewWords(languageCode) }
    val tokens =
        remember(text, wordLearningEnabled, languageCode, alignedOriginalTokens, tokenizerRevision()) {
            when {
                !wordLearningEnabled -> emptyList()
                alignedOriginalTokens != null ->
                    LanguageAwareTokenizer.alignAndTokenizeTranslation(
                        text,
                        alignedOriginalTokens,
                        languageCode,
                    )
                else -> LanguageAwareTokenizer.tokenize(text, languageCode)
            }
        }
    val newWordRanges =
        remember(text, tokens, languageCode, study?.known, study?.learning) {
            study?.let { tools ->
                tokens
                    .ifEmpty { LanguageAwareTokenizer.tokenize(text, languageCode) }
                    .filter(tools::isNew)
                    .map { it.startIndex until it.endIndex }
            }.orEmpty()
        }
    return remember(text, spans, tokens, activeWordIndex, baseColor, highlightColor, wordLearningEnabled, newWordRanges) {
        annotatedSubtitleText(
            text,
            words,
            activeWordIndex,
            baseColor,
            highlightColor,
            wordLearningEnabled,
            languageCode,
            alignedOriginalTokens,
            tokens,
            spans,
            newWordRanges,
            newWordTint(highlightColor),
        )
    }
}

/** Renders the original line with the currently spoken word tinted and underlined. */
internal fun annotatedSpokenText(
    segment: SubtitleSegment,
    activeWordIndex: Int,
    baseColor: Color,
    highlightColor: Color,
): AnnotatedString =
    annotatedSubtitleText(
        text = segment.originalText,
        words = segment.words,
        activeWordIndex = activeWordIndex,
        baseColor = baseColor,
        highlightColor = highlightColor,
        wordLearningEnabled = false,
    )

/** Finds the analyzed word at [charOffset] in [text], for Tap-to-learn inspection. */
internal fun findWordAtOffset(
    text: String,
    charOffset: Int,
    languageCode: String? = null,
    alignedOriginalTokens: List<AnalyzedToken>? = null,
): AnalyzedToken? {
    if (text.isBlank() || charOffset < 0 || charOffset >= text.length) return null
    return subtitleWordTokens(text, languageCode, alignedOriginalTokens)
        .firstOrNull { charOffset >= it.startIndex && charOffset < it.endIndex }
}
