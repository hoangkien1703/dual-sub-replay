package com.kienhoang.dualsubreplay.data

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
            RawCaptionCue(start, start + duration, content)
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
                cues += RawCaptionCue(startMs, startMs + durationMs.coerceAtLeast(200L), content)
            }
        }
        return cues.sortedBy(RawCaptionCue::startMs)
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
    private val XML_TAG_REGEX = Regex("<[^>]+>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
}
