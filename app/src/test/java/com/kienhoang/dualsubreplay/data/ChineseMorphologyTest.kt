package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseMorphologyTest {
    private companion object {
        val dictionary by lazy {
            ChineseDictionary.load(LearnerData.open("zh-words.txt"), LearnerData.open("zh-pinyin.txt"), LearnerData.open("zh-t2s.txt"))
        }
    }

    private fun words(line: String) = dictionary.segment(line).filter { it.pos != PUNCTUATION_TAG }

    @Test
    fun splitsASentenceIntoWordsWithPinyin() {
        val words = words("我在银行工作了三年。")
        assertEquals(listOf("我", "在", "银行", "工作", "了", "三年"), words.map { it.surface })
        assertEquals("yínháng", words[2].reading)
        assertEquals("le", words[4].reading)
    }

    @Test
    fun readsTraditionalCharactersAsSimplifiedWithTheSameOffsets() {
        val line = "我們在學校學習。"
        val words = dictionary.segment(line)
        assertEquals(listOf("我们", "在", "学校", "学习", "。"), words.map { it.surface })
        assertEquals(listOf(0, 2, 3, 5, 7), words.map { it.start })
        assertEquals("學校", line.substring(words[2].start, words[2].end))
    }

    @Test
    fun givesEachWordItsUsualPinyin() {
        assertEquals("kàn", dictionary.pinyinOf("看", "v"))
        assertEquals("xǐhuan", dictionary.pinyinOf("喜欢", "v"))
        assertEquals("yīdiǎnr", dictionary.pinyinOf("一点儿", "m"))
        assertEquals("nǚ'ér", dictionary.pinyinOf("女儿", "n"))
        assertEquals("huán", dictionary.pinyinOf("还", "v"))
        assertEquals("hái", dictionary.pinyinOf("还", "d"))
    }

    @Test
    fun splitsWordsThatHideGrammar() {
        assertEquals(listOf("这个", "太", "贵", "了"), words("这个太贵了。").map { it.surface })
        val seen = words("我看过这本书。")
        assertEquals(listOf("v", "ug"), seen.filter { it.surface == "看" || it.surface == "过" }.map { it.pos })
        assertEquals("guo", seen.first { it.surface == "过" }.reading)
    }

    @Test
    fun marksTonesAndJoinsSyllables() {
        assertEquals("zhōng", toneMarked("zhong1"))
        assertEquals("lǜ", toneMarked("lv4"))
        assertEquals("ma", toneMarked("ma5"))
        assertEquals("xī'ān", joinSyllables(listOf("xī", "ān")))
    }
}
