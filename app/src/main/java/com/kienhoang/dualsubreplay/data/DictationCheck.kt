package com.kienhoang.dualsubreplay.data

import java.text.Normalizer
import java.util.Locale

/**
 * Dictation's Check (Settings → Study tools → Play mode → Dictation, owner pick 2026-11-09): which
 * parts of a subtitle line the learner typed. Capital letters, punctuation and spacing don't count;
 * accents do. Parts are matched in order, so one missed word doesn't make the rest wrong.
 */
object DictationCheck {
    /**
     * Checks [answer] against [line]. [tokens] are the line's tokens in [languageCode]; Japanese
     * uses them so a word typed in kana counts when it matches the word's reading.
     */
    fun check(
        line: String,
        answer: String,
        languageCode: String?,
        tokens: List<AnalyzedToken> = emptyList(),
    ): DictationResult {
        val mode = modeFor(line, languageCode)
        val units = units(line, mode, tokens)
        val atoms = atoms(answer, mode)
        val matched = align(units, atoms)
        return DictationResult(units.mapIndexed { index, unit -> DictationPart(unit.shown, index in matched) }, spaced = mode == Mode.WORDS)
    }

    /** One dot per word (per character for Chinese, Japanese and other languages written without spaces). */
    fun hiddenDots(
        line: String,
        languageCode: String?,
    ): String {
        val mode = modeFor(line, languageCode)
        val count = if (mode == Mode.WORDS) wordPieces(line).size else characterKey(line, mode == Mode.JAPANESE).length
        return List(count.coerceIn(1, MAX_DOTS)) { DOT }.joinToString(if (mode == Mode.WORDS) " " else "")
    }

    internal enum class Mode { WORDS, CHARACTERS, JAPANESE }

    internal fun modeFor(
        line: String,
        languageCode: String?,
    ): Mode {
        val code =
            languageCode
                ?.lowercase(Locale.ROOT)
                ?.substringBefore('-')
                ?.substringBefore('_')
                .orEmpty()
        return when {
            LanguageAwareTokenizer.isJapanese(line, languageCode) -> Mode.JAPANESE
            code in CHARACTER_LANGUAGES || LanguageAwareTokenizer.isCjk(line) -> Mode.CHARACTERS
            else -> Mode.WORDS
        }
    }

    /** A part of the line to hear: what is shown, and the typed atoms that count as it. */
    private class Target(
        val shown: String,
        val accepted: List<List<String>>,
    )

    private fun units(
        line: String,
        mode: Mode,
        tokens: List<AnalyzedToken>,
    ): List<Target> =
        when (mode) {
            Mode.WORDS -> wordPieces(line).map { Target(it.shown, listOf(listOf(it.key))) }
            Mode.CHARACTERS -> characters(characterKey(line, kana = false)).map { Target(it, listOf(listOf(it))) }
            Mode.JAPANESE -> japaneseUnits(line, tokens)
        }

    private fun japaneseUnits(
        line: String,
        tokens: List<AnalyzedToken>,
    ): List<Target> {
        val covered = tokens.filter { it.startIndex >= 0 && it.endIndex <= line.length && it.startIndex < it.endIndex }
        if (covered.isEmpty()) return characters(characterKey(line, kana = true)).map { Target(it, listOf(listOf(it))) }
        return covered.mapNotNull { token ->
            val surface = characterKey(token.text, kana = true)
            if (surface.isEmpty()) return@mapNotNull null
            val reading = token.reading?.let { characterKey(it, kana = true) }?.takeIf { it.isNotEmpty() && it != surface }
            Target(token.text, listOfNotNull(characters(surface), reading?.let(::characters)))
        }
    }

    private fun atoms(
        answer: String,
        mode: Mode,
    ): List<String> =
        when (mode) {
            Mode.WORDS -> wordPieces(answer).map { it.key }
            Mode.CHARACTERS -> characters(characterKey(answer, kana = false))
            Mode.JAPANESE -> characters(characterKey(answer, kana = true))
        }

