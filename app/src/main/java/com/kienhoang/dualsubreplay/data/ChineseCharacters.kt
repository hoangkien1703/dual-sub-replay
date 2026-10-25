package com.kienhoang.dualsubreplay.data

import java.io.BufferedReader
import java.io.File

/**
 * One Chinese character's facts for the word card's character page, from Make Me a Hanzi's
 * dictionary with HSK 3.0 levels and words (`learner-data/zh-hanzi.txt`, built by
 * `tools/hanzi/build_hanzi_data.py`).
 */
internal data class HanziInfo(
    val literal: String,
    val pinyin: List<String>,
    val strokes: Int,
    /** HSK 3.0 level 1–6, 7 for the 7–9 band, 0 for a part that is not in the syllabus. */
    val level: Int,
    /** The traditional forms of a simplified character (叶 → 葉), or the simplified one of a traditional form. */
    val otherForms: List<String>,
    val radical: String,
    val meanings: List<String>,
    val parts: List<HanziPart>,
    /** Common HSK words with this character (simplified characters only). */
    val words: List<String>,
    /** A traditional form of a syllabus character (葉 for 叶), not one of the syllabus's own. */
    val traditional: Boolean = false,
)

/** A part of a character: 女 gives 妈 its meaning, 马 (mǎ) its sound. */
internal data class HanziPart(
    val shape: String,
    val isRadical: Boolean,
    val givesMeaning: Boolean,
    val givesSound: Boolean,
)

/** A character's strokes in stroke order, in a 512 × 512 box with y pointing down. */
internal data class HanziStrokes(
    /** Each stroke's brush outline, an SVG path. */
    val outlines: List<String>,
    /** Each stroke's centre line, the way the brush moves. */
    val medians: List<List<Pair<Int, Int>>>,
)

/** A memory story; [form] is the simplified character it was written for when a traditional form borrows it. */
internal data class HanziStory(
    val keyword: String,
    val story: String,
    val form: String? = null,
)

internal const val HSK_HIGHEST_BAND = 7

/** The Chinese characters in [text] in order, each once. */
internal fun hanziIn(text: String): List<String> =
    text
        .codePoints()
        .toArray()
        .filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN && it != '〇'.code }
        .map { String(Character.toChars(it)) }
        .distinct()

internal fun parseHanziLine(line: String): HanziInfo? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size < HANZI_FIELDS) return null
    return HanziInfo(
        literal = fields[0],
        pinyin = fields[1].split(',').filter { it.isNotBlank() },
        strokes = fields[2].toIntOrNull() ?: return null,
        level = fields[3].toIntOrNull() ?: return null,
        otherForms = fields[4].split(',').filter { it.isNotBlank() },
        radical = fields[5],
        meanings = fields[6].split(';').filter { it.isNotBlank() },
        parts = fields[7].split(' ').filter { it.isNotBlank() }.map(::parseHanziPart),
        words = fields[8].split(' ').filter { it.isNotBlank() },
        traditional = fields[9] == "t",
    )
}

/** `马:s` → 马, the part that gives the sound; flags r (radical), m (meaning) and s (sound). */
internal fun parseHanziPart(text: String): HanziPart {
    val flags = text.substringAfter(':', "")
    return HanziPart(
        shape = text.substringBefore(':'),
        isRadical = 'r' in flags,
        givesMeaning = 'm' in flags,
        givesSound = 's' in flags,
    )
}

/** A line of `zh-hanzi-strokes.txt`: the character, then each stroke as `outline|x,y dx,dy …`. */
internal fun parseHanziStrokeLine(line: String): Pair<String, HanziStrokes>? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size < 2) return null
    val strokes = fields.drop(1).map { it.split('|', limit = 2) }
    if (strokes.any { it.size < 2 }) return null
    return fields[0] to HanziStrokes(strokes.map { it[0] }, strokes.map { parseMedian(it[1]) })
}

/** `314,60 -34,-8` → (314, 60), (280, 52): the first point, then each step from the point before. */
internal fun parseMedian(text: String): List<Pair<Int, Int>> {
    var x = 0
    var y = 0
    return text.split(' ').filter { it.isNotBlank() }.mapIndexed { index, step ->
        val dx = step.substringBefore(',').toInt()
        val dy = step.substringAfter(',').toInt()
        x = if (index == 0) dx else x + dx
        y = if (index == 0) dy else y + dy
        x to y
    }
}

internal fun findHanziStrokes(
    reader: BufferedReader,
    literal: String,
): HanziStrokes? {
    val prefix = literal + "\t"
    while (true) {
        val line = reader.readLine() ?: return null
        if (line.startsWith(prefix)) return parseHanziStrokeLine(line)?.second
    }
}

private const val HANZI_FIELDS = 10

/**
 * The pinyin each character of a word has in it: 树叶 read "shù yè" gives 叶 → yè, so the page
 * shows the reading the word uses first. Empty when the reading does not split one per character.
 */
