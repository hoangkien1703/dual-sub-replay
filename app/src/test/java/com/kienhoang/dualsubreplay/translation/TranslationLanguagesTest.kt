package com.kienhoang.dualsubreplay.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLanguagesTest {
    @Test
    fun exposesUniqueMlKitLanguageCodes() {
        assertTrue(TranslationLanguages.all.size > 50)
        assertEquals(
            TranslationLanguages.all.size,
            TranslationLanguages.all
                .map { it.code }
                .distinct()
                .size,
        )
        assertEquals("Vietnamese", TranslationLanguages.displayName("vi-VN"))
        assertEquals("Hebrew", TranslationLanguages.displayName("iw"))
    }

    @Test
    fun onDeviceFlagMatchesMlKitModels() {
        TranslationLanguages.all.forEach { option ->
            assertEquals(option.code, TranslateLanguage.fromLanguageTag(option.code) != null, option.onDevice)
        }
        // Every ML Kit language is offered.
        assertEquals(
            TranslateLanguage.getAllLanguages().toSet(),
            TranslationLanguages.all
                .filter { it.onDevice }
                .map { it.code }
                .toSet(),
        )
    }

    @Test
    fun offersOnlineOnlyLanguagesSuchAsAzerbaijani() {
        assertTrue(TranslationLanguages.isSupported("az"))
        assertFalse(TranslationLanguages.hasOnDeviceModel("az"))
        assertTrue(TranslationLanguages.hasOnDeviceModel("tr"))
        assertEquals("Azerbaijani", TranslationLanguages.displayName("az-Latn"))
        assertEquals("az", googleLanguageCode("az"))
    }
}
