package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Translate with AI shows Google's lines first; each sentence takes the AI's when it arrives (owner request 2026-10-28). */
class TranslationUpgradesTest {
    private val sentence = "The reality is that two different things are happening at once right now."
    private val cut = sentence.indexOf("happening")
    private val prefix = sentence.substring(0, cut).trim()

    private val googleRows =
        listOf(
            SubtitleSegment(0, 0, 2_000, prefix, sentence = SentenceSlice(sentence, listOf(cut), 0), translatedText = "Thực tế là hai"),
            SubtitleSegment(
                1,
                2_000,
                4_000,
                sentence.substring(cut),
                sentence = SentenceSlice(sentence, listOf(cut), 1),
                translatedText = "điều khác nhau đang xảy ra.",
            ),
            SubtitleSegment(2, 4_000, 5_000, "On one side, we have the CVEs.", translatedText = "Một bên, chúng tôi có CVE."),
            SubtitleSegment(3, 5_000, 6_000, "Not translated yet."),
        )

    private val ai =
        mapOf(
            sentence to "Thực tế là có hai chuyện khác nhau đang diễn ra cùng lúc.",
            prefix to "Thực tế là có hai chuyện khác nhau",
            "Not translated yet." to "Chưa dịch.",
        )

    @Test fun theAiTranslationReplacesGooglesRowsAndFillsEmptyOnes() {
        val upgraded = withUpgrades(googleRows) { ai[it] }
        assertEquals("Thực tế là có hai chuyện khác nhau", upgraded[0].translatedText)
        assertEquals("đang diễn ra cùng lúc.", upgraded[1].translatedText)
        // The AI has not written this line yet: Google's stays.
        assertEquals("Một bên, chúng tôi có CVE.", upgraded[2].translatedText)
        assertEquals("Chưa dịch.", upgraded[3].translatedText)
    }

    @Test fun rowsAlreadyShowingTheAiTranslationStayAsTheyAre() {
        val upgraded = withUpgrades(googleRows) { ai[it] }
        // Without the prefix the cut would be proportional; the shown rows already read as the AI's, so they are kept.
        val again = withUpgrades(upgraded) { if (it == prefix) null else ai[it] }
        assertSame(upgraded, again)
    }

    @Test fun nothingArrivedLeavesTheRows() {
        assertSame(googleRows, withUpgrades(googleRows) { null })
    }
}
