package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationQueueTest {

    private fun segments(count: Int): List<SubtitleSegment> = List(count) { index ->
        SubtitleSegment(
            id = index.toLong(),
            startMs = index * 5_000L,
            endMs = index * 5_000L + 5_000L,
            originalText = "Segment $index",
        )
    }

    @Test fun nearestSegmentIndexFindsLastStartedSegment() {
        val list = segments(10)
        assertEquals(0, nearestSegmentIndex(list, timeMs = 1L))
        assertEquals(2, nearestSegmentIndex(list, timeMs = 10_000L))
        assertEquals(2, nearestSegmentIndex(list, timeMs = 12_345L))
        assertEquals(3, nearestSegmentIndex(list, timeMs = 15_000L))
        assertEquals(9, nearestSegmentIndex(list, timeMs = 999_999L))
    }
}
