package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPrefetchTest {
    private val sentence = SentenceSlice("Hello there, my friend.", cuts = listOf(12), index = 0)

    @Test fun aSplitSentenceSendsTheSentenceAndEachRowPrefix() {
        val row = SubtitleSegment(0, 0, 1000, "Hello there,", sentence = sentence)
        assertEquals(listOf("Hello there, my friend.", "Hello there,"), rowTranslationTexts(row))
        assertEquals(listOf("Plain row"), rowTranslationTexts(SubtitleSegment(1, 0, 1000, "Plain row")))
    }

    @Test fun theNextMinuteOfUntranslatedSentencesIsSentOnce() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 1000, "Earlier"),
                SubtitleSegment(1, 1_000, 2_000, "Hello there,", sentence = sentence),
                SubtitleSegment(2, 2_000, 3_000, "my friend.", sentence = sentence.copy(index = 1)),
                SubtitleSegment(3, 3_000, 4_000, "Done", translatedText = "Xong"),
                SubtitleSegment(4, 30_000, 31_000, "Half a minute later"),
                SubtitleSegment(5, 61_000, 62_000, "Exactly one minute later"),
                SubtitleSegment(6, 61_001, 63_000, "Too far ahead"),
            )
        assertEquals(
            listOf("Hello there, my friend.", "Hello there,", "Half a minute later", "Exactly one minute later"),
            upcomingTranslationTexts(rows, 1),
        )
        assertEquals(listOf("Hello there, my friend.", "Hello there,"), upcomingTranslationTexts(rows, 1, aheadMs = 5_000))
    }
}
