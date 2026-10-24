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

class JapaneseKanjiTest {
    @get:Rule val folder = TemporaryFolder()

    private val joyo by lazy {
        LearnerData
            .open("ja-kanji.txt")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines -> lines.mapNotNull(::parseKanjiLine).filter { it.common }.toList() }
    }

    @Test
    fun kanjiInKeepsEachKanjiOnceInOrder() {
        assertEquals(listOf("葉"), kanjiIn("葉っぱ"))
        assertEquals(listOf("日", "本", "語"), kanjiIn("日本の日本語"))
        assertEquals("Repeat marks and kana are not kanji", listOf("人", "切"), kanjiIn("人々は〆切だ"))
        assertEquals(listOf("𠮟"), kanjiIn("𠮟る"))
        assertTrue(kanjiIn("hello さくら").isEmpty())
    }

    @Test
    fun aKanjiLineReadsEveryField() {
        val info = parseKanjiLine("葉\tDiệp\t12\t3\t3\t140\tヨウ\tは\tleaf;plane;lobe\t艹:r 世 木")!!
        assertEquals("葉", info.literal)
        assertEquals(listOf("Diệp"), info.hanViet)
        assertEquals(12, info.strokes)
        assertEquals(3, info.grade)
        assertEquals(140, info.radical)
        assertEquals(listOf("ヨウ"), info.onReadings)
        assertEquals(listOf("は"), info.kunReadings)
        assertEquals(listOf("leaf", "plane", "lobe"), info.meanings)
        assertEquals(listOf("艹", "世", "木"), info.parts.map { it.shape })
        assertTrue(info.common)
        assertFalse(info.copy(grade = 9).common)
        assertNull(parseKanjiLine("# comment"))
        assertNull(parseKanjiLine("葉\tDiệp\ttwelve\t3\t3\t140\t\t\t\t"))
    }

    @Test
    fun aPartReadsItsOriginalAndFlags() {
        assertEquals(KanjiPart("氵", "水", isRadical = true, givesSound = false), parseKanjiPart("氵=水:r"))
        assertEquals(KanjiPart("毎", null, isRadical = false, givesSound = true), parseKanjiPart("毎:p"))
        assertEquals(KanjiPart("木", null, isRadical = false, givesSound = false), parseKanjiPart("木"))
    }

    @Test
    fun radicalStoryAndStrokeLinesParse() {
        val radical = parseRadicalLine("85\t水 氵 氺\twater\tthủy\tnước")!!
        assertEquals(85, radical.number)
        assertEquals(listOf("水", "氵", "氺"), radical.forms)
        assertEquals("nước", radical.vietnameseMeaning)
        assertEquals("葉" to KanjiStory("leaf", "Grass on a tree."), parseStoryLine("葉\tleaf\tGrass on a tree."))
        assertNull("A blank story is no story", parseStoryLine("葉\tleaf\t "))
        val reader = BufferedReader(StringReader("# header\n一\tM10,50h90\n二\tM20,30h70\tM10,70h90\n"))
        assertEquals(listOf("M20,30h70", "M10,70h90"), findStrokes(reader, "二"))
        assertTrue(findStrokes(BufferedReader(StringReader("一\tM10,50h90\n")), "三").isEmpty())
    }

    @Test
    fun vietnameseLearnersGetVietnameseStories() {
        assertEquals("vi", kanjiStoryLanguage("vi", "en"))
        assertEquals("vi", kanjiStoryLanguage("en", "vi"))
        assertEquals("en", kanjiStoryLanguage("es", "ja"))
    }

    @Test
    fun partsAreNamedByTheirRadicalOrTheirOwnKeyword() {
        // 葉 = 艹 (grass radical) + 世 (a kanji of its own) + 木.
        val grass = kanjiPartMeaning(parseKanjiPart("艹=艸:r"), "en")
        assertEquals("grass", grass.meaning)
        assertNull(grass.hanViet)
        val world = kanjiPartMeaning(parseKanjiPart("世"), "vi")
        assertEquals(KanjiDictionary.keyword("世", "vi"), world.meaning)
        assertEquals("THẾ", world.hanViet)
        // 月 from 肉 is the meat radical, but 儿 from 八 keeps its own meaning (legs).
        assertEquals("meat", kanjiPartMeaning(parseKanjiPart("月=肉:r"), "en").meaning)
        assertEquals("legs", kanjiPartMeaning(parseKanjiPart("儿=八"), "en").meaning)
        assertNull(kanjiPartMeaning(parseKanjiPart("マ"), "en").meaning)
    }

    @Test
    fun everyJoyoKanjiHasStrokesPartsAndBothStories() {
        assertEquals(2136, joyo.size)
        val strokes =
            LearnerData.open("ja-kanji-strokes.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull(::parseStrokeLine).toMap()
            }
        for (kanji in joyo) {
            val literal = kanji.literal
            assertEquals("$literal stroke count", kanji.strokes, strokes[literal]?.size)
            assertTrue("$literal English story", KanjiDictionary.story(literal, "en")?.story?.isNotBlank() == true)
            val vietnamese = KanjiDictionary.story(literal, "vi")!!
            // Each Vietnamese story names the kanji's Hán Việt reading in capitals.
            val story = Normalizer.normalize(vietnamese.story, Normalizer.Form.NFC)
            if (kanji.hanViet.isNotEmpty()) {
                assertTrue(
                    "$literal Hán Việt in \"$story\"",
                    kanji.hanViet.any { Normalizer.normalize(it.uppercase(Locale.ROOT), Normalizer.Form.NFC) in story },
                )
            }
        }
    }

    @Test
    fun myStoriesAreSavedPerKanjiAndRemovedWhenBlank() {
        val stories = MyKanjiStories.inDirectory(folder.root)
        assertNull(stories.get("葉"))
        stories.set("葉", "Leaves\ton the\nworld tree")
        stories.set("木", "A tree")
        assertEquals("Leaves on the world tree", stories.get("葉"))
        assertEquals(mapOf("葉" to "Leaves on the world tree", "木" to "A tree"), MyKanjiStories.inDirectory(folder.root).all())
        stories.set("葉", "  ")
        assertNull(stories.get("葉"))
        assertEquals(setOf("木"), stories.all().keys)
    }
}
