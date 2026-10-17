package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.GrammarMatch
import com.kienhoang.dualsubreplay.data.GrammarMeaning
import com.kienhoang.dualsubreplay.data.grammarForSelection
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

    @Test
    fun aLabelThatOnlyRepeatsTheFormIsDropped() {
        assertEquals("To, toward, about", grammarPointText("对: to, toward, about", "对 duì"))
        assertEquals("〜に、〜に対して", grammarPointText("对：〜に、〜に対して", "对 duì"))
        assertEquals("Would prefer to", grammarPointText("Would rather: would prefer to", "would rather"))
        assertEquals("Question: do comes first", grammarPointText("Question: do comes first", "do … + verb?"))
        assertEquals("A 比 B + adjective: A is more", grammarPointText("A 比 B + adjective: A is more", "比 bǐ"))
        assertEquals("As for A; marks A as the topic", grammarPointText("As for A; marks A as the topic", "は wa"))
    }

    @Test
    fun aPointRepeatedInTheSelectionIsListedOnce() {
        val dui = (0..2).map { GrammarMatch("对 duì", it, it + 1, listOf(GrammarMeaning.ZH_DUI)) }
        assertEquals(listOf(dui.first()), grammarForSelection(dui, 0, 3))
    }
}
