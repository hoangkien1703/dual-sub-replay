package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord

internal data class SubtitleWordSpan(val wordIndex: Int, val start: Int, val end: Int)

/**
 * Maps caption word timings onto character ranges of [text]. Broken auto-caption
 * chunks are skipped individually so one malformed timing token does not disable
 * karaoke highlighting for the whole subtitle line.
 */
internal fun subtitleWordSpans(text: String, words: List<SubtitleWord>): List<SubtitleWordSpan> {
    if (text.isBlank() || words.isEmpty()) return emptyList()
    val spans = ArrayList<SubtitleWordSpan>(words.size)
    var searchFrom = 0
    words.forEachIndexed { index, word ->
        val token = word.text.replace(Regex("\\s+"), " ").trim()
        if (token.isEmpty()) return@forEachIndexed
        val exactStart = text.indexOf(token, searchFrom)
        val start = if (exactStart >= 0) {
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
): AnnotatedString {
    if (text.isBlank()) return AnnotatedString("")

    val activeSpan = if (activeWordIndex >= 0 && words.isNotEmpty()) {
        subtitleWordSpans(text, words).firstOrNull { it.wordIndex == activeWordIndex }
    } else null

    val tokens = if (wordLearningEnabled) {
        LanguageAwareTokenizer.tokenize(text, languageCode)
    } else emptyList()

    return buildAnnotatedString {
        append(text)

        // Apply POS colors when Word Learning Mode is active
        if (wordLearningEnabled && tokens.isNotEmpty()) {
            tokens.forEach { token ->
                if (token.startIndex >= 0 && token.endIndex <= text.length && token.startIndex < token.endIndex) {
                    if (token.partOfSpeech != PartOfSpeech.OTHER) {
                        addStyle(
                            SpanStyle(color = Color(token.partOfSpeech.colorHex)),
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

        // Active spoken-word karaoke highlight
        if (activeSpan != null) {
            if (wordLearningEnabled) {
                // Keep POS color, add underline and subtle background highlight (no bolding, zero layout shift)
                addStyle(
                    SpanStyle(
                        background = highlightColor.copy(alpha = 0.25f),
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

/** Renders the original line with the currently spoken word tinted and underlined. */
internal fun annotatedSpokenText(
    segment: SubtitleSegment,
    activeWordIndex: Int,
    baseColor: Color,
    highlightColor: Color,
): AnnotatedString = annotatedSubtitleText(
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
): AnalyzedToken? {
    if (text.isBlank() || charOffset < 0 || charOffset >= text.length) return null
    val tokens = LanguageAwareTokenizer.tokenize(text, languageCode)
    return tokens.firstOrNull { charOffset >= it.startIndex && charOffset < it.endIndex }
}
