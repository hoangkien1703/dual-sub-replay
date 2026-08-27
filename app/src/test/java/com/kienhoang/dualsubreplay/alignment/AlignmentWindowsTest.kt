package com.kienhoang.dualsubreplay.alignment

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleTimingSource
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentWindowsTest {
    @Test
    fun `enhanced plans overlap context but emit every segment once`() {
        val segments = (0 until 6).map(::segment)

        val plans = alignmentWindowPlans(
            segments = segments,
            preferredIndex = 1,
            maxCoreSpanMs = 6_000L,
            overlapContextMs = 2_500L,
            maxContextSpanMs = 10_000L,
        )

        assertEquals(0..2, plans[0].outputIndices)
        assertEquals(0..4, plans[0].contextIndices)
        assertEquals(3..5, plans[1].outputIndices)
        assertEquals(1..5, plans[1].contextIndices)
        assertEquals((0 until 6).toList(), plans.flatMap { it.outputIndices.toList() }.sorted())
        assertTrue(plans[0].contextIndices.intersect(plans[1].contextIndices).isNotEmpty())
    }

    @Test
    fun `overlap duration does not absorb every contiguous subtitle`() {
        val plans = alignmentWindowPlans(
            segments = (0 until 10).map(::segment),
            preferredIndex = 0,
            maxCoreSpanMs = 6_000L,
            overlapContextMs = 2_500L,
            maxContextSpanMs = 60_000L,
        )

        assertEquals(0..4, plans.first().contextIndices)
    }

    @Test
    fun `window containing playback position is aligned first`() {
        val plans = alignmentWindowPlans(
            segments = (0 until 9).map(::segment),
            preferredIndex = 7,
            maxCoreSpanMs = 6_000L,
        )

        assertTrue(7 in plans.first().outputIndices)
    }

    @Test
    fun `word cap prevents an unbounded ctc trellis`() {
        val dense = (0 until 3).map { index ->
            segment(index).copy(
                words = (0 until 30).map { word ->
                    SubtitleWord("word$word", index * 2_000L, index * 2_000L + 100L)
                },
            )
        }

        val plans = alignmentWindowPlans(
            segments = dense,
            preferredIndex = 0,
            maxContextWords = 40,
        )

        assertEquals(3, plans.size)
        assertTrue(plans.all { it.contextIndices.count() == 1 })
    }

    @Test
    fun `aligned context is stitched back only into the core range`() {
        val segments = (0 until 4).map(::segment)
        val plan = AlignmentWindowPlan(contextIndices = 0..3, outputIndices = 1..2)
        val alignedWords = segments.mapIndexed { index, segment ->
            segment.words.single().copy(
                startMs = segment.startMs - 125L,
                endMs = segment.endMs - 125L,
                timingSource = SubtitleTimingSource.ACOUSTIC_ALIGNED,
            )
        }

        val stitched = splitAlignedWindow(segments, plan, alignedWords)

        assertEquals(listOf(1, 2), stitched.map { it.first })
        assertEquals(1_875L, stitched.first().second.startMs)
        assertEquals(SubtitleTimingSource.ACOUSTIC_ALIGNED, stitched.first().second.words.single().timingSource)
    }

    private fun segment(index: Int): SubtitleSegment {
        val start = index * 2_000L
        return SubtitleSegment(
            id = index.toLong(),
            startMs = start,
            endMs = start + 2_000L,
            originalText = "word $index",
            words = listOf(SubtitleWord("word", start, start + 1_000L)),
        )
    }
}
