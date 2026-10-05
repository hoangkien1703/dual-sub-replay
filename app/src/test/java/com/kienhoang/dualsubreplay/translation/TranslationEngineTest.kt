package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineTest {
    @Test fun onDeviceIsTheDefault() {
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine(null, onlineAvailable = true))
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("unknown", onlineAvailable = true))
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("on_device", onlineAvailable = true))
    }

    @Test fun googleIsUsedOnlyWhenChosenAndAvailable() {
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine("google_web", onlineAvailable = true))
        // The F-Droid build never translates online, even with a choice restored from a backup.
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("google_web", onlineAvailable = false))
    }
}
