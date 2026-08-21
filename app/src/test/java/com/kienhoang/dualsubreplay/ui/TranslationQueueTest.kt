package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun returnsEmptyBatchForEmptyPendingList() {
        assertTrue(nearestUntranslatedBatch(emptyList(), positionIndex = 5).isEmpty())
    }

    @Test fun returnsEmptyBatchForNonPositiveSize() {
        assertTrue(nearestUntranslatedBatch(listOf(1, 2, 3), positionIndex = 2, batchSize = 0).isEmpty())
        assertTrue(nearestUntranslatedBatch(listOf(1, 2, 3), positionIndex = 2, batchSize = -4).isEmpty())
    }

    @Test fun startsAtExactPositionThenAlternatesOutward() {
        val batch = nearestUntranslatedBatch((0 until 100).toList(), positionIndex = 40)
        assertEquals(40, batch.first())
        assertEquals(41, batch[1])
        assertEquals(39, batch[2])
        assertEquals(42, batch[3])
        assertEquals(38, batch[4])
    }

    @Test fun picksNearestSideWhenPositionFallsBetweenPendingIndices() {
        val pending = listOf(10, 20, 30, 40)
        assertEquals(20, nearestUntranslatedBatch(pending, positionIndex = 22).first())
        assertEquals(30, nearestUntranslatedBatch(pending, positionIndex = 28).first())
    }

    @Test fun breaksDistanceTiesTowardForwardPlayback() {
        val batch = nearestUntranslatedBatch(listOf(10, 12), positionIndex = 11)
        assertEquals(listOf(12, 10), batch)
    }

    @Test fun clampsPositionBeforeFirstAndAfterLastPending() {
        assertEquals(10, nearestUntranslatedBatch(listOf(10, 20, 30), positionIndex = -5).first())
        assertEquals(30, nearestUntranslatedBatch(listOf(10, 20, 30), positionIndex = 99).first())
    }

    @Test fun truncatesToRequestedBatchSize() {
        val batch = nearestUntranslatedBatch((0 until 100).toList(), positionIndex = 50, batchSize = 3)
        assertEquals(listOf(50, 51, 49), batch)
    }

    @Test fun returnsWholePendingListWhenSmallerThanBatchSize() {
        val batch = nearestUntranslatedBatch(listOf(7, 8), positionIndex = 0, batchSize = 8)
        assertEquals(listOf(7, 8), batch)
    }

    @Test fun neverRepeatsAnIndexWithinOrAcrossBatches() {
        var pending = (0 until 50).toMutableList()
        val seen = mutableSetOf<Int>()
        var positionIndex = 25
        while (pending.isNotEmpty()) {
            val batch = nearestUntranslatedBatch(pending, positionIndex)
            assertEquals(minOf(8, pending.size), batch.size)
            batch.forEach { index ->
                assertTrue(seen.add(index))
                pending.remove(index)
            }
            positionIndex = batch.first()
        }
        assertEquals((0 until 50).toSet(), seen)
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
