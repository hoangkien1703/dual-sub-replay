package com.kienhoang.dualsubreplay.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Longer "words" are analyzer mistakes or whole lines, never kept. */
private const val MAX_KNOWN_WORD_LENGTH = 64

private const val PERCENT = 100

/**
 * The key a word is known by: its dictionary form when the analyzer gives one (Japanese and English),
 * else the word itself, lower-cased. Null for tokens without a letter (punctuation, numbers).
 */
internal fun knownWordKey(token: AnalyzedToken): String? = knownWordKey(token.baseForm?.takeIf { it.isNotBlank() } ?: token.text)

internal fun knownWordKey(word: String): String? =
    word
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() && it.length <= MAX_KNOWN_WORD_LENGTH && it.any(Char::isLetter) && '\n' !in it }

/** The language a known-word list belongs to: the base code ("en" for "en-US", "zh" for "zh-Hans"). */
internal fun knownWordLanguage(code: String?): String? =
    code
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z]{2,3}")) }

/** The word keys of each subtitle line, in order and with repeats. */
internal fun lineWordKeys(
    segments: List<SubtitleSegment>,
    languageCode: String?,
): List<List<String>> =
    segments.map { segment -> LanguageAwareTokenizer.tokenize(segment.originalText, languageCode).mapNotNull(::knownWordKey) }

/** How much of a video's words are known, from [lineWordKeys]. */
internal data class VideoComprehension(
    /** Word occurrences in the whole video. */
    val words: Int,
    /** Occurrences of known words. */
    val known: Int,
    /** Occurrences of saved (learning) words that are not marked known. */
    val learning: Int,
    /** Different words that are neither known nor saved. */
    val newWords: Set<String>,
    /** Lines with exactly one new word. */
    val oneNewWordLines: Set<Int>,
) {
    /** The known share, rounded down, so 100% means every word is known. */
    val percent: Int get() = if (words == 0) 0 else known * PERCENT / words
}

internal fun videoComprehension(
    lines: List<List<String>>,
    known: Set<String>,
    learning: Set<String>,
): VideoComprehension {
    var words = 0
    var knownCount = 0
    var learningCount = 0
    val newWords = LinkedHashSet<String>()
    val oneNew = HashSet<Int>()
    lines.forEachIndexed { index, keys ->
        val lineNew = HashSet<String>()
        keys.forEach { key ->
            words++
            when (key) {
                in known -> knownCount++
                in learning -> learningCount++
                else -> lineNew += key
            }
        }
        if (lineNew.size == 1) oneNew += index
        newWords += lineNew
    }
    return VideoComprehension(words, knownCount, learningCount, newWords, oneNew)
}

/**
 * The words the user marked as known, one list per language in `files/known-words/<language>.txt`
 * (one key per line). Separate from preferences, so Reset all settings keeps them, like saved words.
 */
internal class KnownWordsRepository internal constructor(
    private val directory: File,
) {
    private val lists = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _total = MutableStateFlow<Int?>(null)

    /** How many words are known in every language together, once counted (see [refreshTotal]). */
    val total: StateFlow<Int?> = _total.asStateFlow()

    /** The known words of [language] (a [knownWordLanguage] code); empty until the list is read. */
    fun words(language: String): StateFlow<Set<String>> {
        lists[language]?.let { return it.asStateFlow() }
        val created = MutableStateFlow<Set<String>>(emptySet())
        val flow = lists.putIfAbsent(language, created) ?: created
        if (flow === created) scope.launch { mutex.withLock { flow.value = read(language) + flow.value } }
        return flow.asStateFlow()
    }

    suspend fun setKnown(
        language: String,
        keys: Collection<String>,
        known: Boolean,
    ) = withContext(Dispatchers.IO) {
        val valid = keys.mapNotNull(::knownWordKey)
        if (valid.isEmpty() || knownWordLanguage(language) != language) return@withContext
        mutex.withLock {
            val flow = lists.getOrPut(language) { MutableStateFlow(emptySet()) }
            val stored = read(language) + flow.value
            val updated = if (known) stored + valid else stored - valid.toSet()
            write(language, updated)
            flow.value = updated
            _total.value = null
        }
        refreshTotal()
    }

    /** Forgets every known word in every language. */
    suspend fun clearAll() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.listFiles()?.forEach { it.delete() }
                lists.values.forEach { it.value = emptySet() }
                _total.value = 0
            }
        }

    suspend fun refreshTotal() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _total.value = directory.listFiles { file -> file.name.endsWith(".txt") }?.sumOf { file -> readFile(file).size } ?: 0
            }
        }

    private fun file(language: String) = File(directory, "$language.txt")

    private fun read(language: String): Set<String> = readFile(file(language))

    private fun readFile(file: File): Set<String> =
        runCatching { file.takeIf(File::isFile)?.readLines(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(::knownWordKey)
            .toSet()

    private fun write(
        language: String,
        words: Set<String>,
    ) {
        directory.mkdirs()
        val target = file(language)
        val temporary = File(directory, "$language.txt.tmp")
        temporary.writeText(words.sorted().joinToString("\n"), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    companion object {
        @Volatile private var instance: KnownWordsRepository? = null

        fun get(context: Context): KnownWordsRepository =
            instance ?: synchronized(this) {
                instance ?: KnownWordsRepository(File(context.applicationContext.filesDir, "known-words")).also { instance = it }
            }
    }
}
