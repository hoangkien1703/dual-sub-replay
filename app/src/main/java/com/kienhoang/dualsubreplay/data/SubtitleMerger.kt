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
}
