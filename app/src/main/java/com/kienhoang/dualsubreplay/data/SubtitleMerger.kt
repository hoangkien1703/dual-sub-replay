package com.kienhoang.dualsubreplay.data

object SubtitleMerger {
    private const val MAX_GAP_MS = 1_200L
    private const val MAX_DURATION_MS = 6_000L
    private const val MAX_CHARACTERS = 96
    private val sentenceEnding = Regex("[.!?。！？…][\\\"'’”)]*$")

    fun merge(cues: List<RawCaptionCue>): List<SubtitleSegment> {
        val ordered = cues
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (ordered.isEmpty()) return emptyList()

        val output = mutableListOf<SubtitleSegment>()
        var start = ordered.first().startMs
        var end = ordered.first().endMs
        var text = clean(ordered.first().text)
        var pendingWords = ordered.first().words.filter { word -> word.text.isNotBlank() }
        var pendingTimingsComplete = ordered.first().words.isNotEmpty()

        fun flush() {
            if (text.isNotBlank()) {
                output += SubtitleSegment(
                    id = output.size.toLong(),
                    startMs = start,
                    endMs = end,
                    originalText = text.trim(),
                    words = timedOrEstimatedWords(
                        segmentText = text.trim(),
                        startMs = start,
                        endMs = end,
                        collectedWords = pendingWords,
                        timingsComplete = pendingTimingsComplete,
                    ),
                )
            }
            pendingWords = emptyList()
            pendingTimingsComplete = true
        }

        ordered.drop(1).forEach { cue ->
            val nextText = clean(cue.text)
            val gap = cue.startMs - end
            val shouldStartNew = gap > MAX_GAP_MS ||
                end - start >= MAX_DURATION_MS ||
                text.length >= MAX_CHARACTERS ||
                sentenceEnding.containsMatchIn(text)

            if (shouldStartNew) {
                flush()
                start = cue.startMs
                end = cue.endMs
                text = nextText
                pendingWords = cue.words.filter { word -> word.text.isNotBlank() }
                pendingTimingsComplete = cue.words.isNotEmpty()
            } else {
                text += separator(text.lastOrNull(), nextText.firstOrNull()) + nextText
                end = maxOf(end, cue.endMs)
                pendingWords += cue.words.filter { word -> word.text.isNotBlank() }
                pendingTimingsComplete = pendingTimingsComplete && cue.words.isNotEmpty()
            }
        }
        flush()
        return output
    }

    /**
     * Keeps real caption word timings when every merged cue supplied them and
     * all values are valid. Silent lead-ins and gaps are intentionally allowed:
     * requiring timings to fill the entire cue would discard YouTube's precise
     * offsets and replace them with estimates, making karaoke highlighting drift.
     */
    private fun timedOrEstimatedWords(
        segmentText: String,
        startMs: Long,
        endMs: Long,
        collectedWords: List<SubtitleWord>,
        timingsComplete: Boolean,
    ): List<SubtitleWord> {
        val sorted = collectedWords.sortedBy(SubtitleWord::startMs)
        val hasValidTimedWords = timingsComplete && sorted.isNotEmpty() && sorted.all { word ->
            word.startMs >= startMs &&
                word.startMs < endMs &&
                word.endMs > word.startMs &&
                word.endMs <= endMs
        }
        return if (hasValidTimedWords) {
            sorted
        } else {
            estimateWordTimings(segmentText, startMs, endMs)
        }
    }

    private fun clean(text: String): String = text
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun separator(left: Char?, right: Char?): String {
        if (left == null || right == null || left.isWhitespace() || right.isWhitespace()) return ""
        return if (isCjk(left) || isCjk(right)) "" else " "
    }

    private fun isCjk(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }

    internal fun splitLongSegments(
        segments: List<SubtitleSegment>,
        maxCharacters: Int = SPLIT_SENTENCE_MAX_CHARACTERS,
    ): List<SubtitleSegment> {
        if (segments.isEmpty()) return emptyList()
        val output = mutableListOf<SubtitleSegment>()
        segments.forEach { segment ->
            val chunks = splitSentenceChunks(segment.originalText, maxCharacters)
            if (chunks.size <= 1) {
                output += segment
                return@forEach
            }
            output += buildSplitSegments(segment, chunks)
        }
        // The transcript list keys segments by id, so every output segment must
        // carry a unique fresh id; keeping original ids would collide once one
        // parent's chunk ids overlap another unsplit segment's id.
        return output.mapIndexed { index, segment -> segment.copy(id = index.toLong()) }
    }

