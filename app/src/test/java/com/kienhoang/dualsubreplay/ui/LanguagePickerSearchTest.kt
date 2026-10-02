package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LanguagePickerSearchTest {
    @Test
    fun englishInterfaceKeepsTheCatalogAndYouTubeNames() {
        assertEquals("Japanese", localizedLanguageLabel("ja", "Japanese", Locale.ENGLISH))
        assertEquals("English (United Kingdom)", localizedLanguageLabel("en", "English (United Kingdom)", Locale.ENGLISH))
    }

    @Test
    fun otherInterfacesOnlyRenameCatalogNames() {
        val vietnamese = Locale.forLanguageTag("vi")
        assertEquals("English (United Kingdom)", localizedLanguageLabel("en", "English (United Kingdom)", vietnamese))
        assertEquals("Auto (recommended)", localizedLanguageLabel("auto", "Auto (recommended)", vietnamese))
    }

    @Test
    fun searchMatchesTheShownNameTheEnglishNameAndTheCode() {
        val japanese = LanguageChoice("ja", "Japanese")
        assertTrue(languageChoiceMatches(japanese, "Tiếng Nhật", ""))
        assertTrue(languageChoiceMatches(japanese, "Tiếng Nhật", " nhật "))
        assertTrue(languageChoiceMatches(japanese, "Tiếng Nhật", "japan"))
        assertTrue(languageChoiceMatches(japanese, "Tiếng Nhật", "JA"))
        assertFalse(languageChoiceMatches(japanese, "Tiếng Nhật", "korean"))
    }
}
