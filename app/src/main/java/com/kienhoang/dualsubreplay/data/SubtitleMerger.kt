package com.kienhoang.dualsubreplay.data

object SubtitleMerger {
    private const val MAX_GAP_MS = 1_200L
    private const val MAX_DURATION_MS = 9_000L
    private const val MAX_CHARACTERS = 160
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

        fun flush() {
            if (text.isNotBlank()) {
                output += SubtitleSegment(
                    id = output.size.toLong(),
                    startMs = start,
                    endMs = end,
                    originalText = text.trim(),
                )
            }
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
            } else {
                text += separator(text.lastOrNull(), nextText.firstOrNull()) + nextText
                end = maxOf(end, cue.endMs)
            }
        }
        flush()
        return output
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
