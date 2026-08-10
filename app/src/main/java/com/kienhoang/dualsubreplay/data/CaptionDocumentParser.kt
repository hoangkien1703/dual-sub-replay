package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

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
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(text))
        }
        val cues = mutableListOf<RawCaptionCue>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && (parser.name == "text" || parser.name == "p")) {
                val isLegacyText = parser.name == "text"
                val startMs = if (isLegacyText) {
                    secondsToMs(parser.getAttributeValue(null, "start"))
                } else {
                    parser.getAttributeValue(null, "t")?.toLongOrNull() ?: -1L
                }
                val durationMs = if (isLegacyText) {
                    secondsToMs(parser.getAttributeValue(null, "dur"), defaultSeconds = 1.2)
                } else {
                    parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 1_200L
                }
                val content = readElementText(parser).normalizeCaptionText()
                if (content.isNotBlank() && startMs >= 0) {
                    cues += RawCaptionCue(
                        startMs = startMs,
                        endMs = startMs + durationMs.coerceAtLeast(200L),
                        text = content,
                    )
                }
            }
            event = parser.next()
        }
        return cues
    }

    private fun readElementText(parser: XmlPullParser): String {
        val elementDepth = parser.depth
        return buildString {
            var event = parser.next()
            while (!(event == XmlPullParser.END_TAG && parser.depth == elementDepth)) {
                if (event == XmlPullParser.TEXT) append(parser.text)
                event = parser.next()
            }
        }
    }

    private fun secondsToMs(value: String?, defaultSeconds: Double = -0.001): Long =
        ((value?.toDoubleOrNull() ?: defaultSeconds) * 1_000).toLong()

    private fun String.normalizeCaptionText(): String = replace(Regex("\\s+"), " ").trim()
}
