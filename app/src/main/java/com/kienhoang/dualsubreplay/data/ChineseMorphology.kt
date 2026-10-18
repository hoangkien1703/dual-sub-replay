package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import kotlin.math.ln

/**
 * Chinese words, their parts of speech and pinyin, from data bundled in the APK: jieba's word list
 * (MIT) and HanLP's pinyin and traditional-to-simplified tables (Apache-2.0). Loads on a
 * background thread the first time Chinese is shown; until then [analyze] and [morphemes] return
 * null and callers split one character per word.
 */
internal object ChineseMorphology {
    private val dictionary =
        BackgroundLoaded("chinese-words") {
            ChineseDictionary.load(
                words = LearnerData.open("zh-words.txt"),
                pinyin = LearnerData.open("zh-pinyin.txt"),
                simplified = LearnerData.open("zh-t2s.txt"),
            )
        }

    val revision: StateFlow<Int> get() = dictionary.revision

    fun warmUp() = dictionary.warmUp()

    fun awaitReady(timeoutMs: Long = 60_000): Boolean = dictionary.awaitReady(timeoutMs)

    /** Learner words of [text], or null while the word list loads. */
    fun analyze(text: String): List<AnalyzedToken>? = dictionary.get()?.let { chineseWords(it, text) }

    /** The words grammar is read from (simplified surface, jieba tag, pinyin), or null while the word list loads. */
    fun morphemes(text: String): List<Morpheme>? = dictionary.get()?.let { it.segment(text) }
}

internal fun chineseWords(
    dictionary: ChineseDictionary,
    text: String,
): List<AnalyzedToken> =
    dictionary
        .segment(text)
        .filter { it.pos != PUNCTUATION_TAG }
        .map { word ->
            AnalyzedToken(
                text = text.substring(word.start, word.end),
                startIndex = word.start,
                endIndex = word.end,
                partOfSpeech = chinesePartOfSpeech(word.pos),
                reading = word.reading.takeIf { it != "*" },
            )
        }

internal const val PUNCTUATION_TAG = "w"

/** The app's part of speech for a jieba tag (ICTCLAS style: n, v, a, d, p, c, u…, y, q…). */
internal fun chinesePartOfSpeech(tag: String): PartOfSpeech =
    when {
        tag == "eng" || tag == "x" || tag == PUNCTUATION_TAG -> PartOfSpeech.OTHER
        tag.startsWith("v") -> PartOfSpeech.VERB
        tag.startsWith("a") || tag == "b" || tag == "z" -> PartOfSpeech.ADJECTIVE
        tag.startsWith("d") -> PartOfSpeech.ADVERB
        tag.startsWith("r") -> PartOfSpeech.PRONOUN
        tag == "c" -> PartOfSpeech.CONJUNCTION
        tag == "p" -> PartOfSpeech.PREPOSITION
        tag.startsWith("u") || tag == "y" || tag == "q" || tag == "k" -> PartOfSpeech.PARTICLE
        tag.startsWith("n") || tag == "s" || tag == "t" || tag == "f" || tag == "j" -> PartOfSpeech.NOUN
        else -> PartOfSpeech.OTHER
    }

/**
 * jieba's word list kept compactly (one string of all words in sorted order, with offset,
 * frequency and tag arrays), the pinyin table, and the traditional-to-simplified table.
 */
