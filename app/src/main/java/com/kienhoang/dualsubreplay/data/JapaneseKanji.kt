package com.kienhoang.dualsubreplay.data

import java.io.BufferedReader
import java.io.File
import java.util.Locale

/**
 * One kanji's facts for the word card's kanji page, from KANJIDIC2 with its parts from KanjiVG
 * (`learner-data/ja-kanji.txt`, built by `tools/kanji/build_kanji_data.py`).
 */
internal data class KanjiInfo(
    val literal: String,
    /** Hán Việt readings, for Vietnamese learners (Diệp for 葉). */
    val hanViet: List<String>,
    val strokes: Int,
    /** School grade 1–6, 8 for the other jōyō kanji, 9–10 for kanji used in names. */
    val grade: Int,
    val radical: Int,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val meanings: List<String>,
    val parts: List<KanjiPart>,
) {
    val common: Boolean get() = grade <= LAST_JOYO_GRADE
}

/** A part of a kanji as KanjiVG draws it: 氵 (from 水) is the radical of 海, 毎 gives its sound. */
internal data class KanjiPart(
    val shape: String,
    /** The full form when [shape] is a shortened one, such as 水 for 氵. */
    val original: String?,
    val isRadical: Boolean,
    val givesSound: Boolean,
)

/** One of the 214 Kangxi radicals, with its English name and Vietnamese bộ name and meaning. */
internal data class KanjiRadical(
    val number: Int,
    val forms: List<String>,
    val english: String,
    val vietnameseName: String,
    val vietnameseMeaning: String,
)

/** A kanji's keyword and memory story in one language (`ja-kanji-stories-<language>.txt`). */
internal data class KanjiStory(
    val keyword: String,
    val story: String,
)

private const val LAST_JOYO_GRADE = 8

/** The kanji in [text] in order, each once (kana, Latin letters and punctuation left out). */
internal fun kanjiIn(text: String): List<String> =
    text
        .codePoints()
        .toArray()
        .filter(::isKanji)
        .map { String(Character.toChars(it)) }
        .distinct()

internal fun isKanji(codePoint: Int): Boolean =
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN &&
        codePoint != '々'.code &&
        codePoint != '〆'.code

internal fun parseKanjiLine(line: String): KanjiInfo? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size < KANJI_FIELDS) return null
    return KanjiInfo(
        literal = fields[0],
        hanViet = fields[1].commaList(),
        strokes = fields[2].toIntOrNull() ?: return null,
        grade = fields[3].toIntOrNull() ?: return null,
        radical = fields[5].toIntOrNull() ?: return null,
        onReadings = fields[6].commaList(),
        kunReadings = fields[7].commaList(),
        meanings = fields[8].split(';').filter { it.isNotBlank() },
        parts = fields[9].split(' ').filter { it.isNotBlank() }.map(::parseKanjiPart),
    )
}

/** `氵=水:r` → 氵, from 水, the radical; flags r (radical) and p (gives the sound). */
internal fun parseKanjiPart(text: String): KanjiPart {
    val flags = text.substringAfter(':', "")
    val shapes = text.substringBefore(':')
    return KanjiPart(
        shape = shapes.substringBefore('='),
        original = shapes.substringAfter('=', "").ifEmpty { null },
        isRadical = 'r' in flags,
        givesSound = 'p' in flags,
    )
}

internal fun parseRadicalLine(line: String): KanjiRadical? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size < RADICAL_FIELDS) return null
    return KanjiRadical(
        number = fields[0].toIntOrNull() ?: return null,
        forms = fields[1].split(' ').filter { it.isNotBlank() },
        english = fields[2],
        vietnameseName = fields[3],
        vietnameseMeaning = fields[4],
    )
}

internal fun parseStoryLine(line: String): Pair<String, KanjiStory>? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    if (fields.size < STORY_FIELDS || fields[2].isBlank()) return null
    return fields[0] to KanjiStory(fields[1], fields[2])
}

/** A stroke line of `ja-kanji-strokes.txt`: the kanji, then one SVG path per stroke. */
internal fun parseStrokeLine(line: String): Pair<String, List<String>>? {
    if (line.isBlank() || line.startsWith("#")) return null
    val fields = line.split('\t')
    return if (fields.size < 2) null else fields[0] to fields.drop(1)
}

private fun String.commaList(): List<String> = split(',').filter { it.isNotBlank() }

private const val KANJI_FIELDS = 10
private const val RADICAL_FIELDS = 5
private const val STORY_FIELDS = 3

/**
 * The language of the kanji page's keywords and stories: Vietnamese (with Hán Việt) when the word's
 * meaning or the interface is Vietnamese, English for everyone else.
 */
internal fun kanjiStoryLanguage(
    meaningLanguage: String,
    interfaceLanguage: String,
): String = if (listOf(meaningLanguage, interfaceLanguage).any { it.lowercase(Locale.ROOT).startsWith("vi") }) "vi" else "en"

