package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineTest {
    @Test fun googleIsTheDefault() {
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine(null))
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine("unknown"))
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine("google_web"))
    }

    @Test fun onDeviceStaysWhenChosen() {
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("on_device"))
    }

    @Test fun theSavedSettingsMakeUpOneTranslatorChoice() {
        val google = TranslationEngine.GOOGLE_WEB
        assertEquals(SubtitleTranslator.GOOGLE, subtitleTranslator(google, ai = false, aiOnly = true))
        assertEquals(SubtitleTranslator.AI_WITH_GOOGLE, subtitleTranslator(google, ai = true, aiOnly = false))
        assertEquals(SubtitleTranslator.AI_ONLY, subtitleTranslator(google, ai = true, aiOnly = true))
        assertEquals(SubtitleTranslator.ON_DEVICE, subtitleTranslator(TranslationEngine.ON_DEVICE, ai = true, aiOnly = true))
    }

    @Test fun everyChoiceSavesBackToItself() {
        SubtitleTranslator.entries.forEach { choice ->
            assertEquals(choice, subtitleTranslator(choice.engine, choice.usesAi, choice == SubtitleTranslator.AI_ONLY))
        }
    }
}
