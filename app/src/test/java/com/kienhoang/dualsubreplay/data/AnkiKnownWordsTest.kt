package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.ui.ankiKnownWordsLanguages
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiKnownWordsTest {
    /** A stand-in for the tokenizer: words split on spaces, lower-cased, "went" read as "go". */
    private val keys: (String) -> List<String> = { piece ->
        piece.split(' ').filter { it.isNotBlank() }.map { it.lowercase().let { word -> if (word == "went") "go" else word } }
    }

    @Test
    fun theSearchQuotesTheDeckAndPicksTheCardsThatCount() {
        assertEquals("\"deck:Core 2k\" prop:ivl>=21", ankiDeckSearch("Core 2k", AnkiCardsToCount.WELL_KNOWN))
        assertEquals("\"deck:Japanese::Core\" -is:new", ankiDeckSearch("Japanese::Core", AnkiCardsToCount.STUDIED))
        assertEquals("\"deck:My \\\"best\\\" \\*deck\\_\" -is:new", ankiDeckSearch("My \"best\" *deck_", AnkiCardsToCount.STUDIED))
    }

    @Test
    fun aFieldLosesItsHtmlSoundsAndFurigana() {
        assertEquals(listOf("食べる"), ankiFieldPieces("<b>食[た]べる</b>[sound:taberu.mp3]"))
        assertEquals(listOf("漢字"), ankiFieldPieces("<ruby>漢字<rt>かんじ</rt></ruby>"))
        assertEquals(listOf("go", "went"), ankiFieldPieces("go, went"))
        assertEquals(listOf("rock & roll"), ankiFieldPieces("rock&nbsp;&amp;&nbsp;roll"))
        assertEquals(listOf("one", "two"), ankiFieldPieces("one<br>two"))
        assertEquals(emptyList<String>(), ankiFieldPieces("<img src=\"cat.jpg\">"))
    }

    @Test
    fun onlySingleWordsBecomeKnownWords() {
        assertEquals(setOf("go"), ankiFieldKeys("went", keys))
        assertEquals(setOf("go", "run"), ankiFieldKeys("go; run", keys))
        // A sentence or a phrase is not one word of a subtitle line.
        assertEquals(emptySet<String>(), ankiFieldKeys("I go to school", keys))
        assertEquals(emptySet<String>(), ankiFieldKeys("x".repeat(60), keys))
    }

    @Test
    fun theLearnersLanguageComesFirstThenOtherKnownLists() {
        assertEquals(listOf("ja", "en", "es"), ankiKnownWordsLanguages("ja", "en-US", listOf("es", "ja")))
        assertEquals(listOf("zh"), ankiKnownWordsLanguages(null, "zh-Hans", emptyList()))
        assertEquals(emptyList<String>(), ankiKnownWordsLanguages(null, null, emptyList()))
    }
}
