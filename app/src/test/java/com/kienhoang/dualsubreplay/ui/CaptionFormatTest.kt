package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.*
import org.junit.Test

class CaptionFormatTest {
    @Test fun defaultAndLegacyPreferencesAreStable() {
        assertEquals(CaptionFormat.SHORT_PHRASES, storedCaptionFormat(null))
        assertEquals(CaptionFormat.WHOLE_SENTENCE, storedCaptionFormat(null, false))
        CaptionFormat.entries.forEach { assertEquals(it, storedCaptionFormat(it.storageValue)) }
        assertEquals(CaptionFormat.SHORT_PHRASES, storedCaptionFormat("unknown"))
    }

    @Test fun bothFormatsRetainExactUnevenWordTimesIncludingRepeatedWords() {
        val text = "I think I can help you, because I have worked here for five years."
        val words = text.split(" ").mapIndexed { i, word -> SubtitleWord(word, i * i * 30L, (i + 1) * (i + 1) * 30L) }
        val source = listOf(SubtitleSegment(0, 0, words.last().endMs, text, words = words))
        val short = captionDisplaySegments(source, CaptionFormat.SHORT_PHRASES, true)
        val whole = captionDisplaySegments(source, CaptionFormat.WHOLE_SENTENCE, true)
        assertTrue(short.size > 1)
        assertEquals(text, short.joinToString(" ") { it.originalText })
        assertEquals(words, short.flatMap { it.words })
        assertEquals(words, whole.flatMap { it.words })
        // v0.9.5's 75 ms lead skips this fixture's 30 ms opening token once
        // the cue starts. All later token starts are at least 90 ms apart.
        words.forEachIndexed { wordIndex, word ->
            val expected = words[if (wordIndex == 0) 1 else wordIndex]
            listOf(short, whole).forEach { rows ->
                val index = activeSubtitleIndex(rows, word.startMs)
                assertEquals(expected, rows[index].words[activeWordIndex(rows, index, word.startMs)])
            }
        }
    }

    @Test fun missingPunctuationDoesNotJoinUnlimitedSentences() {
        val source = (0..20).map { SubtitleSegment(it.toLong(), it * 1000L, (it + 1) * 1000L, "another fragment") }
        val whole = captionDisplaySegments(source, CaptionFormat.WHOLE_SENTENCE, true)
        assertTrue(whole.size > 1)
        assertTrue(whole.all { it.endMs - it.startMs <= 12000 && it.originalText.length <= 240 })
        assertEquals(source.joinToString(" ") { it.originalText }, whole.joinToString(" ") { it.originalText })
    }
}