internal class ChineseDictionary private constructor(
    private val words: String,
    private val offsets: IntArray,
    private val frequencies: IntArray,
    private val tagIds: ByteArray,
    private val tagNames: List<String>,
    private val pinyin: Map<String, String>,
    private val simplified: Map<Char, Char>,
) {
    private val logTotal = ln(frequencies.sumOf { it.toLong() }.toDouble())
    private val maxWordLength = (0 until frequencies.size).maxOf { offsets[it + 1] - offsets[it] }

    /** The words of [text]: simplified surface (same length as the original), tag, base form and pinyin. */
    fun segment(text: String): List<Morpheme> {
        val simple = buildString(text.length) { text.forEach { append(simplified[it] ?: it) } }
        val result = mutableListOf<Morpheme>()
        var i = 0
        while (i < simple.length) {
            val ch = simple[i]
            when {
                ch.isWhitespace() -> i++
                isWordChar(ch) -> {
                    var end = i
                    while (end < simple.length && isWordChar(simple[end])) end++
                    segmentBlock(simple, i, end, result)
                    i = end
                }
                else -> {
                    result += Morpheme(simple.substring(i, i + 1), i, PUNCTUATION_TAG, detail = PUNCTUATION_TAG)
                    i++
                }
            }
        }
        return result
    }

    /** jieba's dictionary method: the most probable path through every word the list knows. */
    private fun segmentBlock(
        text: String,
        start: Int,
        end: Int,
        result: MutableList<Morpheme>,
    ) {
        val length = end - start
        val best = DoubleArray(length + 1)
        val next = IntArray(length + 1)
        for (i in length - 1 downTo 0) {
            best[i] = Double.NEGATIVE_INFINITY
            var j = i + 1
            while (j <= minOf(length, i + maxWordLength)) {
                val found = lowerBound(text, start + i, start + j)
                if (found < 0) break
                val score = if (found.isExact()) ln(frequencies[found.index].toDouble()) - logTotal + best[j] else Double.NEGATIVE_INFINITY
                if (score > best[i]) {
                    best[i] = score
                    next[i] = j
                }
                j++
            }
            if (best[i] == Double.NEGATIVE_INFINITY) {
                best[i] = -logTotal + best[i + 1]
                next[i] = i + 1
            }
        }
        var i = 0
        while (i < length) {
            var j = next[i]
            // Letters and digits the list does not know stay together: iPhone, 2024.
            if (j == i + 1 && isLatinOrDigit(text[start + i]) && lookup(text, start + i, start + j) < 0) {
                while (j < length && isLatinOrDigit(text[start + j]) && lookup(text, start + j, start + j + 1) < 0) j++
            }
            val word = text.substring(start + i, start + j)
            val parts = splitParts(word)
            if (parts != null) {
                var at = start + i
                parts.forEach { (part, tag) ->
                    result += word(part, at, tag)
                    at += part.length
                }
            } else {
                val index = lookup(text, start + i, start + j)
                result +=
                    word(word, start + i, TAG_OVERRIDES[word] ?: if (index >= 0) tagNames[tagIds[index].toInt()] else unknownTag(word))
            }
            i = j
        }
    }

    /**
     * The list keeps some phrases as one word that grammar needs apart: a verb and its 过 or 着
     * (看过), 太 or 很 and an adjective (太贵), and a few others (在家, 我会). Null for other words.
     */
    private fun splitParts(word: String): List<Pair<String, String>>? =
        when {
            word in SPLITS -> SPLITS.getValue(word)
            word in ASPECT_SPLITS -> listOf(word.substring(0, 1) to "v", word.substring(1) to if (word.last() == '过') "ug" else "uz")
            word.length == 2 && (word[0] == '太' || word[0] == '很') && word[1] in DEGREE_ADJECTIVES ->
                listOf(word.substring(0, 1) to "d", word.substring(1) to "a")
            else -> null
        }

    private fun word(
        text: String,
        start: Int,
        tag: String,
    ) = Morpheme(text, start, tag, detail = tag.take(1), baseForm = text, reading = pinyinOf(text, tag))

    private fun unknownTag(word: String): String =
        when {
            word.all { it.isDigit() } -> "m"
            word.all(::isLatinOrDigit) -> "eng"
            else -> "x"
        }

    /** Tone-marked pinyin of [word] (yínháng), or `*` for words that are not Chinese. */
    fun pinyinOf(
        word: String,
        tag: String,
    ): String {
        if (word.none(::isHan)) return "*"
        PARTICLE_READINGS[word]?.takeIf { tag.startsWith("u") || tag == "y" || word !in setOf("得", "地", "着", "过") }?.let { return it }
        val numbered =
            WORD_READINGS[word]?.split(',')
                ?: pinyin[word]?.takeIf { word.length > 1 }?.split(',')?.takeIf { it.size == word.length }
                ?: word.mapIndexed { index, ch -> characterReading(word, index, tag) }
        val erhua = word.length > 1 && word.last() == '儿' && word !in CHILD_WORDS
        val marked = (if (erhua) numbered.dropLast(1) else numbered).map(::toneMarked)
        return joinSyllables(marked) + if (erhua) "r" else ""
    }

    /** One character's reading: its commonest one, the one its part of speech needs, or neutral at a word's end. */
    private fun characterReading(
        word: String,
        index: Int,
        tag: String,
    ): String {
        val ch = word[index]
        val reading =
            TAGGED_READINGS["$ch${tag.take(1)}"].takeIf { word.length == 1 } ?: pinyin[ch.toString()]?.substringBefore(',') ?: return ""
        val last = index > 0 && index == word.length - 1
        // 桌子, 我们, 怎么, and doubled kinship words and verbs: 妈妈, 看看.
        val neutral =
            last && (ch in NEUTRAL_SUFFIXES || (word.length == 2 && word[0] == ch && (tag.startsWith("n") || tag.startsWith("v"))))
        return if (neutral) reading.trimEnd { it.isDigit() } + "5" else reading
    }

    /** The index of the word text[start, end) in the list, or -1. */
    private fun lookup(
        text: String,
        start: Int,
        end: Int,
    ): Int = lowerBound(text, start, end).takeIf { it >= 0 && it.isExact() }?.index ?: -1

    /**
     * Where text[start, end) sits in the sorted list: an encoded index ([isExact] when it is a whole
     * word), or -1 when no word even starts with it, which ends the search for longer words.
     */
    private fun lowerBound(
        text: String,
        start: Int,
        end: Int,
    ): Int {
        var low = 0
        var high = frequencies.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareWord(mid, text, start, end) < 0) low = mid + 1 else high = mid
        }
        if (low >= frequencies.size) return -1
        val wordStart = offsets[low]
        val wordLength = offsets[low + 1] - wordStart
        val keyLength = end - start
        if (wordLength < keyLength || !words.regionMatches(wordStart, text, start, keyLength)) return -1
        return if (wordLength == keyLength) low or EXACT else low
    }

    private fun compareWord(
        index: Int,
        text: String,
        start: Int,
        end: Int,
    ): Int {
        val wordStart = offsets[index]
        val wordEnd = offsets[index + 1]
        var a = wordStart
        var b = start
        while (a < wordEnd && b < end) {
            val difference = words[a] - text[b]
            if (difference != 0) return difference
            a++
            b++
        }
        return (wordEnd - wordStart) - (end - start)
    }

    private fun Int.isExact() = this and EXACT != 0

    private val Int.index get() = this and EXACT.inv()

    companion object {
        private const val EXACT = 1 shl 30

        /** Reads the sorted word list (`word frequency tag` per line), the pinyin table and the t2s table. */
        fun load(
            words: InputStream,
            pinyin: InputStream,
            simplified: InputStream,
        ): ChineseDictionary {
            val text = StringBuilder()
            val offsets = ArrayList<Int>()
            val frequencies = ArrayList<Int>()
            val tags = ArrayList<Byte>()
            val tagNames = mutableListOf<String>()
            words.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split(' ')
                    if (parts.size < 2) return@forEach
                    offsets += text.length
                    text.append(parts[0])
                    frequencies += parts[1].toIntOrNull() ?: 1
                    val tag = parts.getOrElse(2) { "x" }
                    val id = tagNames.indexOf(tag).takeIf { it >= 0 } ?: tagNames.size.also { tagNames += tag }
                    tags += id.toByte()
                }
            }
            offsets += text.length
            return ChineseDictionary(
                words = text.toString(),
                offsets = offsets.toIntArray(),
                frequencies = frequencies.toIntArray(),
                tagIds = tags.toByteArray(),
                tagNames = tagNames,
                pinyin = readTable(pinyin),
                simplified =
                    readTable(simplified).filter { (from, to) -> from.length == 1 && to.length == 1 }.entries.associate {
                        it.key[0] to
                            it.value[0]
                    },
            )
        }

        private fun readTable(input: InputStream): Map<String, String> =
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .mapNotNull { line ->
                        val split = line.indexOf('=')
                        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim()
                    }.toMap()
            }

        private fun isHan(ch: Char) = ch.code in 0x3400..0x9FFF || ch == '〇'

        private fun isLatinOrDigit(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in '０'..'９'

        private fun isWordChar(ch: Char) = isHan(ch) || isLatinOrDigit(ch)
    }
}

