package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSplitterDeviceTest {
    @Test fun splitterRunsOnAndroidRegexEngineWithoutCrashing() {
        val text = "This is a long first sentence that should be split safely. A second sentence follows, with another clause for testing."
        val split = SubtitleMerger.splitLongSegments(
            listOf(SubtitleSegment(0, 0, 6_000, text)),
        )
        assertTrue(split.size >= 2)
        assertTrue(split.all { it.originalText.isNotBlank() })
    }
}
