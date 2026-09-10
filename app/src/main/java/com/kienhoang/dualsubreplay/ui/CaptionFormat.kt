package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment

internal const val CAPTION_FORMAT_PREFERENCE = "caption_format"

enum class CaptionFormat(
    val storageValue: String,
    val label: String,
) {
    SHORT_PHRASES("short_phrases", "Short paired phrases"),
    WHOLE_SENTENCE("whole_sentence", "Whole sentence"),
}

internal fun storedCaptionFormat(
    raw: String?,
    legacySplit: Boolean = true,
): CaptionFormat =
    CaptionFormat.entries.firstOrNull { it.storageValue == raw }
        ?: if (raw == null && !legacySplit) CaptionFormat.WHOLE_SENTENCE else CaptionFormat.SHORT_PHRASES

/** Both presentations share source words; translation is applied only after grouping. */
internal fun captionDisplaySegments(
    source: List<SubtitleSegment>,
    format: CaptionFormat,
    natural: Boolean,
): List<SubtitleSegment> {
    val sentences =
        sentenceCaptionUnits(source, source, natural).mapIndexed { index, unit ->
            SubtitleSegment(
                id = index.toLong(),
                startMs = source[unit.indices.first()].startMs,
                endMs = source[unit.indices.last()].endMs,
                originalText = unit.text,
                words = unit.indices.flatMap { source[it].words },
            )
        }
    return if (format == CaptionFormat.SHORT_PHRASES) SubtitleMerger.splitLongSegments(sentences) else sentences
}
