package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/** The longest a transcript panel line lasts when the panel gives only its start time. */
private const val PANEL_LINE_MAX_MS = 10_000L

/**
 * Reads the lines of YouTube's own "Show transcript" panel, the last resort when the page player
 * never downloads the caption track. [text] is either the panel's `get_transcript` reply, kept as
 * the page received it (`transcriptSegmentRenderer` lines with `startMs`, `endMs` and a `snippet`,
 * or older `transcriptCueRenderer` cues), or the lines the page read from the shown panel
 * (`{"panelLines":[{"startMs":..,"text":".."}]}`). Lines carry no word timing. Returns the lines in
 * time order, or an empty list when [text] holds none.
 */
internal fun parseTranscriptPanel(text: String): List<RawCaptionCue> {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    val lines = mutableListOf<PanelLine>()
    root.optJSONArray("panelLines")?.let { shown ->
        for (index in 0 until shown.length()) {
            val line = shown.optJSONObject(index) ?: continue
            panelLine(line.optLong("startMs", -1), null, line.optString("text"))?.let(lines::add)
        }
    }
    if (lines.isEmpty()) collectPanelSegments(root, lines, depth = 0)
    val sorted = lines.distinctBy { it.startMs to it.text }.sortedBy { it.startMs }
    return sorted.mapIndexed { index, line ->
        val latest = line.startMs + PANEL_LINE_MAX_MS
        val end = line.endMs?.takeIf { it > line.startMs } ?: minOf(sorted.getOrNull(index + 1)?.startMs ?: latest, latest)
        RawCaptionCue(line.startMs, maxOf(end, line.startMs + 1), line.text)
    }
}

private class PanelLine(
    val startMs: Long,
    val endMs: Long?,
    val text: String,
)

private fun panelLine(
    startMs: Long,
    endMs: Long?,
    text: String,
): PanelLine? {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (startMs < 0 || clean.isEmpty()) return null
    return PanelLine(startMs, endMs, clean)
}

/** Walks the reply for transcript lines; YouTube nests them differently between versions. */
private fun collectPanelSegments(
    node: Any?,
    lines: MutableList<PanelLine>,
    depth: Int,
) {
    if (depth > 40) return
    when (node) {
        is JSONObject -> {
            node.optJSONObject("transcriptSegmentRenderer")?.let { segment ->
                panelLine(
                    segment.optString("startMs").toLongOrNull() ?: -1,
                    segment.optString("endMs").toLongOrNull(),
                    panelText(segment.opt("snippet")),
                )?.let(lines::add)
                return
            }
            node.optJSONObject("transcriptCueRenderer")?.let { cue ->
                val start = cue.optString("startOffsetMs").toLongOrNull() ?: -1
                val duration = cue.optString("durationMs").toLongOrNull()
                panelLine(start, duration?.let { start + it }, panelText(cue.opt("cue")))?.let(lines::add)
                return
            }
            node.keys().forEach { key -> collectPanelSegments(node.opt(key), lines, depth + 1) }
        }
        is JSONArray -> for (index in 0 until node.length()) collectPanelSegments(node.opt(index), lines, depth + 1)
    }
}

private fun panelText(value: Any?): String {
    val text = value as? JSONObject ?: return ""
    text.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
    val runs = text.optJSONArray("runs") ?: return ""
    return (0 until runs.length()).mapNotNull(runs::optJSONObject).joinToString(separator = "") { it.optString("text") }
}
