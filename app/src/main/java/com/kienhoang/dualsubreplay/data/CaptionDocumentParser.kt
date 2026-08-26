package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/** Parses each caption format currently returned by YouTube timed-text URLs. */
internal object CaptionDocumentParser {
    private data class TimedChunk(val text: String, val offsetMs: Long?)

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
     * json3 carries per-chunk [tOffsetMs] offsets. A chunk is not guaranteed to
     * be exactly one word, so split multi-word chunks while keeping YouTube's
     * real timing anchors. This avoids highlighting a whole phrase at once on
     * noisy/auto-generated transcripts.
     */
    private fun json3Words(segments: JSONArray, cueStartMs: Long, cueEndMs: Long): List<SubtitleWord> {
        val chunks = (0 until segments.length())
            .mapNotNull(segments::optJSONObject)
            .map { segment ->
                TimedChunk(
                    text = segment.optString("utf8").normalizeCaptionText(),
                    offsetMs = segment.optLong("tOffsetMs", -1L).takeIf { it >= 0L },
                )
            }
            .filter { chunk -> chunk.text.isNotBlank() && chunk.text != "\n" }
        if (chunks.none { it.offsetMs != null }) return emptyList()
        return expandTimedChunks(chunks, cueStartMs, cueEndMs)
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
     * srv3 auto captions mark spoken chunks with an `<s t="…">` offset in
     * milliseconds relative to the paragraph start. Like json3, one `<s>` can
     * contain several words, so preserve the anchor and estimate only inside
     * that anchored chunk.
     */
    private fun srv3Words(paragraphInnerXml: String, cueStartMs: Long, cueEndMs: Long): List<SubtitleWord> {
        val chunks = SRV3_WORD_REGEX.findAll(paragraphInnerXml)
            .map { match ->
                TimedChunk(
                    text = cleanXmlText(match.groupValues[2]),
                    offsetMs = attribute(match.groupValues[1], "t")?.toLongOrNull()?.takeIf { it >= 0L },
                )
            }
            .filter { chunk -> chunk.text.isNotBlank() }
            .toList()
        if (chunks.isEmpty()) return emptyList()
        if (chunks.none { it.offsetMs != null }) {
            return estimateWordTimings(cleanXmlText(paragraphInnerXml), cueStartMs, cueEndMs)
        }
        return expandTimedChunks(chunks, cueStartMs, cueEndMs)
    }

    /**
     * Expands timed caption chunks into individual words. YouTube's offset is
     * kept as the chunk anchor; when a chunk contains multiple words, only the
     * time inside that chunk is estimated. The next real offset remains the
     * boundary, so accurate ASR anchors are never replaced by whole-line timing.
     */
    private fun expandTimedChunks(
        chunks: List<TimedChunk>,
        cueStartMs: Long,
        cueEndMs: Long,
    ): List<SubtitleWord> {
        if (chunks.isEmpty() || cueEndMs <= cueStartMs) return emptyList()
        val words = mutableListOf<SubtitleWord>()
        var cursorMs = cueStartMs

        chunks.forEachIndexed { index, chunk ->
            val anchoredStart = chunk.offsetMs?.let(cueStartMs::plus)
            val chunkStart = maxOf(cursorMs, anchoredStart ?: cursorMs)
                .coerceIn(cueStartMs, cueEndMs)
            val nextAnchoredStart = chunks
                .asSequence()
                .drop(index + 1)
                .mapNotNull { next -> next.offsetMs?.let(cueStartMs::plus) }
                .firstOrNull()
            val desiredEnd = when {
                nextAnchoredStart != null -> nextAnchoredStart
                index == chunks.lastIndex -> cueEndMs
                else -> chunkStart + DEFAULT_WORD_DURATION_MS
            }
            val chunkEnd = desiredEnd
                .coerceAtLeast(chunkStart)
                .coerceAtMost(cueEndMs)
            if (chunkEnd <= chunkStart) {
                cursorMs = chunkStart
                return@forEachIndexed
            }

            val expanded = estimateWordTimings(chunk.text, chunkStart, chunkEnd)
            if (expanded.isNotEmpty()) {
                words += expanded
                cursorMs = expanded.last().endMs
            } else {
                cursorMs = chunkEnd
            }
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