/** jieba's tags for these grammar words are rough (很 zg, 往 zg); these are what grammar expects. */
private val TAG_OVERRIDES =
    mapOf(
        "很" to "d",
        "啊" to "y",
        "呀" to "y",
        "啦" to "y",
        "嘛" to "y",
        "往" to "p",
        "离" to "p",
        "件" to "q",
        "东西" to "n",
        "有点儿" to "d",
        "有点" to "d",
        "正在" to "d",
        "一点儿" to "m",
        "下去" to "v",
        "下来" to "v",
        "上去" to "v",
        "过来" to "v",
        "们" to "k",
        "可以" to "v",
        "玩儿" to "v",
        "学会" to "v",
        "别" to "d",
        "连" to "p",
        "每" to "r",
        "宁可" to "d",
        "总是" to "d",
        "以外" to "f",
        "十分" to "d",
        "好吃" to "a",
        "多么" to "d",
        "一样" to "a",
        "就算" to "c",
        "等" to "v",
        "回家" to "v",
        "见面" to "v",
        "唱歌" to "v",
        "跑步" to "v",
        "走路" to "v",
        "生病" to "v",
        "上学" to "v",
        "放学" to "v",
        "毕业" to "v",
        "朝" to "p",
    )

/** Phrases the word list keeps whole that read better as two words. */
private val SPLITS =
    mapOf(
        "在家" to listOf("在" to "p", "家" to "n"),
        "我会" to listOf("我" to "r", "会" to "v"),
        "往前" to listOf("往" to "p", "前" to "f"),
        "往后" to listOf("往" to "p", "后" to "f"),
    )