    /**
     * The most units matched in order, each unit by one of its accepted atom runs (longest common
     * subsequence where a unit may cover several atoms). Returns the matched unit indices.
     */
    private fun align(
        units: List<Target>,
        atoms: List<String>,
    ): Set<Int> {
        val best = Array(units.size + 1) { IntArray(atoms.size + 1) }
        for (i in 1..units.size) {
            for (j in 0..atoms.size) {
                var value = best[i - 1][j]
                if (j > 0) value = maxOf(value, best[i][j - 1])
                matchLength(units[i - 1], atoms, j)?.let { value = maxOf(value, best[i - 1][j - it] + 1) }
                best[i][j] = value
            }
        }
        val matched = mutableSetOf<Int>()
        var i = units.size
        var j = atoms.size
        while (i > 0) {
            val length = matchLength(units[i - 1], atoms, j)
            when {
                length != null && best[i][j] == best[i - 1][j - length] + 1 -> {
                    matched += i - 1
                    j -= length
                    i--
                }
                best[i][j] == best[i - 1][j] -> i--
                else -> j--
            }
        }
        return matched
    }

    /** How many atoms ending at [end] one of [unit]'s accepted runs covers, or null. */
    private fun matchLength(
        unit: Target,
        atoms: List<String>,
        end: Int,
    ): Int? =
        unit.accepted
            .filter { run -> run.isNotEmpty() && run.size <= end && atoms.subList(end - run.size, end) == run }
            .maxOfOrNull { it.size }

    private class Piece(
        val shown: String,
        val key: String,
    )

    /** The words of [text] as shown (punctuation off their ends) and as compared. */
    private fun wordPieces(text: String): List<Piece> =
        Normalizer
            .normalize(text, Normalizer.Form.NFC)
            .split(WORD_BREAK)
            .map { it.trim(*APOSTROPHES) }
            .mapNotNull { shown -> wordKey(shown).takeIf { it.isNotEmpty() }?.let { Piece(shown, it) } }

    private fun wordKey(word: String): String =
        Normalizer
            .normalize(word, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filterNot { it in APOSTROPHES || Character.getType(it) == Character.FORMAT.toInt() }

    /** [text] without spaces, punctuation and symbols, in lower case; with [kana], katakana as hiragana. */
    private fun characterKey(
        text: String,
        kana: Boolean,
    ): String {
        val plain =
            Normalizer
                .normalize(text, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(NOT_COUNTED, "")
        return if (kana) plain.map(::hiragana).joinToString("") else plain
    }

    private fun hiragana(char: Char): Char = if (char in KATAKANA_WITH_HIRAGANA) char - KATAKANA_OFFSET else char

    private fun characters(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            result += text.substring(index, next)
            index = next
        }
        return result
    }

    private const val DOT = "•"
    private const val MAX_DOTS = 40
    private const val KATAKANA_OFFSET = 0x60
    private val KATAKANA_WITH_HIRAGANA = 'ァ'..'ヶ'
    private val APOSTROPHES = charArrayOf('\'', '’', 'ʼ', '`')

    /** Spaces, punctuation and symbols split words; apostrophes stay inside them (don't, l'eau). */
    private val WORD_BREAK = Regex("[[\\s\\p{P}\\p{S}]&&[^'’ʼ`]]+")
    private val NOT_COUNTED = Regex("[\\s\\p{P}\\p{S}\\p{Cf}]+")

    /** Written without spaces between words: checked per character. */
    private val CHARACTER_LANGUAGES = setOf("zh", "yue", "th", "lo", "km", "my")
}

/** One part of a checked line: shown as [text], [right] when the learner typed it. */
data class DictationPart(
    val text: String,
    val right: Boolean,
)

/** Dictation's Check for one line. */
data class DictationResult(
    val parts: List<DictationPart>,
    /** Words written with spaces between them; false for parts checked per character. */
    val spaced: Boolean = true,
) {
    val rightCount: Int get() = parts.count { it.right }
    val perfect: Boolean get() = parts.isNotEmpty() && parts.all { it.right }
}
