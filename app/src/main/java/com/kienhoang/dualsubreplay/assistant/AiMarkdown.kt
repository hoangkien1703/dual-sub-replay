package com.kienhoang.dualsubreplay.assistant

internal enum class AiSpanStyle { BOLD, CODE }

internal data class AiSpan(
    val start: Int,
    val end: Int,
    val style: AiSpanStyle,
)

/** Reply text without Markdown markers, and where its bold and code parts are. */
internal data class AiFormattedText(
    val text: String,
    val spans: List<AiSpan>,
)

private val BULLET = Regex("^\\s*[-*•]\\s+")
private val HEADING = Regex("^\\s*#{1,6}\\s+")
private val FENCE = Regex("^\\s*```.*$")

/**
 * The small part of Markdown that chat models use most: `**bold**`, `` `code` ``, `#` headings
 * (shown bold), `-`/`*` bullets (shown as •) and ``` fences (dropped, their lines kept as code).
 * Everything else stays as written, so nothing a model sends can turn into a link or a web page.
 */
internal fun formatAiReply(markdown: String): AiFormattedText {
    val out = StringBuilder()
    val spans = mutableListOf<AiSpan>()
    var inFence = false
    markdown.replace("\r\n", "\n").trim().lines().forEachIndexed { index, rawLine ->
        if (FENCE.matches(rawLine)) {
            inFence = !inFence
            return@forEachIndexed
        }
        if (index > 0 && out.isNotEmpty()) out.append('\n')
        val lineStart = out.length
        when {
            inFence -> {
                out.append(rawLine)
                spans += AiSpan(lineStart, out.length, AiSpanStyle.CODE)
            }
            HEADING.containsMatchIn(rawLine) -> {
                appendInline(out, spans, rawLine.replace(HEADING, ""))
                spans += AiSpan(lineStart, out.length, AiSpanStyle.BOLD)
            }
            BULLET.containsMatchIn(rawLine) -> {
                out.append("• ")
                appendInline(out, spans, rawLine.replace(BULLET, ""))
            }
            else -> appendInline(out, spans, rawLine)
        }
    }
    return AiFormattedText(out.toString(), spans.filter { it.end > it.start })
}

/** Appends [line] without `**` and `` ` `` markers; an unclosed marker stays as plain text. */
private fun appendInline(
    out: StringBuilder,
    spans: MutableList<AiSpan>,
    line: String,
) {
    var index = 0
    while (index < line.length) {
        val bold = line.startsWith("**", index)
        val code = !bold && line[index] == '`'
        val marker = if (bold) "**" else "`"
        val close = if (bold || code) line.indexOf(marker, index + marker.length) else -1
        if (close > index + marker.length) {
            val start = out.length
            out.append(line, index + marker.length, close)
            spans += AiSpan(start, out.length, if (bold) AiSpanStyle.BOLD else AiSpanStyle.CODE)
            index = close + marker.length
        } else {
            out.append(line[index])
            index++
        }
    }
}
