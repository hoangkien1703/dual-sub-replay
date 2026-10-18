package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnerGrammarTest {
    @Test
    fun picksTheGrammarByLanguage() {
        assertEquals(GrammarLanguage.CHINESE, grammarLanguage("你好", "zh-Hans"))
        assertEquals(GrammarLanguage.CHINESE, grammarLanguage("你好", "zh_TW"))
        assertEquals(GrammarLanguage.JAPANESE, grammarLanguage("こんにちは", "ja"))
        assertNull(grammarLanguage("Hello there", "en"))
        assertNull(grammarLanguage("Hola", "es"))
        assertNull(grammarLanguage("Hello", null))
        assertNull(grammarLanguage("Hello", "eng"))
        // A line of hanzi whose language is unknown keeps the Japanese heuristic, as before.
        assertNull(grammarLanguage("银行", null))
    }

    @Test
    fun levelsAreNamedTheWayLearnersKnowThem() {
        assertEquals("N3", JlptLevel.N3.code)
        assertEquals("HSK 2", HskLevel.HSK2.code)
        assertEquals("HSK 7–9", HskLevel.HSK7_9.code)
    }
}
