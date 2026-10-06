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

    @Test fun upcomingSentencesAreSentOnceSkippingTranslatedRows() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 1000, "Earlier", translatedText = null),
                SubtitleSegment(1, 1000, 2000, "Hello there,", sentence = sentence),
                SubtitleSegment(2, 2000, 3000, "my friend.", sentence = sentence.copy(index = 1)),
                SubtitleSegment(3, 3000, 4000, "Done", translatedText = "Xong"),
                SubtitleSegment(4, 4000, 5000, "Next one"),
                SubtitleSegment(5, 5000, 6000, "And another"),
            )
        assertEquals(
            listOf("Hello there, my friend.", "Hello there,", "Next one", "And another"),
            upcomingTranslationTexts(rows, 1),
        )
        // The limit counts sentences, starting with the row being translated.
        assertEquals(listOf("Hello there, my friend.", "Hello there,", "Next one"), upcomingTranslationTexts(rows, 1, maxSentences = 2))
    }
}
