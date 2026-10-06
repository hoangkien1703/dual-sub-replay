package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.TranslationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the top-right icon reports, and which engine translates, after a Google failure. */
class TranslationIssueTest {
    private val google = DualSubUiState(activeVideoId = "video", translationEngine = TranslationEngine.GOOGLE_WEB)

    @Test fun noIconWhileTranslationWorksOrNoVideoIsOpen() {
        assertNull(google.translationIssue())
        assertNull(google.copy(activeVideoId = null, onDeviceFallback = true).translationIssue())
    }

    @Test fun aGoogleFailureSwitchesThisVideoToOnDeviceAndShowsWhy() {
        val fallback =
            google.copy(
                onDeviceFallback = true,
                onlineTranslationFailureDetail = "Google Translate refused the request (HTTP 429).",
            )
        assertFalse(fallback.translatesWithGoogle())
        assertEquals(TranslationEngine.GOOGLE_WEB, fallback.translationEngine)
        assertEquals(TranslationIssue.OnDeviceFallback("Google Translate refused the request (HTTP 429)."), fallback.translationIssue())
    }

    @Test fun aStoppedTranslationOutranksTheFallback() {
        val stopped = google.copy(onDeviceFallback = true, translationError = "The translation model download took too long.")
        assertEquals(TranslationIssue.Unavailable("The translation model download took too long."), stopped.translationIssue())
    }

    @Test fun googleTranslatesOnlyWhenChosenAndNotFallenBack() {
        assertTrue(google.translatesWithGoogle())
        assertFalse(google.copy(translationEngine = TranslationEngine.ON_DEVICE).translatesWithGoogle())
    }
}
