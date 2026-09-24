package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import java.io.File

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
    val pieces = sentencePieces(source, natural)
    return captionDisplaySegments(
        source = pieces,
        units = sentenceCaptionUnits(pieces, pieces, natural),
        format = format,
    )
}

/** Natural captions translate whole sentences, so first cut cues that contain a sentence end. */
private fun sentencePieces(
    source: List<SubtitleSegment>,
    natural: Boolean,
): List<SubtitleSegment> = if (natural) SubtitleMerger.splitAtSentenceEnds(source) else source

private fun captionDisplaySegments(
    source: List<SubtitleSegment>,
    units: List<CaptionTranslationUnit>,
    format: CaptionFormat,
): List<SubtitleSegment> {
    val sentences =
        units.mapIndexed { index, unit ->
            SubtitleSegment(
                id = index.toLong(),
                startMs = source[unit.indices.first()].startMs,
                endMs = source[unit.indices.last()].endMs,
                originalText = unit.text,
                words = unit.indices.flatMap { source[it].words },
            )
        }
    if (format != CaptionFormat.SHORT_PHRASES) return sentences
    return sentences
        .flatMap { sentence ->
            val rows = SubtitleMerger.splitLongSegments(listOf(sentence))
            if (rows.size <= 1) rows else withSentenceSlices(sentence.originalText, rows)
        }.mapIndexed { index, row -> row.copy(id = index.toLong()) }
}

/** Short rows remember their parent sentence so it is translated once, in full context. */
internal fun withSentenceSlices(
    sentence: String,
    rows: List<SubtitleSegment>,
): List<SubtitleSegment> {
    var cursor = 0
    val starts =
        rows.map { row ->
            val found = sentence.indexOf(row.originalText, cursor)
            if (found >= 0) cursor = found + row.originalText.length
            found
        }
    if (starts.any { it < 0 }) return rows
    val cuts = starts.drop(1)
    return rows.mapIndexed { index, row -> row.copy(sentence = SentenceSlice(sentence, cuts, index)) }
}

internal const val CAPTION_PREPARATION_BATCH_SIZE = 256

/**
 * Builds the presentation track without loading the complete source track into memory. For natural
 * captions only the last open sentence unit crosses a batch boundary, so retaining that unit makes
 * the batched result identical to formatting one continuous list.
 */
internal suspend fun prepareCaptionDisplayStore(
    source: SubtitleStore,
    directory: File,
    format: CaptionFormat,
    natural: Boolean,
    batchSize: Int = CAPTION_PREPARATION_BATCH_SIZE,
): SubtitleStore {
    require(batchSize > 0) { "Caption preparation batch size must be positive." }
    return SubtitleStore.create(directory) { append ->
        var cursor = 0
        var nextId = 0L
        var carry = emptyList<SubtitleSegment>()
        while (cursor < source.size) {
            val end = minOf(cursor + batchSize, source.size)
            val buffered = carry + sentencePieces(source.read(cursor until end), natural)
            val finalBatch = end == source.size
            val units = sentenceCaptionUnits(buffered, buffered, natural)
            val readyUnits =
                if (natural && !finalBatch && units.isNotEmpty()) {
                    units.dropLast(1)
                } else {
                    units
                }
            val ready =
                captionDisplaySegments(buffered, readyUnits, format).map { segment ->
                    segment.copy(id = nextId++)
                }
            append(ready)
            carry =
                if (natural && !finalBatch && units.isNotEmpty()) {
                    units.last().indices.map(buffered::get)
                } else {
                    emptyList()
                }
            cursor = end
        }
    }
}