internal fun pinyinInWord(
    word: String,
    reading: String?,
): Map<String, String> {
    val characters = word.codePoints().toArray().map { String(Character.toChars(it)) }
    val syllables =
        reading
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    if (syllables.size != characters.size) return emptyMap()
    return characters.zip(syllables).toMap()
}

/**
 * The bundled Chinese character data, read on first use (call from a background thread). Stroke
 * data is looked up one character at a time so the 7 MB stroke file never sits in memory.
 */
internal object HanziDictionary {
    private val hanzi: Map<String, HanziInfo> by lazy {
        LearnerData.open("zh-hanzi.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull(::parseHanziLine).associateBy { it.literal }
        }
    }

    private val hanViet: Map<String, List<String>> by lazy {
        LearnerData.open("zh-hanviet.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t', limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to it[1].split(',').filter(String::isNotBlank) }
        }
    }

    private val stories = mutableMapOf<String, Map<String, KanjiStory>>()

    fun info(literal: String): HanziInfo? = hanzi[literal]

    fun hanViet(literal: String): List<String> = hanViet[literal].orEmpty()

    /**
     * The story for [literal] in [language] (English when there is none), or for a traditional
     * form without its own story, the story of its simplified form.
     */
    fun story(
        literal: String,
        language: String,
    ): HanziStory? {
        own(literal, language)?.let { return HanziStory(it.keyword, it.story) }
        val info = hanzi[literal]?.takeIf { it.traditional }
        val simplified = info?.otherForms?.firstOrNull()
        return simplified?.let { form -> own(form, language)?.let { HanziStory(it.keyword, it.story, form) } }
    }

    private fun own(
        literal: String,
        language: String,
    ): KanjiStory? = storiesIn(language)[literal] ?: storiesIn("en")[literal]

    /** The keyword for [literal] in [language]: its story's keyword, else the first meaning's first word. */
    fun keyword(
        literal: String,
        language: String,
    ): String? =
        story(literal, language)?.keyword ?: hanzi[literal]
            ?.meanings
            ?.firstOrNull()
            ?.substringBefore(',')
            ?.trim()

    @Synchronized
    private fun storiesIn(language: String): Map<String, KanjiStory> =
        stories.getOrPut(language) {
            try {
                LearnerData.open("zh-hanzi-stories-$language.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.mapNotNull(::parseStoryLine).toMap()
                }
            } catch (_: java.io.IOException) {
                emptyMap()
            }
        }

    fun strokes(literal: String): HanziStrokes? =
        LearnerData.open("zh-hanzi-strokes.txt").bufferedReader(Charsets.UTF_8).use { reader -> findHanziStrokes(reader, literal) }
}

/** What one part means for the parts list: a character's keyword, or a radical's name. */
internal data class HanziPartMeaning(
    val part: HanziPart,
    val meaning: String?,
    /** The part's Hán Việt or the radical's bộ name, for Vietnamese. */
    val hanViet: String?,
    /** The part's pinyin when it gives the sound (马 → mǎ). */
    val sound: String?,
)

/**
 * A part's meaning: a part that is a character of its own takes its keyword (with its Hán Việt
 * for Vietnamese); a radical shape such as 讠 or 钅 takes the radical's name.
 */
internal fun hanziPartMeaning(
    part: HanziPart,
    language: String,
    dictionary: HanziDictionary = HanziDictionary,
    radicals: KanjiDictionary = KanjiDictionary,
): HanziPartMeaning {
    val vietnamese = language == "vi"
    val info = dictionary.info(part.shape)
    val radical = radicals.radicalForm(part.shape)
    val sound = info?.pinyin?.firstOrNull()?.takeIf { part.givesSound }
    // A radical's own shape (女, 口) is named by its keyword; a shortened form (讠, 氵) by the radical.
    val shortened = radical != null && radical.forms.firstOrNull() != part.shape
    return when {
        info != null && !(shortened && info.level == 0) ->
            HanziPartMeaning(
                part,
                dictionary.keyword(part.shape, language),
                dictionary
                    .hanViet(part.shape)
                    .firstOrNull()
                    ?.uppercase(java.util.Locale.ROOT)
                    ?.takeIf { vietnamese },
                sound,
            )
        radical != null ->
            HanziPartMeaning(
                part,
                if (vietnamese) radical.vietnameseMeaning else radical.english,
                radical.vietnameseName.uppercase(java.util.Locale.ROOT).takeIf { vietnamese },
                sound,
            )
        else -> HanziPartMeaning(part, null, null, sound)
    }
}

/** The learner's own Chinese character stories, kept apart from the kanji ones (meanings can differ). */
internal fun myHanziStories(filesDir: File): MyKanjiStories = MyKanjiStories(File(filesDir, "kanji-stories/mine-zh.txt"))
