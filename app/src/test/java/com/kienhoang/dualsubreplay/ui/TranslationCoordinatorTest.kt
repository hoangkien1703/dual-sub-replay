package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.*
import org.junit.Test

class TranslationCoordinatorTest {
    @Test fun punctuationAndLongSilencePreventJoiningUnrelatedSentences() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 1000, "Done."),
                SubtitleSegment(1, 1000, 2000, "Next fragment"),
                SubtitleSegment(2, 4000, 5000, "a separate thought"),
            )
        assertEquals(rows.map { it.originalText }, sentenceCaptionUnits(rows, rows, true).map { it.text })
    }

    @Test fun japaneseFragmentsJoinWithoutAddingSpacesAndRetainSourceTimes() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 1000, "雨が降っていたので"),
                SubtitleSegment(1, 1000, 2000, "出かけるのをやめました。"),
            )
        assertEquals("雨が降っていたので出かけるのをやめました。", sentenceCaptionUnits(rows, rows, true).single().text)
        assertEquals(listOf(0L, 1000L), rows.map { it.startMs })
    }

    private val sentence = SubtitleSegment(0, 0, 2000, "I gave up because it was raining.")
    private val display =
        listOf(
            sentence.copy(endMs = 1000, originalText = "I gave up"),
            sentence.copy(id = 1, startMs = 1000, originalText = "because it was raining."),
        )

    @Test fun naturalTranslationRetainsContextAcrossDisplaySplits() {
        val unit = sentenceCaptionUnits(display, listOf(sentence), true).single()
        assertEquals(sentence.originalText, unit.text)
        assertEquals(listOf(0, 1), unit.indices)
        assertEquals(1000, display[1].startMs)
    }

    @Test fun rawModePreservesIndependentDisplayUnits() {
        assertEquals(display.map { it.originalText }, sentenceCaptionUnits(display, listOf(sentence), false).map { it.text })
    }

    @Test fun unmatchedDisplaySegmentsAreNeverLost() {
        assertEquals(listOf(0, 1), sentenceCaptionUnits(display, emptyList(), true).flatMap { it.indices })
    }
}
