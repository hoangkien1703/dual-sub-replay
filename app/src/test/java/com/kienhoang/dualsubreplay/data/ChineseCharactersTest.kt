package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.StringReader
import java.text.Normalizer
import java.util.Locale

class ChineseCharactersTest {
    @get:Rule val folder = TemporaryFolder()

    private val syllabus by lazy {
        LearnerData
            .open("zh-hanzi.txt")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines -> lines.mapNotNull(::parseHanziLine).filter { it.level > 0 && !it.traditional }.toList() }
    }

    @Test
    fun hanziInKeepsEachCharacterOnceInOrder() {
        assertEquals(listOf("树", "叶"), hanziIn("树叶"))
        assertEquals(listOf("我", "的"), hanziIn("我的我的 abc"))
        assertTrue(hanziIn("hello 〇").isEmpty())
    }

    @Test
    fun aHanziLineReadsEveryField() {
        val info = parseHanziLine("妈\tmā\t6\t1\t媽\t女\tmother, mama\t女:rm 马:s\t妈妈 大妈\ts")!!
        assertEquals("妈", info.literal)
        assertEquals(listOf("mā"), info.pinyin)
        assertEquals(6, info.strokes)
        assertEquals(1, info.level)
        assertEquals(listOf("媽"), info.otherForms)
        assertEquals("女", info.radical)
        assertEquals(listOf("mother, mama"), info.meanings)
        assertEquals(HanziPart("女", isRadical = true, givesMeaning = true, givesSound = false), info.parts[0])
        assertEquals(HanziPart("马", isRadical = false, givesMeaning = false, givesSound = true), info.parts[1])
        assertEquals(listOf("妈妈", "大妈"), info.words)
        assertFalse(info.traditional)
        assertTrue(parseHanziLine("葉\tyè\t13\t4\t叶\t艹\tleaf\t艹:r 枼\t\tt")!!.traditional)
        assertNull(parseHanziLine("# comment"))
        assertNull(parseHanziLine("妈\tmā\tsix\t1\t\t女\t\t\t\ts"))
    }

    @Test
    fun strokeLinesAndCentreLinesParse() {
        assertEquals(listOf(314 to 60, 280 to 52, 290 to 70), parseMedian("314,60 -34,-8 10,18"))
        val reader = BufferedReader(StringReader("# header\n一\tM0 0h9|0,0 9,0\n二\tM0 0h9|0,0 9,0\tM0 9h9|0,9 9,0\n"))
        val two = findHanziStrokes(reader, "二")!!
        assertEquals(listOf("M0 0h9", "M0 9h9"), two.outlines)
        assertEquals(listOf(0 to 9, 9 to 9), two.medians[1])
        assertNull(findHanziStrokes(BufferedReader(StringReader("一\tM0 0h9|0,0 9,0\n")), "三"))
        assertNull("A stroke needs its centre line", parseHanziStrokeLine("一\tM0 0h9"))
    }

    @Test
    fun aWordsReadingGivesEachCharacterItsPinyin() {
        assertEquals(mapOf("树" to "shù", "叶" to "yè"), pinyinInWord("树叶", "shù yè"))
        assertTrue("A reading that does not split per character is not used", pinyinInWord("树叶", "shùyè").isEmpty())
        assertTrue(pinyinInWord("树叶", null).isEmpty())
    }

    @Test
    fun partsAreNamedByTheirRadicalOrTheirOwnKeywordWithTheSound() {
        // 说 = 讠 (the words radical) + 兑; 妈 = 女 + 马, which gives the sound mǎ.
        val words = hanziPartMeaning(parseHanziPart("讠:rm"), "en")
        assertEquals("words", words.meaning)
        assertNull(words.sound)
        assertEquals("NGÔN", hanziPartMeaning(parseHanziPart("讠:rm"), "vi").hanViet)
        val horse = hanziPartMeaning(parseHanziPart("马:s"), "en")
        assertEquals(HanziDictionary.keyword("马", "en"), horse.meaning)
        assertEquals("mǎ", horse.sound)
        assertEquals("MÃ", hanziPartMeaning(parseHanziPart("马:s"), "vi").hanViet)
        assertNull(hanziPartMeaning(parseHanziPart("马:s"), "en").hanViet)
    }

    @Test
    fun aTraditionalFormBorrowsItsSimplifiedFormsStory() {
        val simplified = HanziDictionary.story("叶", "vi")!!
        val traditional = HanziDictionary.story("葉", "vi")!!
        assertNull(simplified.form)
        assertEquals("叶", traditional.form)
        assertEquals(simplified.story, traditional.story)
        assertEquals(listOf("Diệp"), HanziDictionary.hanViet("葉"))
    }

    @Test
    fun everyHskCharacterHasStrokesAndBothStoriesFromItsOwnParts() {
        assertEquals(3000, syllabus.size)
        val strokes =
            LearnerData.open("zh-hanzi-strokes.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull(::parseHanziStrokeLine).toMap()
            }
        for (info in syllabus) {
            val literal = info.literal
            val drawn = strokes[literal]
            assertEquals("$literal stroke count", info.strokes, drawn?.outlines?.size)
            assertTrue("$literal centre lines", drawn!!.medians.all { it.size >= 2 })
            val allowed = info.parts.map { it.shape }.toSet() + literal
            for (language in listOf("en", "vi")) {
                val story = HanziDictionary.story(literal, language)
                assertTrue("$literal $language story", story?.story?.isNotBlank() == true && story.keyword.isNotBlank())
                // A story only shows Chinese characters the page also shows: the character and its parts.
                val strangers = hanziIn(story!!.story).filter { it !in allowed }
                assertTrue("$literal $language story uses $strangers", strangers.isEmpty())
            }
            val hanViet = HanziDictionary.hanViet(literal)
            if (hanViet.isNotEmpty()) {
                val story = Normalizer.normalize(HanziDictionary.story(literal, "vi")!!.story, Normalizer.Form.NFC)
                assertTrue(
                    "$literal Hán Việt in \"$story\"",
                    hanViet.any { Normalizer.normalize(it.uppercase(Locale.ROOT), Normalizer.Form.NFC) in story },
                )
            }
        }
    }

    @Test
    fun myHanziStoriesAreKeptApartFromKanjiStories() {
        myHanziStories(folder.root).set("叶", "A mouth on ten leaves")
        MyKanjiStories.inDirectory(folder.root).set("葉", "Grass on the world tree")
        assertEquals(mapOf("叶" to "A mouth on ten leaves"), myHanziStories(folder.root).all())
        assertNull(MyKanjiStories.inDirectory(folder.root).get("叶"))
    }
}
