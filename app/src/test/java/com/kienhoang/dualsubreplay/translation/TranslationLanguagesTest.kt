package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLanguagesTest {
    @Test
    fun exposesUniqueMlKitLanguageCodes() {
        assertTrue(TranslationLanguages.all.size > 50)
        assertEquals(
            TranslationLanguages.all.size,
            TranslationLanguages.all.map { it.code }.distinct().size,
        )
        assertEquals("Vietnamese", TranslationLanguages.displayName("vi-VN"))
        assertEquals("Hebrew", TranslationLanguages.displayName("iw"))
    }
}
