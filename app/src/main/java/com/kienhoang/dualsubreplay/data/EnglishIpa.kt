package com.kienhoang.dualsubreplay.data

import java.io.InputStream
import java.util.Locale

/**
 * American IPA for English words (25kiuo, discussion #81, owner pick 2026-11-16: on by default,
 * spec docs/specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md). The data is
 * `en-ipa.txt`, made by `tools/english/build_english_ipa.py` from ipa-dict, for the words of a
 * subtitle frequency list; other words have none. Japanese and Chinese keep their own readings.
 */
internal object EnglishIpa {
    private val table = BackgroundLoaded("english-ipa") { LearnerData.open("en-ipa.txt").use(::readEnglishIpa) }

    /** Changes once the table has loaded, so a card shown before then can look again. */
    val revision get() = table.revision

    fun warmUp() = table.warmUp()

    fun awaitReady(timeoutMs: Long = 60_000): Boolean = table.awaitReady(timeoutMs)

    /** [word]'s IPA as the card shows it ("/ɡoʊ/", "/rɛd/, /rid/"), or null when unknown or still loading. */
    fun of(word: String): String? = table.get()?.let { englishIpa(it, word) }
}

/** Reads `en-ipa.txt`: "word<TAB>ipa[,ipa]" lines, "#" comments. */
internal fun readEnglishIpa(input: InputStream): Map<String, String> {
    val entries = HashMap<String, String>(INITIAL_CAPACITY)
    input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
        val tab = line.indexOf('\t')
        if (tab > 0 && tab < line.length - 1) entries[line.substring(0, tab)] = line.substring(tab + 1)
    }
    return entries
}

/** [word]'s pronunciations from [table], each between slashes; null for a word the table lacks. */
internal fun englishIpa(
    table: Map<String, String>,
    word: String,
): String? {
    val key =
        word
            .trim()
            .replace('’', '\'')
            .lowercase(Locale.ROOT)
    if (key.isEmpty()) return null
    val pronunciations = table[key] ?: return null
    return pronunciations
        .split(',')
        .filter { it.isNotBlank() }
        .joinToString(", ") { "/$it/" }
        .ifEmpty { null }
}

/** Whether [language] is English, whose words get IPA. */
internal fun isEnglishLanguage(language: String?): Boolean =
    language?.lowercase(Locale.ROOT)?.let { it == "en" || it.startsWith("en-") || it.startsWith("en_") } == true

/**
 * The reading the word card shows and the card's Reading field gets: the analyzer's own (kana,
 * pinyin), else an English word's IPA. A phrase gets none.
 */
internal fun wordReading(selection: LearningWordSelection): String? =
    selection.token.reading?.takeIf { it.isNotBlank() }
        ?: selection
            .takeIf { !it.isPhrase && isEnglishLanguage(it.wordLanguage) }
            ?.let { EnglishIpa.of(it.token.text) }

private const val INITIAL_CAPACITY = 65_536