/**
 * The bundled kanji data, read on first use (call from a background thread). Stroke paths are
 * looked up one kanji at a time so the 2.6 MB stroke file never sits in memory.
 */
internal object KanjiDictionary {
    private val kanji: Map<String, KanjiInfo> by lazy {
        LearnerData.open("ja-kanji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull(::parseKanjiLine).associateBy { it.literal }
        }
    }

    val radicals: Map<Int, KanjiRadical> by lazy {
        LearnerData.open("ja-radicals.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull(::parseRadicalLine).associateBy { it.number }
        }
    }

    /** Every radical form (艹, 氵, 王 …) → its radical. */
    private val radicalForms: Map<String, KanjiRadical> by lazy {
        buildMap { radicals.values.forEach { radical -> radical.forms.forEach { putIfAbsent(it, radical) } } }
    }

    private val stories = mutableMapOf<String, Map<String, KanjiStory>>()

    fun info(literal: String): KanjiInfo? = kanji[literal]

    fun radicalForm(shape: String): KanjiRadical? = radicalForms[shape]

    fun story(
        literal: String,
        language: String,
    ): KanjiStory? = storiesIn(language)[literal] ?: storiesIn("en")[literal]

    /** The keyword for [literal] in [language]: its story's keyword, else KANJIDIC2's first meaning. */
    fun keyword(
        literal: String,
        language: String,
    ): String? = story(literal, language)?.keyword ?: kanji[literal]?.meanings?.firstOrNull()

    @Synchronized
    private fun storiesIn(language: String): Map<String, KanjiStory> =
        stories.getOrPut(language) {
            try {
                LearnerData.open("ja-kanji-stories-$language.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.mapNotNull(::parseStoryLine).toMap()
                }
            } catch (_: java.io.IOException) {
                emptyMap()
            }
        }

    fun strokes(literal: String): List<String> =
        LearnerData.open("ja-kanji-strokes.txt").bufferedReader(Charsets.UTF_8).use { reader -> findStrokes(reader, literal) }
}

internal fun findStrokes(
    reader: BufferedReader,
    literal: String,
): List<String> {
    val prefix = literal + "\t"
    while (true) {
        val line = reader.readLine() ?: return emptyList()
        if (line.startsWith(prefix)) return parseStrokeLine(line)?.second.orEmpty()
    }
}

/** What one part means for the parts list: a radical's name, or a kanji's keyword. */
internal data class KanjiPartMeaning(
    val part: KanjiPart,
    val meaning: String?,
    /** The radical's bộ name or the kanji's Hán Việt, for Vietnamese. */
    val hanViet: String?,
)

/**
 * A part's meaning: a shortened radical (氵 from 水) is named as that radical; a part that is a
 * kanji itself takes its keyword; any other radical shape takes the radical's name.
 */
internal fun kanjiPartMeaning(
    part: KanjiPart,
    language: String,
    dictionary: KanjiDictionary = KanjiDictionary,
): KanjiPartMeaning {
    val vietnamese = language == "vi"
    // KanjiVG's original names the full form only when the shape is one of its radical's shapes
    // (月 from 肉 is "meat"); otherwise the shape keeps its own meaning (儿 is "legs").
    val shortened = part.original?.let { dictionary.radicalForm(it) }?.takeIf { part.shape in it.forms }
    val radical = shortened ?: dictionary.radicalForm(part.shape)
    val kanji = if (shortened == null) dictionary.info(part.shape) else null
    return when {
        kanji != null ->
            KanjiPartMeaning(
                part,
                dictionary.keyword(part.shape, language),
                kanji.hanViet
                    .firstOrNull()
                    ?.uppercase(Locale.ROOT)
                    ?.takeIf { vietnamese },
            )
        radical != null ->
            KanjiPartMeaning(
                part,
                if (vietnamese) radical.vietnameseMeaning else radical.english,
                radical.vietnameseName.uppercase(Locale.ROOT).takeIf { vietnamese },
            )
        else -> KanjiPartMeaning(part, null, null)
    }
}

/**
 * The learner's own memory stories, one per kanji, in `files/kanji-stories/mine.txt` (not
 * preferences, so Reset all settings keeps them). Tabs and line breaks in a story become spaces.
 */
internal class MyKanjiStories(
    private val file: File,
) {
    @Synchronized
    fun all(): Map<String, String> =
        if (!file.exists()) {
            emptyMap()
        } else {
            file
                .readLines(Charsets.UTF_8)
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
        }

    fun get(literal: String): String? = all()[literal]

    @Synchronized
    fun set(
        literal: String,
        story: String,
    ) {
        val clean = story.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        val stories = all().toMutableMap()
        if (clean.isEmpty()) stories.remove(literal) else stories[literal] = clean
        file.parentFile?.mkdirs()
        file.writeText(stories.entries.joinToString("") { "${it.key}\t${it.value}\n" }, Charsets.UTF_8)
    }

    companion object {
        fun inDirectory(filesDir: File): MyKanjiStories = MyKanjiStories(File(filesDir, "kanji-stories/mine.txt"))
    }
}
