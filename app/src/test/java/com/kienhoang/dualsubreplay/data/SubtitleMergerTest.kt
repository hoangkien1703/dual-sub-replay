package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMergerTest {
    @Test fun mergesShortCuesUntilSentenceBoundary() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(0, 1_000, "This is"),
                RawCaptionCue(1_000, 2_000, "a complete sentence."),
                RawCaptionCue(2_100, 3_000, "Next one"),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals("This is a complete sentence.", merged[0].originalText)
        assertEquals(0, merged[0].startMs)
        assertEquals(2_000, merged[0].endMs)
    }

    @Test fun startsNewParagraphAfterLongGap() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(0, 500, "Hello"),
                RawCaptionCue(3_000, 4_000, "again"),
            ),
        )
        assertEquals(listOf("Hello", "again"), merged.map { it.originalText })
    }

    @Test fun doesNotInsertSpacesInsideJapaneseText() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(0, 500, "今日は"),
                RawCaptionCue(500, 1_000, "いい天気です。"),
            ),
        )
        assertEquals("今日はいい天気です。", merged.single().originalText)
    }

    @Test fun keepsReplayParagraphsShortEnoughForTheDrawer() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(0, 2_000, "First phrase"),
                RawCaptionCue(2_000, 4_000, "continues here"),
                RawCaptionCue(4_000, 6_000, "with more detail"),
                RawCaptionCue(6_000, 8_000, "Next replay phrase"),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals("Next replay phrase", merged[1].originalText)
    }

    @Test fun preservesPreciseTimingsAfterSilentLeadIn() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(
                    startMs = 0,
                    endMs = 1_000,
                    text = "Hello world",
                    words = listOf(
                        SubtitleWord("Hello", 250, 600),
                        SubtitleWord("world", 600, 1_000),
                    ),
                ),
            ),
        ).single()

        assertEquals(listOf(250L, 600L), merged.words.map { it.startMs })
    }

    @Test fun estimatesWholeMergedSegmentWhenAnyCueHasNoWordTimings() {
        val merged = SubtitleMerger.merge(
            listOf(
                RawCaptionCue(
                    startMs = 0,
                    endMs = 1_000,
                    text = "Hello",
                    words = listOf(SubtitleWord("Hello", 250, 1_000)),
                ),
                RawCaptionCue(1_000, 2_000, "world"),
            ),
        ).single()

        assertEquals(listOf("Hello", "world"), merged.words.map { it.text })
        assertEquals(0L, merged.words.first().startMs)
        assertEquals(2_000L, merged.words.last().endMs)
    }

    @Test fun splitsLongSegmentsIntoShorterChunksAtSentenceBoundaries() {
        val long = "The quick brown fox jumps over the lazy dog. Then it rests under a tree."
        val segments = listOf(SubtitleSegment(1, 0, 4_000, long))

        val split = SubtitleMerger.splitLongSegments(segments)

        assertTrue(split.size >= 2)
        assertTrue(split.all { it.originalText.length <= SPLIT_SENTENCE_MAX_CHARACTERS })
        // Sentence-final punctuation ends a chunk instead of dangling mid-chunk.
        assertTrue(split.first().originalText.endsWith("."))
        assertEquals(long, split.joinToString(" ") { it.originalText })
    }

    @Test fun keepsShortSegmentsUnchangedWhenSplitting() {
        val segments = listOf(
            SubtitleSegment(1, 0, 1_000, "Hello world"),
            SubtitleSegment(2, 1_000, 2_000, "Another short line"),
        )

        val split = SubtitleMerger.splitLongSegments(segments)

        // Content, timing, and order survive untouched; only ids are renumbered.
        assertEquals(segments.map { Triple(it.originalText, it.startMs, it.endMs) }, split.map { Triple(it.originalText, it.startMs, it.endMs) })
        assertEquals(listOf(0L, 1L), split.map { it.id })
    }

    @Test fun splitSegmentIdsNeverCollideOnRealisticTimelines() {
        // Regression: chunk ids derived as parent * 1000 + index collided with
        // the original id of an unsplit far-later segment, crashing the
        // transcript list ("Key was already used") on real videos.
        val segments = mutableListOf<SubtitleSegment>()
        segments += SubtitleSegment(0, 0, 1_000, "short")
        segments += SubtitleSegment(1, 1_000, 2_000, "short again")
        segments += SubtitleSegment(
            2,
            2_000,
            4_000,
            "A long opening sentence that certainly needs splitting here. And more.",
        )
        for (id in 3..2_001) {
            segments += SubtitleSegment(id.toLong(), id * 1_000L, id * 1_000L + 500, "filler $id")
        }

        val split = SubtitleMerger.splitLongSegments(segments)

        assertTrue(split.size > segments.size)
        assertEquals(split.size, split.map { it.id }.distinct().size)
        assertEquals(split.indices.map { it.toLong() }, split.map { it.id })
    }

    @Test fun dividesTimeProportionallyAcrossSplitChunks() {
        val segments = listOf(
            SubtitleSegment(1, 1_000, 5_000, "First half of a very long sentence right here. Second half follows."),
        )

        val split = SubtitleMerger.splitLongSegments(segments)

        assertEquals(1_000L, split.first().startMs)
        assertEquals(5_000L, split.last().endMs)
        assertTrue(split.zipWithNext().all { (left, right) -> left.endMs <= right.startMs })
    }

    @Test fun splitChunksKeepKaraokeHighlightWords() {
        val words = listOf(
            SubtitleWord("Alpha", 100, 400),
            SubtitleWord("beta", 400, 700),
            SubtitleWord("gamma", 700, 1_000),
            SubtitleWord("delta", 1_000, 1_300),
            SubtitleWord("epsilon", 1_300, 1_600),
        )
        val segment = SubtitleSegment(
            id = 3,
            startMs = 0,
            endMs = 2_000,
            originalText = "Alpha beta gamma delta epsilon continues beyond the limit here now",
            words = words,
        )

        val split = SubtitleMerger.splitLongSegments(segment.let { listOf(it) })

        assertTrue(split.size >= 2)
        val allWords = split.flatMap { it.words }
        // Every timed word survives exactly once so real-time highlighting keeps working.
        assertEquals(words.map { it.text }, allWords.filter { it.text in words.map(SubtitleWord::text) }.map { it.text })
        assertTrue(split.all { chunk -> chunk.words.isNotEmpty() })
    }

    @Test fun splitsCjkSentencesWithoutInsertingSpaces() {
        val longCjk = "这是一段特别长的中文句子需要被切成更短的片段方便学习者跟读理解每一个部分的含义并且不会丢失任何原始的时间信息与内容"
        val split = SubtitleMerger.splitLongSegments(
            listOf(SubtitleSegment(9, 0, 6_000, longCjk)),
        )

        assertTrue(split.size >= 2)
        assertTrue(split.all { it.originalText.length <= SPLIT_SENTENCE_MAX_CHARACTERS })
        assertEquals(longCjk.length, split.sumOf { it.originalText.length })
    }
}
