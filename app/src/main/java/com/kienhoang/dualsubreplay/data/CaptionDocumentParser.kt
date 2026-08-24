package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/** Parses each caption format currently returned by YouTube timed-text URLs. */
internal object CaptionDocumentParser {
    fun parse(text: String): List<RawCaptionCue> {
        val trimmed = text.trimStart()
        if (trimmed.isBlank()) return emptyList()
        return if (trimmed.startsWith("{")) parseJson3(text) else parseXml(text)
    }

    private fun parseJson3(text: String): List<RawCaptionCue> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val segments = event.optJSONArray("segs") ?: return@mapNotNull null
            val content = buildString {
                for (segmentIndex in 0 until segments.length()) {
                    append(segments.optJSONObject(segmentIndex)?.optString("utf8").orEmpty())
                }
            }.normalizeCaptionText()
            val start = event.optLong("tStartMs", -1L)
            if (content.isBlank() || start < 0) return@mapNotNull null
            val duration = event.optLong("dDurationMs", 1_200L).coerceAtLeast(200L)
            val end = start + duration
            RawCaptionCue(start, end, content, json3Words(segments, start, end))
        }
    }

    /**
     * json3 splits captions into chunks with per-chunk [tOffsetMs] offsets that
     * enable the real-time spoken-word highlight. Newline-only chunks separate
     * lines and carry no spoken text.
     */
    private fun json3Words(segments: JSONArray, cueStartMs: Long, cueEndMs: Long): List<SubtitleWord> {
        data class Chunk(val text: String, val offsetMs: Long)

        val chunks = (0 until segments.length())
            .mapNotNull(segments::optJSONObject)
            .map { it.optString("utf8") to it.optLong("tOffsetMs", -1L) }
            .filter { (raw, _) -> raw.isNotBlank() && raw != "\n" }
            .map { (raw, offset) -> Chunk(raw.normalizeCaptionText(), offset) }
            .filter { it.text.isNotBlank() }
        if (chunks.none { it.offsetMs >= 0 }) return emptyList()

        var cursorMs = cueStartMs
        return chunks.mapIndexed { index, chunk ->
            val wordStart = if (chunk.offsetMs >= 0) cueStartMs + chunk.offsetMs else cursorMs
            val nextOffset = chunks.getOrNull(index + 1)?.offsetMs?.takeIf { it >= 0 }
            val wordEnd = when {
                nextOffset != null -> cueStartMs + nextOffset
                index == chunks.lastIndex -> cueEndMs
                else -> wordStart + DEFAULT_WORD_DURATION_MS
            }.coerceAtLeast(wordStart + MIN_WORD_DURATION_MS).coerceAtMost(cueEndMs)
            cursorMs = wordEnd
            SubtitleWord(text = chunk.text, startMs = wordStart, endMs = wordEnd)
        }
    }

    private fun parseXml(text: String): List<RawCaptionCue> {
        val cues = mutableListOf<RawCaptionCue>()
        LEGACY_TEXT_REGEX.findAll(text).forEach { match ->
            val attributes = match.groupValues[1]
            val startMs = secondsToMs(attribute(attributes, "start"))
            val durationMs = secondsToMs(attribute(attributes, "dur"), defaultSeconds = 1.2)
            val content = cleanXmlText(match.groupValues[2])
            if (content.isNotBlank() && startMs >= 0) {
                cues += RawCaptionCue(startMs, startMs + durationMs.coerceAtLeast(200L), content)
            }
        }

        SRV3_PARAGRAPH_REGEX.findAll(text).forEach { match ->
            val attributes = match.groupValues[1]
            val startMs = attribute(attributes, "t")?.toLongOrNull() ?: -1L
            val durationMs = attribute(attributes, "d")?.toLongOrNull() ?: 1_200L
            val content = cleanXmlText(match.groupValues[2])
            if (content.isNotBlank() && startMs >= 0) {
                val end = startMs + durationMs.coerceAtLeast(200L)
                cues += RawCaptionCue(startMs, end, content, srv3Words(match.groupValues[2], startMs, end))
            }
        }
        return cues.sortedBy(RawCaptionCue::startMs)
    }

    /**
     * srv3 auto captions mark each spoken chunk with an `<s t="…">` offset in
     * milliseconds relative to the paragraph start. Chunks without offsets
     * inherit the previous chunk's timing.
     */
    private fun srv3Words(paragraphInnerXml: String, cueStartMs: Long, cueEndMs: Long): List<SubtitleWord> {
        val matches = SRV3_WORD_REGEX.findAll(paragraphInnerXml).toList()
        if (matches.isEmpty()) return emptyList()
        var cursorMs = cueStartMs
        val words = mutableListOf<SubtitleWord>()
        matches.forEachIndexed { index, match ->
            val offset = attribute(match.groupValues[1], "t")?.toLongOrNull()
            val text = cleanXmlText(match.groupValues[2])
            if (text.isBlank()) return@forEachIndexed
            val wordStart = offset?.let(cueStartMs::plus) ?: cursorMs
            val nextOffset = matches.getOrNull(index + 1)?.groupValues?.get(1)
                ?.let { attribute(it, "t")?.toLongOrNull() }
            val wordEnd = when {
                nextOffset != null -> cueStartMs + nextOffset
                index == matches.lastIndex -> cueEndMs
                else -> wordStart + DEFAULT_WORD_DURATION_MS
            }.coerceAtLeast(wordStart + MIN_WORD_DURATION_MS).coerceAtMost(cueEndMs)
            cursorMs = wordEnd
            words += SubtitleWord(text = text, startMs = wordStart, endMs = wordEnd)
        }
        return words
    }

    private fun secondsToMs(value: String?, defaultSeconds: Double = -0.001): Long =
        ((value?.toDoubleOrNull() ?: defaultSeconds) * 1_000).toLong()

    private fun attribute(attributes: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*[\"']([^\"']*)[\"']""")
            .find(attributes)
            ?.groupValues
            ?.getOrNull(1)

    private fun cleanXmlText(value: String): String = value
        .replace(XML_TAG_REGEX, "")
        .decodeXmlEntities()
        .normalizeCaptionText()

    private fun String.decodeXmlEntities(): String {
        val named = replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return NUMERIC_ENTITY_REGEX.replace(named) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint?.let { Character.toChars(it).concatToString() } ?: match.value
        }
    }

    private fun String.normalizeCaptionText(): String = replace(Regex("\\s+"), " ").trim()

    private val LEGACY_TEXT_REGEX = Regex(
        """<text\b([^>]*)>(.*?)</text>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SRV3_PARAGRAPH_REGEX = Regex(
        """<p\b([^>]*)>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val SRV3_WORD_REGEX = Regex(
        """<s\b([^>]*)>(.*?)</s>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    internal const val DEFAULT_WORD_DURATION_MS = 400L
    internal const val MIN_WORD_DURATION_MS = 60L
    private val XML_TAG_REGEX = Regex("<[^>]+>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
}