/** Adjectives split from a 太 or 很 in front (太贵 → 太 + 贵); 太阳 and 太太 stay whole. */
private const val DEGREE_ADJECTIVES = "好多大小贵远近快慢难累忙晚早热冷长短高低少久重轻老新美丑胖瘦"

/** Verb + 过 or 着 pairs the word list keeps whole that are split so the particle shows. */
private val ASPECT_SPLITS =
    setOf(
        "看过",
        "去过",
        "受过",
        "出过",
        "下过",
        "走过",
        "看着",
        "想着",
        "指着",
        "打着",
        "跟着",
        "围着",
        "赶着",
        "贴着",
        "挨着",
        "闲着",
        "吸着",
        "穿着",
        "背着",
        "照着",
        "活着",
        "拿着",
        "坐着",
        "站着",
        "躺着",
        "笑着",
    )

/** Readings that depend on the part of speech, for a one-character word: 还 (return) huán, 长 (grow) zhǎng. */
private val TAGGED_READINGS =
    mapOf(
        "还v" to "huan2",
        "长v" to "zhang3",
        "只q" to "zhi1",
        "种v" to "zhong4",
        "为v" to "wei2",
        "干v" to "gan4",
        "教v" to "jiao1",
        "数n" to "shu4",
        "得v" to "de2",
        "着v" to "zhao2",
    )

