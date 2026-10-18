package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Chinese word list and tables are packaged in the APK and load on Android's runtime. */
class LearnerDataDeviceTest {
    @Test
    fun chineseAnalyzerLoadsFromTheApk() {
        LearnerData.useAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertTrue(ChineseMorphology.awaitReady())

        val line = "我在银行工作了三年。"
        val chinese = LanguageAwareTokenizer.tokenize(line, "zh")
        assertEquals(listOf("我", "在", "银行", "工作", "了", "三年"), chinese.map { it.text })
        assertEquals("yínháng", chinese[2].reading)
        assertEquals(PartOfSpeech.NOUN, chinese[2].partOfSpeech)
        val le = grammarMatches(line, GrammarLanguage.CHINESE).orEmpty().single { GrammarMeaning.ZH_LE_COMPLETED in it.meanings }
        assertEquals(HskLevel.HSK1, le.level)
    }
}
