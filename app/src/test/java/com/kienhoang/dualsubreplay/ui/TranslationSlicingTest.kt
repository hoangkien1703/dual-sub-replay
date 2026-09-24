package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSlicingTest {
    private val source = "The French Revolution temporarily stalled relocation efforts."

    @Test fun prefixTranslationPlacesTheRowBoundaryInTheWholeTranslation() {
        val slices =
            translationSlices(
                "Cuộc Cách mạng Pháp tạm thời làm đình trệ các nỗ lực di dời.",
                source.length,
                listOf(source.indexOf("stalled")),
                prefixTranslations = listOf("Cuộc Cách mạng Pháp tạm thời"),
            )
        assertEquals(listOf("Cuộc Cách mạng Pháp tạm thời", "làm đình trệ các nỗ lực di dời."), slices)
    }

    @Test fun withoutPrefixesTheSplitFollowsSourceProportionAtAWordEdge() {
        val slices = translationSlices("một hai ba bốn năm sáu", 20, listOf(10))
        assertEquals(listOf("một hai ba", "bốn năm sáu"), slices)
    }

    @Test fun nearbyPunctuationIsPreferredOverTheExactProportion() {
        val text = "The three models, but you are paying a fraction of the price."
        val slices =
            translationSlices(
                "Ba mô hình, nhưng bạn đang trả một phần nhỏ của giá.",
                text.length,
                listOf(text.indexOf("models,") + 3),
            )
        assertEquals("Ba mô hình,", slices.first())
    }

    @Test fun joinedSlicesReproduceTheTranslationAndNoneAreEmpty() {
        val translated = "một hai ba bốn năm sáu bảy tám chín mười"
        val slices = translationSlices(translated, 100, listOf(10, 20, 30, 90))
        assertEquals(translated, slices.joinToString(" "))
        assertTrue(slices.none(String::isEmpty))
    }

    @Test fun targetsWithoutSpacesSplitOnCharacters() {
        val slices = translationSlices("この砂浜に初めて来ました。", 40, listOf(20))
        assertEquals(2, slices.size)
        assertEquals("この砂浜に初めて来ました。", slices.joinToString(""))
        assertTrue(slices.none(String::isEmpty))
    }

    @Test fun moreRowsThanWordsKeepsOrderAndLeavesOnlyTrailingRowsEmpty() {
        val slices = translationSlices("Xin chào", 30, listOf(10, 20))
        assertEquals(listOf("Xin", "chào", ""), slices)
    }

    @Test fun oneTranslationFillsEveryRowOfItsSentence() {
        val display =
            captionDisplaySegments(
                listOf(SubtitleSegment(0, 0, 6000, source)),
                CaptionFormat.SHORT_PHRASES,
                natural = true,
            )
        assertTrue(display.size > 1)
        val filled = withTranslation(display, 0, "Cuộc Cách mạng Pháp tạm thời làm đình trệ các nỗ lực di dời.")
        assertTrue(filled.all { !it.translatedText.isNullOrEmpty() })
        assertEquals(
            "Cuộc Cách mạng Pháp tạm thời làm đình trệ các nỗ lực di dời.",
            filled.joinToString(" ") { it.translatedText.orEmpty() },
        )
    }
}
