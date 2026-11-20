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
    fun simplifiedAndTraditionalChineseAreSeparateEntries() {
        assertEquals("Chinese (Simplified)", TranslationLanguages.displayName("zh"))
        assertEquals("Chinese (Traditional)", TranslationLanguages.displayName(TRADITIONAL_CHINESE))
        // An earlier pick of "Chinese" (zh) stays Simplified.
        listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-HK", "ZH-cn").forEach { code ->
            assertEquals(code, "zh", TranslationLanguages.targetCode(code))
            assertEquals(code, "zh", TranslationLanguages.find(code)?.code)
        }
        listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW", "zh_TW", "zh-hant").forEach { code ->
            assertEquals(code, TRADITIONAL_CHINESE, TranslationLanguages.targetCode(code))
            assertEquals(code, TRADITIONAL_CHINESE, TranslationLanguages.find(code)?.code)
        }
        // Captions and the learning language still use the base language.
        assertEquals("zh", TranslationLanguages.normalize("zh-TW"))
        assertEquals("en", TranslationLanguages.targetCode("en-US"))
        assertEquals("he", TranslationLanguages.targetCode("iw"))
    }

    @Test
    fun traditionalChineseTranslatesOnlineInItsOwnScript() {
        // ML Kit's one Chinese model writes Simplified: Traditional never moves to the device.
        assertTrue(TranslationLanguages.hasOnDeviceModel("zh"))
        assertFalse(TranslationLanguages.hasOnDeviceModel(TRADITIONAL_CHINESE))
        assertFalse(TranslationLanguages.hasOnDeviceModel("zh-Hant"))
        assertEquals("zh-CN", googleLanguageCode("zh"))
        assertEquals("zh-TW", googleLanguageCode(TRADITIONAL_CHINESE))
        assertEquals("zh-Hans", TranslationLanguages.youTubeTranslationCode("zh"))
        assertEquals("zh-Hant", TranslationLanguages.youTubeTranslationCode(TRADITIONAL_CHINESE))
        assertEquals("vi", TranslationLanguages.youTubeTranslationCode("vi"))
        assertEquals("Simplified Chinese", TranslationLanguages.englishTargetName("zh"))
        assertEquals("Traditional Chinese", TranslationLanguages.englishTargetName(TRADITIONAL_CHINESE))
        assertEquals("Vietnamese", TranslationLanguages.englishTargetName("vi"))
    }

    @Test
    fun findsChineseByTheNamesPeopleUse() {
        assertEquals("zh", TranslationLanguages.findByName("Chinese")?.code)
        assertEquals("zh", TranslationLanguages.findByName("simplified chinese")?.code)
        assertEquals("zh", TranslationLanguages.findByName("Chinese (Simplified)")?.code)
        assertEquals(TRADITIONAL_CHINESE, TranslationLanguages.findByName(" Traditional Chinese ")?.code)
        assertEquals(TRADITIONAL_CHINESE, TranslationLanguages.findByName("Chinese (Traditional)")?.code)
        assertEquals("vi", TranslationLanguages.findByName("Vietnamese")?.code)
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
