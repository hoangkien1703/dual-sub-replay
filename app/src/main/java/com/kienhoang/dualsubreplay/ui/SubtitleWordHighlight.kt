package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord

internal data class SubtitleWordSpan(val wordIndex: Int, val start: Int, val end: Int)

/**
 * Maps caption word timings onto character ranges of [text]. Returns an empty
 * list when alignment fails so the highlight degrades to plain text instead of
 * coloring the wrong characters.
 */
internal fun subtitleWordSpans(text: String, words: List<SubtitleWord>): List<SubtitleWordSpan> {
    if (text.isBlank() || words.isEmpty()) return emptyList()
    val spans = ArrayList<SubtitleWordSpan>(words.size)
    var searchFrom = 0
    words.forEachIndexed { index, word ->
        val token = word.text.trim()
        if (token.isEmpty()) return@forEachIndexed
        val start = text.indexOf(token, searchFrom)
        if (start < 0) return emptyList()
        spans += SubtitleWordSpan(index, start, start + token.length)
        searchFrom = start + token.length
    }
    return spans
}

/** Renders the original line with the currently spoken word tinted. */
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
            SpanStyle(color = highlightColor, fontWeight = FontWeight.ExtraBold),
            span.start,
            span.end,
        )
    }
}
