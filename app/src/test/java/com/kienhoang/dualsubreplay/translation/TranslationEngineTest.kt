package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineTest {
    @Test fun googleIsTheDefaultWhereTheBuildOffersIt() {
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine(null, onlineAvailable = true))
        assertEquals(TranslationEngine.GOOGLE_WEB, storedTranslationEngine("unknown", onlineAvailable = true))
    }

    @Test fun onDeviceStaysWhenChosen() {
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("on_device", onlineAvailable = true))
    }

    @Test fun theFdroidBuildNeverTranslatesOnline() {
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine(null, onlineAvailable = false))
        // Even with a choice restored from a backup of the other build.
        assertEquals(TranslationEngine.ON_DEVICE, storedTranslationEngine("google_web", onlineAvailable = false))
    }
}
