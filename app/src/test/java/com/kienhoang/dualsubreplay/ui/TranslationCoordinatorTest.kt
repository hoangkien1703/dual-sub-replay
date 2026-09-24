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

    @Test fun interiorSentenceEndsStartANewTranslationUnit() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 4000, "The three models, but you are paying a fraction of the price. Even at, look at"),
                SubtitleSegment(1, 4000, 5000, "this."),
            )
        val display = captionDisplaySegments(rows, CaptionFormat.WHOLE_SENTENCE, natural = true)
        assertEquals(
            listOf("The three models, but you are paying a fraction of the price.", "Even at, look at this."),
            display.map { it.originalText },
        )
        assertTrue(display[1].startMs > 0)
    }

    @Test fun unpunctuatedAutoCaptionsKeepTheirUnits() {
        val rows = listOf(SubtitleSegment(0, 0, 2000, "so we went to the beach"), SubtitleSegment(1, 5000, 6000, "and it rained"))
        assertEquals(
            rows.map {
                it.originalText
            },
            captionDisplaySegments(rows, CaptionFormat.WHOLE_SENTENCE, natural = true).map { it.originalText },
        )
    }

    @Test fun shortPhraseRowsRememberTheirWholeSentence() {
        val text = "Though the catacombs did offer a space where inconvenient bodies could disappear."
        val display = captionDisplaySegments(listOf(SubtitleSegment(0, 0, 6000, text)), CaptionFormat.SHORT_PHRASES, natural = true)
        assertTrue(display.size > 1)
        assertEquals(display.indices.toList(), display.map { it.sentence?.index })
        assertTrue(display.all { it.sentence?.text == text })
        assertEquals(display.indices.map { it.toLong() }, display.map { it.id })
        display.drop(1).forEachIndexed { index, row ->
            assertTrue(text.substring(display[index + 1].sentence!!.cuts[index]).startsWith(row.originalText))
        }
    }
}
