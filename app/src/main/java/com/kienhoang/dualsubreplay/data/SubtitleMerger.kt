package com.kienhoang.dualsubreplay.data

object SubtitleMerger {
    private const val MAX_GAP_MS = 1_200L
    private const val MAX_DURATION_MS = 6_000L
    private const val MAX_CHARACTERS = 96
    private const val MIN_TIMED_WORD_TEXT_COVERAGE = 0.85f
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
        var pendingWords = preparedCueWords(ordered.first())

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
                    ),
                )
            }
            pendingWords = emptyList()
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
                pendingWords = preparedCueWords(cue)
            } else {
                text += separator(text.lastOrNull(), nextText.firstOrNull()) + nextText
                end = maxOf(end, cue.endMs)
                pendingWords += preparedCueWords(cue)
            }
        }
        flush()
        return output
    }

    /**
     * Keep YouTube's real word timing for each cue whenever it is coherent. If
     * one noisy auto-caption cue is missing/stale, estimate only that cue instead
     * of discarding accurate timing from every neighboring cue in the merged line.
     */
    private fun preparedCueWords(cue: RawCaptionCue): List<SubtitleWord> {
        val cueText = clean(cue.text)
        val sorted = cue.words
            .filter { word -> word.text.isNotBlank() }
            .sortedBy(SubtitleWord::startMs)
        val hasUsableTimedWords = sorted.isNotEmpty() && sorted.all { word ->
            word.startMs >= cue.startMs &&
                word.startMs < cue.endMs &&
                word.endMs > word.startMs &&
                word.endMs <= cue.endMs
        } && wordsAlignWithText(cueText, sorted)
        return if (hasUsableTimedWords) {
            sorted
        } else {
            estimateWordTimings(cueText, cue.startMs, cue.endMs)
        }
    }

    /**
     * Keeps the collected real/locally-estimated timings when they remain valid
     * after merging. A final whole-line estimate is only a safety fallback for
     * malformed overlapping data that cannot be aligned to the visible text.
     */
    private fun timedOrEstimatedWords(
        segmentText: String,
        startMs: Long,
        endMs: Long,
        collectedWords: List<SubtitleWord>,
    ): List<SubtitleWord> {
        val sorted = collectedWords.sortedBy(SubtitleWord::startMs)
        val hasValidTimedWords = sorted.isNotEmpty() && sorted.all { word ->
            word.startMs >= startMs &&
                word.startMs < endMs &&
                word.endMs > word.startMs &&
                word.endMs <= endMs
        } && wordsAlignWithText(segmentText, sorted)
        return if (hasValidTimedWords) {
            sorted
        } else {
            estimateWordTimings(segmentText, startMs, endMs)
        }
    }

    /**
     * True when timed caption chunks can be found in display order and cover
     * enough of the visible text to provide useful karaoke highlighting.
     * Broken partial ASR payloads fall back to local estimates so visible words
     * do not stay permanently unhighlighted.
     */
    private fun wordsAlignWithText(text: String, words: List<SubtitleWord>): Boolean {
        if (text.isBlank() || words.isEmpty()) return false
        var searchFrom = 0
        var matchedCharacters = 0
        words.forEach { word ->
            val token = word.text.replace(Regex("\\s+"), " ").trim()
            if (token.isEmpty()) return@forEach
            val exactStart = text.indexOf(token, searchFrom)
            val start = if (exactStart >= 0) {
                exactStart
            } else {
                text.indexOf(token, searchFrom, ignoreCase = true)
            }
            if (start < 0) return false
            matchedCharacters += token.count { !it.isWhitespace() }
            searchFrom = start + token.length
        }
        val visibleCharacters = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        return matchedCharacters.toFloat() / visibleCharacters >= MIN_TIMED_WORD_TEXT_COVERAGE
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
     * Turns one long segment into several short display chunks while keeping the
     * canonical word timeline untouched. Words are assigned by their position in
     * the subtitle text, never by where their timestamps happen to fall inside a
     * character-proportional range. When a following chunk has real timed words,
     * its first word becomes the visual boundary between the two chunks.
     */
    private fun buildSplitSegments(segment: SubtitleSegment, chunks: List<String>): List<SubtitleSegment> {
        val fallbackBoundaries = proportionalSplitBoundaries(segment, chunks)
        val assignedWords = assignWordsToChunksByText(segment.originalText, segment.words, chunks)
        val usableWords = chunks.mapIndexed { index, chunkText ->
            assignedWords[index]
                .takeIf { candidate -> candidate.isNotEmpty() && wordsAlignWithText(chunkText, candidate) }
                .orEmpty()
        }

        val boundaries = fallbackBoundaries.toMutableList()
        for (index in 1 until chunks.size) {
            val exactNextStart = usableWords[index].firstOrNull()?.startMs
            val safeFallback = usableWords[index - 1].lastOrNull()?.endMs?.let { previousEnd ->
                maxOf(boundaries[index], previousEnd)
            } ?: boundaries[index]
            boundaries[index] = (exactNextStart ?: safeFallback)
                .coerceIn(segment.startMs, segment.endMs)
        }
        for (index in 1 until boundaries.size) {
            boundaries[index] = maxOf(boundaries[index], boundaries[index - 1])
        }

        return chunks.mapIndexed { index, chunkText ->
            val chunkStart = boundaries[index]
            val chunkEnd = boundaries[index + 1]
            val candidateWords = usableWords[index]
            val words = if (candidateWords.isNotEmpty()) {
                candidateWords
            } else {
                estimateWordTimings(chunkText, chunkStart, chunkEnd)
            }
            SubtitleSegment(
                id = segment.id,
                startMs = chunkStart,
                endMs = chunkEnd,
                originalText = chunkText,
                translatedText = null,
                words = words,
            )
        }
    }

    /** Character-proportional boundaries are only a fallback when no real word anchor exists. */
    private fun proportionalSplitBoundaries(
        segment: SubtitleSegment,
        chunks: List<String>,
    ): List<Long> {
        val duration = (segment.endMs - segment.startMs).coerceAtLeast(0L)
        val lengths = chunks.map { chunk -> chunk.length.toFloat().coerceAtLeast(1f) }
        val totalLength = lengths.sum().coerceAtLeast(1f)
        var consumedWeight = 0f
        val boundaries = mutableListOf(segment.startMs)
        for (index in 0 until chunks.lastIndex) {
            consumedWeight += lengths[index]
            boundaries += segment.startMs + (duration * (consumedWeight / totalLength)).toLong()
        }
        boundaries += segment.endMs
        return boundaries
    }

    /**
     * Maps timed words to display chunks by text order. Timestamp skew must never
     * move a word into another visual sentence: that was the source of karaoke
     * regressions when long-sentence splitting was enabled.
     */
    private fun assignWordsToChunksByText(
        segmentText: String,
        words: List<SubtitleWord>,
        chunks: List<String>,
    ): List<List<SubtitleWord>> {
        val buckets = List(chunks.size) { mutableListOf<SubtitleWord>() }
        if (segmentText.isBlank() || words.isEmpty() || chunks.isEmpty()) return buckets

        val chunkRanges = mutableListOf<IntRange>()
        var chunkSearchFrom = 0
        chunks.forEach { chunk ->
            val start = findTextStart(segmentText, chunk, chunkSearchFrom)
            if (start < 0) {
                chunkRanges += 1..0
            } else {
                chunkRanges += start until (start + chunk.length)
                chunkSearchFrom = start + chunk.length
            }
        }

        var wordSearchFrom = 0
        words.forEach { word ->
            val token = word.text.replace(Regex("\\s+"), " ").trim()
            if (token.isEmpty()) return@forEach
            val start = findTextStart(segmentText, token, wordSearchFrom)
            if (start < 0) return@forEach
            val target = chunkRanges.indexOfFirst { range -> !range.isEmpty() && start in range }
            if (target >= 0) buckets[target] += word
            wordSearchFrom = start + token.length
        }
        return buckets
    }

    private fun findTextStart(text: String, token: String, fromIndex: Int): Int {
        val safeFrom = fromIndex.coerceIn(0, text.length)
        val exact = text.indexOf(token, safeFrom)
        return if (exact >= 0) exact else text.indexOf(token, safeFrom, ignoreCase = true)
    }
}

internal const val SPLIT_SENTENCE_MAX_CHARACTERS = 48

// Keep Android's regex engine happy: both lookbehinds have fixed width.
// The previous `*` inside lookbehind could throw PatternSyntaxException at runtime.
private val sentenceBreak = Regex(
    "(?<=[.!?。！？…])\\s+|(?<=[.!?。！？…][\\\"'’”)])\\s+",
)
private val clauseBreak = Regex("(?<=[,;:])\\s+")