    /** Splits [text] into short chunks at sentence ends, then clause marks, then word edges. */
    internal fun splitSentenceChunks(text: String, maxCharacters: Int): List<String> {
        val safeMax = maxCharacters.coerceAtLeast(16)
        val pieces = mutableListOf<String>()
        text.split(sentenceBreak).forEach { sentence ->
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.length <= safeMax) {
                pieces += trimmed
                return@forEach
            }
            trimmed.split(clauseBreak).forEach { clause ->
                val cleanClause = clause.trim()
                if (cleanClause.isEmpty()) return@forEach
                pieces += wrapAtWordEdges(cleanClause, safeMax)
            }
        }
        return mergeTinyTrailingChunk(pieces, safeMax)
    }

    private fun mergeTinyTrailingChunk(pieces: List<String>, safeMax: Int): List<String> {
        // A lone dangling word reads worse than a slightly longer final chunk,
        // but never re-create an oversized chunk while doing so.
        if (pieces.size < 2) return pieces
        val last = pieces.last()
        if (last.length > safeMax / 4) return pieces
        val secondLast = pieces[pieces.size - 2]
        val glue = if (secondLast.lastOrNull()?.let(::isCjk) == true && last.firstOrNull()?.let(::isCjk) == true) "" else " "
        if (secondLast.length + glue.length + last.length > safeMax) return pieces
        return pieces.dropLast(2) + (secondLast + glue + last).trim()
    }

    private fun wrapAtWordEdges(text: String, safeMax: Int): List<String> {
        if (text.length <= safeMax) return listOf(text)
        val hasSpaces = text.any(Char::isWhitespace)
        if (!hasSpaces) {
            // CJK runs have no word boundaries; cut on fixed character windows.
            return text.chunked(safeMax)
        }
        val words = text.split(Regex("\\s+"))
        val wrapped = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                current.isNotEmpty() && candidate.length > safeMax -> {
                    wrapped += current.toString()
                    current = StringBuilder(word)
                }
                else -> current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) wrapped += current.toString()
        return wrapped
    }

    /**
     * Turns one long segment into several short ones. The time range and the
     * caption words are divided proportionally so karaoke highlighting keeps
     * tracking the spoken word inside every new chunk.
     */
    private fun buildSplitSegments(segment: SubtitleSegment, chunks: List<String>): List<SubtitleSegment> {
        val duration = (segment.endMs - segment.startMs).coerceAtLeast(0L)
        val lengths = chunks.map { chunk -> chunk.length.toFloat().coerceAtLeast(1f) }
        val totalLength = lengths.sum()
        var consumedWeight = 0f
        val ranges = chunks.mapIndexed { index, _ ->
            val startShare = consumedWeight / totalLength
            consumedWeight += lengths[index]
            val endShare = consumedWeight / totalLength
            val chunkStart = segment.startMs + (duration * startShare).toLong()
            val chunkEnd = if (index == chunks.lastIndex) {
                segment.endMs
            } else {
                segment.startMs + (duration * endShare).toLong()
            }
            chunkStart..chunkEnd
        }.toMutableList()
        // Keep ranges monotonic even for degenerate zero-length inputs.
        for (index in 1 until ranges.size) {
            if (ranges[index].first < ranges[index - 1].last) {
                ranges[index] = ranges[index - 1].last..ranges[index].last
            }
        }

        val assignedWords = assignWordsToRanges(segment.words, ranges)

        return chunks.mapIndexed { index, chunkText ->
            val range = ranges[index]
            val words = assignedWords[index].ifEmpty {
                estimateWordTimings(chunkText, range.first, range.last)
            }
            SubtitleSegment(
                id = segment.id,
                startMs = range.first.coerceIn(segment.startMs, segment.endMs),
                endMs = range.last.coerceIn(segment.startMs, segment.endMs),
                originalText = chunkText,
                translatedText = null,
                words = words,
            )
        }
    }

    /** Places each timed word into the range covering its midpoint; misses fall back to estimates. */
    private fun assignWordsToRanges(
        words: List<SubtitleWord>,
        ranges: List<LongRange>,
    ): List<List<SubtitleWord>> {
        val buckets = List(ranges.size) { mutableListOf<SubtitleWord>() }
        words.forEach { word ->
            val midpoint = (word.startMs + word.endMs) / 2L
            val target = ranges.indexOfFirst { range -> midpoint in range }
            val bucketIndex = if (target >= 0) target else {
                if (midpoint < ranges.first().first) 0 else ranges.lastIndex
            }
            buckets[bucketIndex] += word
        }
        return buckets
    }
}

internal const val SPLIT_SENTENCE_MAX_CHARACTERS = 48

// Keep Android's regex engine happy: both lookbehinds have fixed width.
// The previous `*` inside lookbehind could throw PatternSyntaxException at runtime.
private val sentenceBreak = Regex(
    "(?<=[.!?。！？…])\\s+|(?<=[.!?。！？…][\\\"'’”)])\\s+",
)
private val clauseBreak = Regex("(?<=[,;:])\\s+")
