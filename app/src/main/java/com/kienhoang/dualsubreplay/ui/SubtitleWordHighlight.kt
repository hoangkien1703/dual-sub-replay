package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
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

/** Renders the original line with the currently spoken word tinted and underlined. */
internal fun annotatedSpokenText(
    segment: SubtitleSegment,
    activeWordIndex: Int,
    baseColor: Color,
    highlightColor: Color,
): AnnotatedString {
    val text = segment.originalText
    if (activeWordIndex < 0) return AnnotatedString(text)
    val span = subtitleWordSpans(text, segment.words)
        .firstOrNull { it.wordIndex == activeWordIndex }
        ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(
            SpanStyle(
                color = highlightColor,
                textDecoration = TextDecoration.Underline,
            ),
            span.start,
            span.end,
        )
    }
}
