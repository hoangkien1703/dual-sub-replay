package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingPreferencesTest {

    @Test fun keepsSupportedOnboardingLanguagePairs() {
        assertEquals("vi" to "ja", normalizedOnboardingLanguages("vi", "ja"))
    }

    @Test fun normalizesRegionalTagsInOnboardingChoices() {
        assertEquals("en" to "zh", normalizedOnboardingLanguages("en-US", "zh-CN"))
        assertEquals("he" to "en", normalizedOnboardingLanguages("iw", "en"))
    }

    @Test fun rejectsUnsupportedOnboardingLanguages() {
        assertNull(normalizedOnboardingLanguages("xx", "ja"))
        assertNull(normalizedOnboardingLanguages("vi", "zz"))
        assertNull(normalizedOnboardingLanguages("", ""))
    }

    @Test fun storedSourcePreferenceFallsBackToAuto() {
        assertEquals("auto", storedSourcePreference(null))
        assertEquals("auto", storedSourcePreference(""))
        assertEquals("auto", storedSourcePreference("klingon"))
        assertEquals("auto", storedSourcePreference("auto"))
    }

    @Test fun storedSourcePreferenceKeepsSupportedCaptionTracks() {
        assertEquals("ja", storedSourcePreference("ja"))
        assertEquals("pt", storedSourcePreference("pt-BR"))
        assertEquals("he", storedSourcePreference("iw"))
    }
}
