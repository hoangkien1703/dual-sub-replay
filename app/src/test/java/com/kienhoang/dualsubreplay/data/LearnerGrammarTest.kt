package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnerGrammarTest {
    @Test
    fun picksTheGrammarByLanguage() {
        assertEquals(GrammarLanguage.ENGLISH, grammarLanguage("Hello there", "en"))
        assertEquals(GrammarLanguage.ENGLISH, grammarLanguage("Hello there", "en-GB"))
        assertEquals(GrammarLanguage.CHINESE, grammarLanguage("你好", "zh-Hans"))
        assertEquals(GrammarLanguage.CHINESE, grammarLanguage("你好", "zh_TW"))
        assertEquals(GrammarLanguage.JAPANESE, grammarLanguage("こんにちは", "ja"))
        assertNull(grammarLanguage("Hola", "es"))
        assertNull(grammarLanguage("Hello", null))
        assertNull(grammarLanguage("Hello", "eng"))
    }

    @Test
    fun levelsAreNamedTheWayLearnersKnowThem() {
        assertEquals("N3", JlptLevel.N3.code)
        assertEquals("HSK 2", HskLevel.HSK2.code)
        assertEquals("B1", CefrLevel.B1.code)
    }
}
