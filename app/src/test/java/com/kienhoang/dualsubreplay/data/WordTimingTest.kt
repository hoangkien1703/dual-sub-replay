package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingTest {

    @Test fun estimateSpreadsWordsByLengthAcrossTheCue() {
        val words = estimateWordTimings("hi there", startMs = 1_000, endMs = 3_000)

        assertEquals(2, words.size)
        assertEquals("hi", words[0].text)
        assertEquals(1_000L, words[0].startMs)
        assertEquals("there", words[1].text)
        assertEquals(words[0].endMs, words[1].startMs)
        assertEquals(3_000L, words.last().endMs)
    }

    @Test fun estimateReturnsEmptyForBlankOrInvalidRanges() {
        assertTrue(estimateWordTimings("", 0, 1_000).isEmpty())
        assertTrue(estimateWordTimings("hello", 1_000, 1_000).isEmpty())
    }

    @Test fun activeWordIndexTracksTheSpokenWord() {
        val words = listOf(
            SubtitleWord("one", 0, 500),
            SubtitleWord("two", 500, 900),
            SubtitleWord("three", 1_000, 1_400),
        )

        assertEquals(-1, activeWordIndex(words, timeMs = -1))
        assertEquals(0, activeWordIndex(words, timeMs = 100))
        assertEquals(1, activeWordIndex(words, timeMs = 600))
        // Between words the last started word stays highlighted (no flicker).
        assertEquals(1, activeWordIndex(words, timeMs = 950))
        assertEquals(2, activeWordIndex(words, timeMs = 1_200))
    }

    @Test fun mergerKeepsRealWordTimingsWhenEveryCueProvidesThem() {
        val cues = listOf(
            RawCaptionCue(
                startMs = 1_000,
                endMs = 2_000,
                text = "Hello world",
                words = listOf(
                    SubtitleWord("Hello", 1_000, 1_400),
                    SubtitleWord("world", 1_400, 2_000),
                ),
            ),
            RawCaptionCue(
                startMs = 2_200,
                endMs = 3_200,
                text = "Next part",
                words = listOf(
                    SubtitleWord("Next", 2_200, 2_700),
                    SubtitleWord("part", 2_700, 3_200),
                ),
            ),
        )

        val segments = SubtitleMerger.merge(cues)

        assertEquals(listOf("Hello", "world", "Next", "part"), segments.single().words.map { it.text })
        assertEquals(2_200L, segments.single().words[2].startMs)
    }

    @Test fun mergerFallsBackToEstimatesWhenTimingsAreMissing() {
        val cues = listOf(
            RawCaptionCue(startMs = 0, endMs = 1_000, text = "No timings here"),
            RawCaptionCue(
                startMs = 1_200,
                endMs = 2_000,
                text = "partial",
                words = listOf(SubtitleWord("partial", 1_200, 2_000)),
            ),
        )

        val segment = SubtitleMerger.merge(cues).single()

        assertTrue(segment.words.isNotEmpty())
        assertEquals(segment.originalText.split(" "), segment.words.map { it.text })
        assertEquals(segment.startMs, segment.words.first().startMs)
        assertEquals(segment.endMs, segment.words.last().endMs)
    }
}
