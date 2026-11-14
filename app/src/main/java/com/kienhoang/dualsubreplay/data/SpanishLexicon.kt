package com.kienhoang.dualsubreplay.data

import java.io.InputStream

/**
 * Spanish nouns (n), adjectives (a), determiners or pronouns (d), prepositions (p), conjunctions
 * (c), adverbs (r) and interjections (i) from `es-words.txt` (made by
 * `tools/spanish/build_spanish_data.py` from Wiktionary), for dictionary forms: casas → casa,
 * bonitas → bonito, canciones → canción, esta → este.
 */
internal class SpanishWords private constructor(
    private val words: Map<String, String>,
    private val byPlainSpelling: Map<String, List<String>>,
    private val exceptions: Map<String, String>,
) {
    /** Whether [word] is listed with the part-of-speech [letter]. */
    fun has(
        word: String,
        letter: Char,
    ): Boolean = words[word]?.contains(letter) == true

    /** Whether [word] is listed at all. */
    fun isListed(word: String): Boolean = word in words

    /** Whether [word] is listed as a preposition, conjunction, adverb or interjection (para, como, ah). */
    fun isFunctionWord(word: String): Boolean = words[word]?.any { it in FUNCTION_LETTERS } == true

    /** Whether [word] is listed as an interjection (ah, eh, vale). */
    fun isInterjection(word: String): Boolean = words[word]?.contains('i') == true

    /** The UD tag of [word]'s likeliest listed part of speech (azúcar NOUN, ah INTJ), or null when unlisted. */
    fun tagOf(word: String): String? {
        val letters = words[word] ?: return null
        return TAG_ORDER.firstOrNull { (letter, _) -> letter in letters }?.second
    }

    /**
     * The dictionary form of a lowercase [word] as a [letter] word, or null when no rule leads to a
     * listed word. Same rules as `tools/spanish/spanish_rules.py`.
     */
    fun lemma(
        word: String,
        letter: Char,
    ): String? {
        exceptions["$word $letter"]?.let { return it }
        for (candidate in spanishLemmaCandidates(word)) {
            if (has(candidate, letter)) return candidate
            byPlainSpelling[plainSpelling(candidate)]?.firstOrNull { has(it, letter) }?.let { return it }
        }
        return null
    }

    companion object {
        private const val FUNCTION_LETTERS = "pcri"

        private val TAG_ORDER =
            listOf('n' to "NOUN", 'a' to "ADJ", 'i' to "INTJ", 'r' to "ADV", 'p' to "ADP", 'c' to "CCONJ", 'd' to "PRON")

        fun load(input: InputStream): SpanishWords {
            val words = HashMap<String, String>(16_384)
            val exceptions = HashMap<String, String>()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts[0].startsWith("=") && parts.size == 3) {
                        exceptions.putIfAbsent("${parts[0].drop(1)} ${parts[1]}", parts[2])
                    } else if (parts.size == 2) {
                        words[parts[0]] = parts[1]
                    }
                }
            }
            val byPlainSpelling = words.keys.groupBy(::plainSpelling).mapValues { it.value.sorted() }
            return SpanishWords(words, byPlainSpelling, exceptions)
        }
    }
}

/** The word, then without a plural ending (-s, -es, -ces → -z), each also from feminine to masculine (-a → -o, -a → nothing). */
internal fun spanishLemmaCandidates(word: String): List<String> {
    val singular = mutableListOf(word)
    if (word.endsWith("s") && word.length > 2) singular += word.dropLast(1)
    if (word.endsWith("es") && word.length > 3) singular += word.dropLast(2)
    if (word.endsWith("ces") && word.length > 4) singular += word.dropLast(3) + "z"
    return singular.flatMap { base ->
        if (base.endsWith("a") && base.length > 2) listOf(base, base.dropLast(1) + "o", base.dropLast(1)) else listOf(base)
    }
}

/** The spelling without written accents (canción → cancion), so plurals that move the accent are found. */
internal fun plainSpelling(word: String): String {
    val out = StringBuilder(word.length)
    for (ch in word) {
        out.append(
            when (ch) {
                'á' -> 'a'
                'é' -> 'e'
                'í' -> 'i'
                'ó' -> 'o'
                'ú', 'ü' -> 'u'
                else -> ch
            },
        )
    }
    return out.toString()
}