/** Common words whose second syllable is neutral in speech, which the pinyin table gives a tone. */
private val WORD_READINGS =
    mapOf(
        "东西" to "dong1,xi5",
        "喜欢" to "xi3,huan5",
        "时候" to "shi2,hou5",
        "朋友" to "peng2,you5",
        "这个" to "zhe4,ge5",
        "那个" to "na4,ge5",
        "哪个" to "na3,ge5",
        "认识" to "ren4,shi5",
        "意思" to "yi4,si5",
        "告诉" to "gao4,su5",
        "漂亮" to "piao4,liang5",
        "先生" to "xian1,sheng5",
        "明白" to "ming2,bai5",
        "舒服" to "shu1,fu5",
        "休息" to "xiu1,xi5",
        "消息" to "xiao1,xi5",
        "麻烦" to "ma2,fan5",
        "客气" to "ke4,qi5",
        "事情" to "shi4,qing5",
        "关系" to "guan1,xi5",
        "地方" to "di4,fang5",
        "学生" to "xue2,sheng5",
        "头发" to "tou2,fa5",
        "眼睛" to "yan3,jing5",
        "耳朵" to "er3,duo5",
        "衣服" to "yi1,fu5",
        "便宜" to "pian2,yi5",
        "清楚" to "qing1,chu5",
        "聪明" to "cong1,ming5",
        "打算" to "da3,suan5",
        "知道" to "zhi1,dao5",
        "觉得" to "jue2,de5",
        "多少" to "duo1,shao5",
        "怎么样" to "zen3,me5,yang4",
        "什么" to "shen2,me5",
        "谢谢" to "xie4,xie5",
        "睡着" to "shui4,zhao2",
    )

/** Words where 儿 means child and keeps its own syllable (nǚ'ér) instead of the -r ending. */
private val CHILD_WORDS = setOf("女儿", "儿女", "婴儿", "孤儿", "幼儿", "健儿", "宠儿", "胎儿", "男儿", "少儿", "托儿", "混血儿", "新生儿", "患儿", "弃儿", "早产儿")

/** Particles read in the neutral tone (or their particle reading) wherever they appear. */
private val PARTICLE_READINGS =
    mapOf(
        "了" to "le",
        "的" to "de",
        "得" to "de",
        "地" to "de",
        "着" to "zhe",
        "过" to "guo",
        "吧" to "ba",
        "吗" to "ma",
        "呢" to "ne",
        "啊" to "a",
        "嘛" to "ma",
        "呀" to "ya",
        "啦" to "la",
        "们" to "men",
    )

/** Endings read in the neutral tone at the end of a longer word: 桌子, 我们, 怎么. */
private val NEUTRAL_SUFFIXES = setOf('子', '们', '么')

/** zhong1 → zhōng, lv4 → lǜ, ma5 → ma. */
internal fun toneMarked(numbered: String): String {
    if (numbered.isEmpty()) return numbered
    val tone = numbered.last().digitToIntOrNull()
    val letters = (if (tone != null) numbered.dropLast(1) else numbered).replace("u:", "ü").replace('v', 'ü')
    if (tone == null || tone !in 1..4) return letters
    val position =
        when {
            'a' in letters -> letters.indexOf('a')
            'e' in letters -> letters.indexOf('e')
            "ou" in letters -> letters.indexOf('o')
            else -> letters.indexOfLast { it in "iouü" }
        }
    if (position < 0) return letters
    val marked = TONE_MARKS.getValue(letters[position])[tone - 1]
    return letters.substring(0, position) + marked + letters.substring(position + 1)
}

private val TONE_MARKS =
    mapOf(
        'a' to "āáǎà",
        'e' to "ēéěè",
        'i' to "īíǐì",
        'o' to "ōóǒò",
        'u' to "ūúǔù",
        'ü' to "ǖǘǚǜ",
    )

/** Syllables of one word written together, with an apostrophe before a/e/o (xī'ān). */
internal fun joinSyllables(syllables: List<String>): String =
    buildString {
        syllables.forEachIndexed { index, syllable ->
            if (index > 0 && syllable.firstOrNull()?.let { it in "aeoāáǎàēéěèōóǒò" } == true) append('\'')
            append(syllable)
        }
    }
