package com.kienhoang.dualsubreplay.data

import java.io.InputStream

/**
 * English dictionary forms and word levels. Dictionary forms follow WordNet's *morphy* (its
 * irregular forms, then suffix rules whose result must be a WordNet word of that part of speech),
 * after the app's own irregular-verb table; words WordNet does not know fall back to spelling
 * rules. Levels are the CEFR-J Wordlist's (A1–B2).
 */
internal class EnglishLexicon(
    private val words: SortedWords,
    private val exceptions: Map<String, String>,
    private val levels: Map<String, CefrLevel>,
) {
    /** Whether WordNet lists [word] as part of speech [part] (n, v, a or r). */
    fun isWord(
        word: String,
        part: Char,
    ): Boolean = words.parts(word)?.contains(part) == true

    /** The CEFR-J level of [lemma] as [part] (n, v, a, r or f), else its lowest level as any part. */
    fun level(
        lemma: String,
        part: Char,
    ): CefrLevel? = levels["$lemma $part"] ?: LEVEL_PARTS.mapNotNull { levels["$lemma $it"] }.minOrNull()

    fun verbLemma(word: String): String {
        IRREGULAR_LEMMAS[word]?.let { return it }
        exceptions["$word v"]?.let { return it }
        return verbCandidates(word).firstOrNull { isWord(it, 'v') }
            ?: word.takeIf { isWord(it, 'v') }
            ?: spelledVerbLemma(word)
    }

    /**
     * Plurals become singular (years → year, leaves → leaf, children → child), except a plural the
     * CEFR-J list has as a word of its own (glasses, news, clothes).
     */
    fun nounLemma(word: String): String {
        IRREGULAR_PLURALS[word]?.let { return it }
        exceptions["$word n"]?.let { return it }
        if (!word.endsWith("s") || word.endsWith("ss") || levels.containsKey("$word n")) return word
        return nounCandidates(word).firstOrNull { isWord(it, 'n') }
            ?: word.takeIf { isWord(it, 'n') }
            ?: word.takeIf { it.endsWith("us") || it.endsWith("is") || it.length <= MIN_PLURAL_LENGTH }
            ?: word.dropLast(1)
    }

    /** bigger → big, happiest → happy; a word that is an adjective itself (clever, silver) stays. */
    fun adjectiveLemma(word: String): String {
        COMPARISON_LEMMAS[word]?.let { return it }
        exceptions["$word a"]?.let { return it }
        if (levels.containsKey("$word a")) return word
        return comparedCandidates(word).firstOrNull { isWord(it, 'a') } ?: word
    }

    /** faster → fast, sooner → soon, better → well; other adverbs (after, never) stay as they are. */
    fun adverbLemma(word: String): String {
        if (word == "better" || word == "best") return "well"
        COMPARISON_LEMMAS[word]?.let { return it }
        exceptions["$word r"]?.let { return it }
        val compared = word in ADVERB_COMPARATIVES
        if (!compared && (levels.containsKey("$word r") || isWord(word, 'r') && !isWord(word, 'a'))) return word
        return comparedCandidates(word).firstOrNull { isWord(it, 'a') || isWord(it, 'r') } ?: word
    }

    companion object {
        private const val MIN_PLURAL_LENGTH = 3
        private const val MIN_COMPARED_STEM = 3
        private val LEVEL_PARTS = listOf('n', 'v', 'a', 'r', 'f')

        /** Reads WordNet's words (`run nv`), its irregular forms (`went v go`) and the CEFR-J levels (`light a A1`). */
        fun load(
            words: InputStream,
            exceptions: InputStream,
            levels: InputStream,
        ): EnglishLexicon {
            val exceptionMap = HashMap<String, String>()
            exceptions.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val fields = line.split(' ')
                if (fields.size >= 3) exceptionMap.putIfAbsent("${fields[0]} ${fields[1]}", fields[2])
            }
            val levelMap = HashMap<String, CefrLevel>()
            levels.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val fields = line.split(' ')
                if (!line.startsWith("#") && fields.size == 3) levelMap["${fields[0]} ${fields[1]}"] = CefrLevel.valueOf(fields[2])
            }
            return EnglishLexicon(SortedWords.read(words), exceptionMap, levelMap)
        }

        /** -s, -es, -ies, then -ed and -ing with a doubled consonant undone, a silent e put back, or neither. */
        private fun verbCandidates(word: String): List<String> =
            when {
                word.endsWith("ies") -> listOf(word.dropLast(3) + "y", word.dropLast(1))
                word.endsWith("es") -> listOf(word.dropLast(1), word.dropLast(2))
                word.endsWith("s") -> listOf(word.dropLast(1))
                word.endsWith("ied") -> listOf(word.dropLast(3) + "y", word.dropLast(1))
                word.endsWith("ed") -> stemCandidates(word.dropLast(2))
                // singing → sing, not singe; hoping → hope, not hop.
                word.endsWith("ing") -> stemCandidates(word.dropLast(3)).let { if (word.endsWith("nging")) it.reversed() else it }
                else -> emptyList()
            }

        private fun stemCandidates(stem: String): List<String> {
            if (stem.isEmpty()) return emptyList()
            val doubled = stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou"
            return listOfNotNull(stem.dropLast(1).takeIf { doubled }, stem + "e", stem)
        }

        /** leaves → leaf, wives → wife, boxes → box, cities → city, years → year. */
        private fun nounCandidates(word: String): List<String> =
            listOfNotNull(
                word.dropLast(3).takeIf { word.endsWith("ves") }?.let { it + "f" },
                word.dropLast(3).takeIf { word.endsWith("ves") }?.let { it + "fe" },
                word.dropLast(3).takeIf { word.endsWith("ies") }?.let { it + "y" },
                word.dropLast(1),
                word.dropLast(2).takeIf { word.endsWith("es") },
            )

        /** -er / -est with a doubled consonant undone, y put back (happier), a silent e put back, or neither. */
        private fun comparedCandidates(word: String): List<String> {
            val stem =
                when {
                    word.endsWith("est") -> word.dropLast(3)
                    word.endsWith("er") -> word.dropLast(2)
                    else -> return emptyList()
                }
            if (stem.length < MIN_COMPARED_STEM) return emptyList()
            return when {
                stem.endsWith("i") -> listOf(stem.dropLast(1) + "y")
                else -> stemCandidates(stem).filter { it.length >= MIN_COMPARED_STEM }
            }
        }
    }
}

