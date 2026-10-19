package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTranslationTest {
    @Test
    fun loneRowsTakeTheCuesTheyOverlapMost() {
        val rows = listOf(row(0, 0, 2_000, "Hello everyone!"), row(1, 2_000, 4_000, "I'm Ken!"))
        val cues =
            listOf(
                RawCaptionCue(0, 1_900, "Xin chào\nmọi người!"),
                // Mostly inside the second row.
                RawCaptionCue(1_800, 4_000, "Tôi là Ken!"),
            )

        val translations = youTubeRowTranslations(rows, cues, "vi")

        assertEquals("Xin chào mọi người!", translations["Hello everyone!"])
        assertEquals("Tôi là Ken!", translations["I'm Ken!"])
    }

    @Test
    fun aSentenceAndEachRowPrefixGetTheCuesOfItsRows() {
        val sentence = "Today I want to go to a fun place with you."
        val cut = sentence.indexOf("to a")
        val rows =
            listOf(
                row(0, 0, 2_000, sentence.substring(0, cut).trim(), SentenceSlice(sentence, listOf(cut), 0)),
                row(1, 2_000, 4_000, sentence.substring(cut), SentenceSlice(sentence, listOf(cut), 1)),
            )
        val cues = listOf(RawCaptionCue(0, 2_000, "Hôm nay tôi muốn đi"), RawCaptionCue(2_000, 4_000, "đến một nơi vui cùng các bạn."))

        val translations = youTubeRowTranslations(rows, cues, "vi")

        // Exactly the texts translating these rows asks for (see rowTranslationTexts).
        assertEquals(rowTranslationTexts(rows[0]).toSet(), translations.keys)
        assertEquals("Hôm nay tôi muốn đi đến một nơi vui cùng các bạn.", translations[sentence])
        assertEquals("Hôm nay tôi muốn đi", translations[sentence.substring(0, cut).trim()])
    }

    @Test
    fun japaneseAndChinesePiecesJoinWithoutSpaces() {
        val rows = listOf(row(0, 0, 4_000, "Good morning, everyone!"))
        val cues = listOf(RawCaptionCue(0, 2_000, "皆さん、"), RawCaptionCue(2_000, 4_000, "おはようございます！"))

        assertEquals("皆さん、おはようございます！", youTubeRowTranslations(rows, cues, "ja")["Good morning, everyone!"])
        assertEquals("皆さん、 おはようございます！", youTubeRowTranslations(rows, cues, "vi")["Good morning, everyone!"])
    }

    @Test
    fun rowsWithoutCuesAreLeftForTheDevice() {
        val rows = listOf(row(0, 0, 2_000, "Hello"), row(1, 10_000, 12_000, "Bye"))
        val translations = youTubeRowTranslations(rows, listOf(RawCaptionCue(0, 2_000, "Xin chào"), RawCaptionCue(5_000, 6_000, " ")), "vi")

        assertEquals(mapOf("Hello" to "Xin chào"), translations)
        assertFalse("Bye" in translations)
        assertTrue(youTubeRowTranslations(rows, emptyList(), "vi").isEmpty())
    }

    @Test
    fun aRepeatedLineKeepsItsFirstTranslation() {
        val rows = listOf(row(0, 0, 1_000, "Yes"), row(1, 1_000, 2_000, "Yes"))
        val cues = listOf(RawCaptionCue(0, 1_000, "Vâng"), RawCaptionCue(1_000, 2_000, "Đúng"))

        assertEquals("Vâng", youTubeRowTranslations(rows, cues, "vi")["Yes"])
    }

    private fun row(
        id: Long,
        startMs: Long,
        endMs: Long,
        text: String,
        sentence: SentenceSlice? = null,
    ) = SubtitleSegment(id, startMs, endMs, text, sentence = sentence)
}
