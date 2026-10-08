package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android's regex engine is ICU, whose word boundaries differ from the JVM's used by unit tests. */
class TokenizerDeviceTest {
    @Test fun wordBeforeZeroWidthSpaceStaysTappableOnAndroidRegexEngine() {
        val text = "there's plenty​ of situations of people​"

        val words = LanguageAwareTokenizer.tokenize(text, "en").map { it.text }

        assertEquals(listOf("there's", "plenty", "of", "situations", "of", "people"), words)
    }
}