/**
 * WordNet's words sorted (String order) in one string with offsets, and each word's parts of
 * speech, so 80,000 words cost about 2 MB instead of a map's 8 MB.
 */
internal class SortedWords private constructor(
    private val text: String,
    private val offsets: IntArray,
    private val parts: Array<String>,
    private val partIds: ByteArray,
) {
    /** The parts of speech of [word] (`nv`), or null if WordNet does not list it. */
    fun parts(word: String): String? {
        var low = 0
        var high = partIds.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compareAt(middle, word)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle - 1
                else -> return parts[partIds[middle].toInt()]
            }
        }
        return null
    }

    private fun compareAt(
        index: Int,
        word: String,
    ): Int {
        val start = offsets[index]
        val length = offsets[index + 1] - start
        for (i in 0 until minOf(length, word.length)) {
            val difference = text[start + i] - word[i]
            if (difference != 0) return difference
        }
        return length - word.length
    }

    companion object {
        fun read(input: InputStream): SortedWords {
            val text = StringBuilder()
            val offsets = ArrayList<Int>()
            val partIds = ArrayList<Byte>()
            val parts = mutableListOf<String>()
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val space = line.indexOf(' ')
                if (space <= 0) return@forEachLine
                offsets += text.length
                text.append(line, 0, space)
                val letters = line.substring(space + 1)
                val id = parts.indexOf(letters).takeIf { it >= 0 } ?: parts.size.also { parts += letters }
                partIds += id.toByte()
            }
            offsets += text.length
            return SortedWords(text.toString(), offsets.toIntArray(), parts.toTypedArray(), partIds.toByteArray())
        }
    }
}
