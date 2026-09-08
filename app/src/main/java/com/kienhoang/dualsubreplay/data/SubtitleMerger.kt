package com.kienhoang.dualsubreplay.data

object SubtitleMerger {
    private const val MAX_GAP_MS = 1_200L
    private const val MAX_DURATION_MS = 6_000L
    private const val MAX_CHARACTERS = 96
    private const val MIN_TIMED_WORD_TEXT_COVERAGE = 0.85f
    private val sentenceEnding = Regex("[.!?。！？…][\\\"'’”)]*$")

    private val clauseConjunctions =
        Regex(
            "^(and|but|so|because|although|however|then|if|when|while|therefore|or)\\b",
            RegexOption.IGNORE_CASE,
        )

    fun merge(
        cues: List<RawCaptionCue>,
        enhancedNaturalFlow: Boolean = false,
    ): List<SubtitleSegment> {
        val ordered =
            cues
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
                val formattedText = if (enhancedNaturalFlow) {
                    text.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                } else {
                    text.trim()
                }
                output += SubtitleSegment(
                    id = output.size.toLong(),
                    startMs = start,
                    endMs = end,
                    originalText = formattedText,
                    words = timedOrEstimatedWords(
                        segmentText = formattedText,
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
            val startsWithConjunction = clauseConjunctions.containsMatchIn(nextText)
            val isPhraseBreak = startsWithConjunction && (gap >= 350L || end - start >= 3_000L) && text.length >= 32
            val shouldStartNew =
                gap > MAX_GAP_MS ||
                    end - start >= MAX_DURATION_MS ||
                    text.length >= MAX_CHARACTERS ||
                    sentenceEnding.containsMatchIn(text) ||
                    (enhancedNaturalFlow && isPhraseBreak)

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

    fun formatNaturalTranslation(text: String): String {
        if (text.isBlank()) return text
        val trimmed = text.trim()
        val capitalized = trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val sentencePunctuation = setOf('.', '?', '!', '。', '！', '？')
        return if (capitalized.isNotEmpty() && capitalized.last() !in sentencePunctuation) {
            "$capitalized."
        } else {
            capitalized
        }
    }

    /**
     * Keep YouTube's real word timing for each cue whenever it is coherent. If
     * one noisy auto-caption cue is missing/stale, estimate only that cue instead
     * of discarding accurate timing from every neighboring cue in the merged line.
     */
    private fun preparedCueWords(cue: RawCaptionCue): List<SubtitleWord> {
        val cueText = clean(cue.text)
        val sorted =
            cue.words
                .filter { word -> word.text.isNotBlank() }
                .sortedBy(SubtitleWord::startMs)
        val hasUsableTimedWords =
            sorted.isNotEmpty() &&
                sorted.all { word ->
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
        val hasValidTimedWords =
            sorted.isNotEmpty() &&
                sorted.all { word ->
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
    private fun wordsAlignWithText(
        text: String,
        words: List<SubtitleWord>,
    ): Boolean {
        if (text.isBlank() || words.isEmpty()) return false
        var searchFrom = 0
        var matchedCharacters = 0
        words.forEach { word ->
            val token = word.text.replace(Regex("\\s+"), " ").trim()
            if (token.isEmpty()) return@forEach
            val exactStart = text.indexOf(token, searchFrom)
            val start =
                if (exactStart >= 0) {
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

    private fun clean(text: String): String =
        text
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun separator(
        left: Char?,
        right: Char?,
    ): String {
        if (left == null || right == null || left.isWhitespace() || right.isWhitespace()) return ""
        return if (isCjk(left) || isCjk(right)) "" else " "
    }

    private fun isCjk(char: Char): Boolean =
        when (Character.UnicodeScript.of(char.code)) {
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
    internal fun splitSentenceChunks(
        text: String,
        maxCharacters: Int,
    ): List<String> {
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

    private fun mergeTinyTrailingChunk(
        pieces: List<String>,
        safeMax: Int,
    ): List<String> {
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

    private fun wrapAtWordEdges(
        text: String,
        safeMax: Int,
    ): List<String> {
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

    /** Split on text identity so uneven speech retains its source timestamps. */
    private fun buildSplitSegments(
        segment: SubtitleSegment,
        chunks: List<String>,
    ): List<SubtitleSegment> {
        if (segment.words.isEmpty()) return estimateSplitSegments(segment, chunks)
        val complete = completeTrailingWords(segment)
        if (complete.words.size != segment.words.size) return buildSplitSegments(complete, chunks)
        var cursor = 0
        val starts =
            segment.words.map { word ->
                val found = segment.originalText.indexOf(word.text.trim(), cursor, ignoreCase = true)
                if (found >= 0) cursor = found + word.text.trim().length
                found
            }
        if (starts.any { it < 0 }) return listOf(segment)
        var chunkCursor = 0
        val ends =
            chunks
                .dropLast(1)
                .map { chunk ->
                    val start = segment.originalText.indexOf(chunk, chunkCursor)
                    if (start >= 0) chunkCursor = start + chunk.length
                    chunkCursor
                }.filter { boundary ->
                    segment.words.indices.none { i ->
                        starts[i] < boundary && starts[i] +
                            segment.words[i]
                                .text
                                .trim()
                                .length > boundary
                    }
                }
        val boundaries = ends.filter { end -> starts.any { it >= end } }.distinct() + segment.originalText.length
        var textStart = 0
        var wordStart = 0
        val output = mutableListOf<SubtitleSegment>()
        boundaries.forEach { boundary ->
            val wordEnd = starts.indexOfFirst { it >= boundary }.let { if (it < 0) starts.size else it }
            if (wordEnd > wordStart) {
                val words = segment.words.subList(wordStart, wordEnd)
                val startMs = if (output.isEmpty()) segment.startMs else words.first().startMs
                val endMs = if (wordEnd == starts.size) segment.endMs else segment.words[wordEnd].startMs
                output +=
                    segment.copy(
                        startMs = startMs,
                        endMs = maxOf(endMs, words.last().endMs),
                        originalText = segment.originalText.substring(textStart, boundary).trim(),
                        translatedText = null,
                        words = words.toList(),
                    )
                textStart = boundary
                wordStart = wordEnd
            }
        }
        return output.ifEmpty { listOf(segment) }
    }

    private fun completeTrailingWords(segment: SubtitleSegment): SubtitleSegment {
        var end = 0
        segment.words.forEach { word ->
            val start = segment.originalText.indexOf(word.text.trim(), end, ignoreCase = true)
            if (start >= 0) end = start + word.text.trim().length
        }
        val suffix = segment.originalText.substring(end).trim()
        if (suffix.isBlank()) return segment
        return segment.copy(words = segment.words + estimateWordTimings(suffix, segment.words.last().endMs, segment.endMs))
    }

    /** Only captions without source-word timings use proportional estimates. */
    private fun estimateSplitSegments(
        segment: SubtitleSegment,
        chunks: List<String>,
    ): List<SubtitleSegment> {
        val total = chunks.sumOf { it.length }.coerceAtLeast(1)
        var consumed = 0L
        return chunks.mapIndexed { index, text ->
            val start = segment.startMs + (segment.endMs - segment.startMs) * consumed / total
            consumed += text.length
            val end =
                if (index == chunks.lastIndex) {
                    segment.endMs
                } else {
                    segment.startMs + (segment.endMs - segment.startMs) * consumed / total
                }
            segment.copy(
                startMs = start,
                endMs = end,
                originalText = text,
                translatedText = null,
                words = estimateWordTimings(text, start, end),
            )
        }
    }
}

internal const val SPLIT_SENTENCE_MAX_CHARACTERS = 48

// Keep Android's regex engine happy: both lookbehinds have fixed width.
// The previous `*` inside lookbehind could throw PatternSyntaxException at runtime.
private val sentenceBreak =
    Regex(
        "(?<=[.!?。！？…])\\s+|(?<=[.!?。！？…][\\\"'’”)])\\s+",
    )
private val clauseBreak = Regex("(?<=[,;:])\\s+")
