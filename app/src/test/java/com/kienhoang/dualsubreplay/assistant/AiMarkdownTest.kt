package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AiMarkdownTest {
    private fun styled(
        formatted: AiFormattedText,
        style: AiSpanStyle,
    ) = formatted.spans.filter { it.style == style }.map { formatted.text.substring(it.start, it.end) }

    @Test
    fun boldAndCodeLoseTheirMarkers() {
        val formatted = formatAiReply("**に** marks the `target` of an action.")
        assertEquals("に marks the target of an action.", formatted.text)
        assertEquals(listOf("に"), styled(formatted, AiSpanStyle.BOLD))
        assertEquals(listOf("target"), styled(formatted, AiSpanStyle.CODE))
    }

    @Test
    fun headingsAreBoldAndBulletsAreDots() {
        val formatted = formatAiReply("## Meaning\n- to take (a photo)\n* hard, tough\n\nDone.")
        assertEquals("Meaning\n• to take (a photo)\n• hard, tough\n\nDone.", formatted.text)
        assertEquals(listOf("Meaning"), styled(formatted, AiSpanStyle.BOLD))
    }

    @Test
    fun codeFencesKeepTheirLinesAsCode() {
        val formatted = formatAiReply("Example:\n```\n写真を撮る\n```\nEnd")
        assertEquals("Example:\n写真を撮る\nEnd", formatted.text)
        assertEquals(listOf("写真を撮る"), styled(formatted, AiSpanStyle.CODE))
    }

    @Test
    fun unclosedMarkersStayAsWritten() {
        assertEquals("2 ** 3 and a ` tick", formatAiReply("2 ** 3 and a ` tick").text)
        assertEquals("****", formatAiReply("****").text)
    }
}
