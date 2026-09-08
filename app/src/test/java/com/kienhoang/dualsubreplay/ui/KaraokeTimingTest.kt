package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeTimingTest {
    @Test
    fun liveWordMapsAcrossSplitSegmentBoundary() {
        val segments =
            listOf(
                segment(0, 0, "We are"),
                segment(1, 1_000, "really ready"),
            )

        assertEquals(
            KaraokePosition(1, 0),
            mapLiveCaptionWord(
                segments = segments,
                referenceSegmentIndex = 0,
                liveTokens = karaokeTokens("are really"),
                liveActiveWordIndex = 1,
            ),
        )
    }

    @Test
    fun repeatedWordUsesContextAndPreviousForwardPosition() {
        val segments = listOf(segment(0, 0, "go now go home"))

        assertEquals(
            KaraokePosition(0, 2),
            mapLiveCaptionWord(
                segments = segments,
                referenceSegmentIndex = 0,
                liveTokens = karaokeTokens("go home"),
                liveActiveWordIndex = 0,
                previousPosition = KaraokePosition(0, 2),
            ),
        )
    }

    private fun segment(
        id: Long,
        startMs: Long,
        text: String,
    ): SubtitleSegment {
        val tokens = text.split(' ')
        val duration = tokens.size * 400L
        return SubtitleSegment(
            id = id,
            startMs = startMs,
            endMs = startMs + duration,
            originalText = text,
            words =
                tokens.mapIndexed { index, token ->
                    SubtitleWord(
                        text = token,
                        startMs = startMs + index * 400L,
                        endMs = startMs + (index + 1) * 400L,
                    )
                },
        )
    }
}
