package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.GrammarMeaning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrammarExplanationsTest {
    @Test
    fun everyGrammarMeaningHasText() {
        assertEquals(GrammarMeaning.entries.toSet(), GRAMMAR_MEANING_TEXT.keys)
        assertEquals(GrammarMeaning.entries.size, GRAMMAR_MEANING_TEXT.values.toSet().size)
    }

    @Test
    fun theTappedOffsetsAreUsedWhenTheyStillPointAtTheWord() {
        assertEquals(5 until 8, selectionRangeIn("僕の妻も、体育館に", "体育館", 5, 8))
    }

    @Test
    fun aMovedWordIsFoundByItsText() {
        assertEquals(2 until 5, selectionRangeIn("今、体育館に", "体育館", 5, 8))
        assertNull(selectionRangeIn("今日は", "体育館", 0, 3))
    }
}
