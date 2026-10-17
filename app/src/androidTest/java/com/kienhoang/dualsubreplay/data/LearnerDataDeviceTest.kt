package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The English model and Chinese word list are packaged in the APK and load on Android's runtime. */
class LearnerDataDeviceTest {
    @Test
    fun englishAndChineseAnalyzersLoadFromTheApk() {
        LearnerData.useAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertTrue(EnglishMorphology.awaitReady())
        assertTrue(ChineseMorphology.awaitReady())

        val english = LanguageAwareTokenizer.tokenize("It's going to rain.", "en")
        assertEquals(PartOfSpeech.VERB, english.first { it.text == "rain" }.partOfSpeech)
        val line = "我在银行工作了三年。"
        val chinese = LanguageAwareTokenizer.tokenize(line, "zh")
        assertEquals(listOf("我", "在", "银行", "工作", "了", "三年"), chinese.map { it.text })
        assertEquals("yínháng", chinese[2].reading)
        assertTrue(grammarMatches(line, GrammarLanguage.CHINESE).orEmpty().any { GrammarMeaning.ZH_LE_COMPLETED in it.meanings })
    }
}
