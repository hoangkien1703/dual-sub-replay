package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
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
}
