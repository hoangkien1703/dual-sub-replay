package com.kienhoang.dualsubreplay.data

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One morpheme from the analyzer, with IPADIC's tags. `*` means the dictionary has no value.
 * [detail] is the second part-of-speech level and [subDetail] the third.
 */
internal data class Morpheme(
    val surface: String,
    val start: Int,
    val pos: String,
    val detail: String = "*",
    val subDetail: String = "*",
    val conjugationForm: String = "*",
    val baseForm: String = "*",
    val reading: String = "*",
) {
    val end: Int get() = start + surface.length
}

/** Where the Japanese dictionary is: not needed yet, downloading, loaded, or failed (retried later). */
internal enum class JapaneseDictionaryStatus { IDLE, DOWNLOADING, READY, UNAVAILABLE }

/**
 * Japanese morphological analysis with Kuromoji (MeCab's IPADIC dictionary), grouped into the
 * words a learner taps. The first time Japanese text is analyzed, the dictionary is downloaded
 * if needed and loaded (about a second and ~50 MB) on a background thread. Until then [analyze]
 * returns null and callers use their heuristic; [revision] changes when the analyzer becomes
 * ready so UI can tokenize again.
 */
internal object JapaneseMorphology {
    private val loaded = MutableStateFlow(0)
    val revision: StateFlow<Int> = loaded
    private val dictionaryStatus = MutableStateFlow(JapaneseDictionaryStatus.IDLE)
    val status: StateFlow<JapaneseDictionaryStatus> = dictionaryStatus

    @Volatile
    private var tokenizer: Tokenizer? = null

    @Volatile
    private var store: JapaneseDictionaryStore? = null

    @Volatile
    private var retryAt = 0L
    private val loading = AtomicBoolean(false)

    /** Where the app keeps the downloaded dictionary. Without one, a dictionary on the classpath (unit tests) is used. */
    fun useStore(dictionary: JapaneseDictionaryStore) {
        store = dictionary
    }

    /** Whether the dictionary file is on this device; true when none is needed (bundled in unit tests). */
    fun isDictionaryInstalled(): Boolean = store?.isInstalled() ?: true

    /** Downloads the dictionary without loading it, for the settings screen. Blocking; returns whether it is installed. */
    fun installDictionary(): Boolean = store?.install() ?: true

    /** Deletes the downloaded dictionary to free space; it downloads again the next time Japanese is shown. */
    fun removeDictionary() {
        store?.remove()
    }

    /** Starts downloading and loading the dictionary in the background unless it is loaded or loading. */
    fun warmUp() {
        if (tokenizer != null || System.currentTimeMillis() < retryAt) return
        val dictionary = store
        // No store yet and no bundled dictionary: the app has not configured storage; try again later.
        if (dictionary == null && Tokenizer::class.java.getResource(BUNDLED_DICTIONARY_PROBE) == null) return
        if (!loading.compareAndSet(false, true)) return
        thread(name = "japanese-morphology", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                tokenizer = load(dictionary)
                dictionaryStatus.value = JapaneseDictionaryStatus.READY
                loaded.value += 1
            } catch (_: Exception) {
                failed()
            } catch (_: OutOfMemoryError) {
                // Low-memory devices keep the heuristic tokenizer rather than crash.
                failed()
            } finally {
                loading.set(false)
            }
        }
    }

    private fun load(dictionary: JapaneseDictionaryStore?): Tokenizer {
        if (dictionary == null) return Tokenizer()
        if (!dictionary.isInstalled()) {
            dictionaryStatus.value = JapaneseDictionaryStatus.DOWNLOADING
            if (!dictionary.install()) throw IOException("The Japanese dictionary could not be downloaded.")
        }
        return dictionary.loadTokenizer()
    }

    private fun failed() {
        retryAt = System.currentTimeMillis() + RETRY_DELAY_MS
        dictionaryStatus.value = JapaneseDictionaryStatus.UNAVAILABLE
    }

    /** Loads the analyzer and waits for it, for tests. Returns whether it is ready. */
    fun awaitReady(timeoutMs: Long = 30_000): Boolean {
        warmUp()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (loading.get() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return tokenizer != null
    }

    /** Learner words of [text], or null while the analyzer is loading or unavailable. */
    fun analyze(text: String): List<AnalyzedToken>? {
        val analyzer = tokenizer
        if (analyzer == null) {
            warmUp()
            return null
        }
        return japaneseLearnerWords(analyzer, text)
    }

    /** The analyzer's morphemes of [text], or null while the analyzer is loading or unavailable. */
    fun morphemes(text: String): List<Morpheme>? {
        val analyzer = tokenizer
        if (analyzer == null) {
            warmUp()
            return null
        }
        return japaneseMorphemes(analyzer, text)
    }

    private const val BUNDLED_DICTIONARY_PROBE = "doubleArrayTrie.bin"
    private const val RETRY_DELAY_MS = 60_000L
    private const val POLL_MS = 20L
}

/** Analyzes [text] with [analyzer] and groups the morphemes into learner words. */
internal fun japaneseLearnerWords(
    analyzer: Tokenizer,
    text: String,
): List<AnalyzedToken> = groupJapaneseMorphemes(japaneseMorphemes(analyzer, text))

internal fun japaneseMorphemes(
    analyzer: Tokenizer,
    text: String,
): List<Morpheme> = synchronized(analyzer) { analyzer.tokenize(text) }.map(::toMorpheme)

private fun toMorpheme(token: Token) =
    Morpheme(
        surface = token.surface,
        start = token.position,
        pos = token.partOfSpeechLevel1,
        detail = token.partOfSpeechLevel2,
        subDetail = token.partOfSpeechLevel3,
        conjugationForm = token.conjugationForm,
        baseForm = token.baseForm,
        reading = token.reading ?: "*",
    )

private enum class WordKind { PREFIX, NOUN, SURU_NOUN, NUMBER, INFLECTING, COPULA, CLOSED }

private const val NOUN = "名詞"
private const val VERB = "動詞"
private const val ADJECTIVE = "形容詞"
private const val AUXILIARY = "助動詞"
private const val PARTICLE = "助詞"

/** Prefixes that belong to the next word: お茶, ご飯, 御社, 第一. Others (今, 全) stand alone. */
private val WORD_PREFIXES = setOf("お", "ご", "御", "第")

/** Te-form and conditional endings that stay on their verb or adjective: 食べて, 読んで, 行けば. */
private val INFLECTION_CONNECTORS = setOf("て", "で", "ば", "ちゃ", "じゃ")

/**
 * Groups analyzer morphemes into learner words, the way reading tools such as bunsetsu and
 * jisho.org present them: a verb or adjective keeps its conjugation and auxiliary endings
 * (思います, 食べられなかった, 手伝ってもらって), a suru-noun keeps する (勉強しています), numbers
 * keep their counters (５歳), nouns keep suffixes (子どもたち), and word prefixes join their noun
 * (お茶). Particles, copulas (です, でした) and punctuation stay separate. Spaces are dropped.
 */
internal fun groupJapaneseMorphemes(morphemes: List<Morpheme>): List<AnalyzedToken> {
    val words = mutableListOf<MutableList<Morpheme>>()
    var kind = WordKind.CLOSED
    var previous: Morpheme? = null
    for (morpheme in morphemes) {
        if (morpheme.surface.isBlank() || morpheme.detail == "空白") {
            previous = null
            continue
        }
        val last = previous
        kind =
            if (last != null && last.end == morpheme.start && attaches(kind, last, morpheme)) {
                words.last() += morpheme
                kindAfterAttaching(kind, morpheme)
            } else {
                words += mutableListOf(morpheme)
                kindOf(morpheme)
            }
        previous = morpheme
    }
    return words.map(::learnerWord)
}

private fun kindOf(morpheme: Morpheme): WordKind =
    when (morpheme.pos) {
        "接頭詞" -> if (morpheme.surface in WORD_PREFIXES) WordKind.PREFIX else WordKind.CLOSED
        NOUN ->
            when (morpheme.detail) {
                "数" -> WordKind.NUMBER
                "サ変接続" -> WordKind.SURU_NOUN
                else -> WordKind.NOUN
            }
        VERB, ADJECTIVE -> WordKind.INFLECTING
        AUXILIARY -> WordKind.COPULA
        else -> WordKind.CLOSED
    }

private fun kindAfterAttaching(
    kind: WordKind,
    next: Morpheme,
): WordKind =
    when (kind) {
        WordKind.PREFIX -> kindOf(next).takeIf { it != WordKind.NUMBER } ?: WordKind.NOUN
        WordKind.SURU_NOUN -> if (next.pos == VERB) WordKind.INFLECTING else WordKind.NOUN
        WordKind.NUMBER -> if (next.detail == "数") WordKind.NUMBER else WordKind.NOUN
        else -> kind
    }

private fun attaches(
    kind: WordKind,
    previous: Morpheme,
    next: Morpheme,
): Boolean =
    when (kind) {
        WordKind.PREFIX -> next.pos == NOUN || next.pos == VERB || next.pos == ADJECTIVE
        WordKind.NUMBER -> next.pos == NOUN && (next.detail == "数" || next.detail == "接尾")
        WordKind.NOUN -> isNounSuffix(next)
        WordKind.SURU_NOUN -> isNounSuffix(next) || (next.pos == VERB && next.baseForm in setOf("する", "できる"))
        WordKind.INFLECTING -> continuesInflection(previous, next)
        WordKind.COPULA -> next.pos == AUXILIARY
        WordKind.CLOSED -> false
    }

private fun isNounSuffix(next: Morpheme) = next.pos == NOUN && next.detail == "接尾" && next.subDetail != "助動詞語幹"

/** Whether [next] is an ending of the verb or adjective that [previous] ends. */
private fun continuesInflection(
    previous: Morpheme,
    next: Morpheme,
): Boolean =
    when (next.pos) {
        AUXILIARY -> !isCopula(previous, next)
        VERB ->
            next.detail == "接尾" ||
                next.detail == "非自立" ||
                // Compound verbs: 思い出す, 飛び込む.
                (previous.pos == VERB && previous.conjugationForm == "連用形")
        ADJECTIVE ->
            next.detail == "非自立" ||
                next.detail == "接尾" ||
                // 高くない: IPADIC tags this ない as an independent adjective.
                (previous.pos == ADJECTIVE && previous.conjugationForm.startsWith("連用") && next.baseForm == "ない")
        PARTICLE -> next.detail == "接続助詞" && next.surface in INFLECTION_CONNECTORS
        NOUN -> next.detail == "接尾" && next.subDetail == "助動詞語幹"
        else -> false
    }

/**
 * です and だ are separate words after a verb or adjective (あったかい / です), except the だ that
 * is a past tense after a te-stem (読んだ). らしい is a separate word too.
 */
private fun isCopula(
    previous: Morpheme,
    next: Morpheme,
): Boolean =
    when (next.baseForm) {
        "です", "らしい" -> true
        "だ" -> previous.pos == ADJECTIVE || !previous.conjugationForm.startsWith("連用")
        else -> false
    }

private fun learnerWord(parts: List<Morpheme>): AnalyzedToken {
    val head = parts.firstOrNull { it.pos != "接頭詞" } ?: parts.first()
    val text = parts.joinToString("") { it.surface }
    val suruVerb = head.detail == "サ変接続" && parts.any { it.pos == VERB }
    val readings = parts.map { part -> part.reading.takeIf { it != "*" } ?: part.surface.takeIf(::isKana) }
    val reading =
        readings
            .takeIf { list -> list.all { it != null } && text.any(::isKanji) }
            ?.joinToString("") { toHiragana(it!!) }
    val baseForm =
        when {
            suruVerb -> head.surface + "する"
            head.pos == VERB || head.pos == ADJECTIVE -> head.baseForm.takeIf { it != "*" && parts.size > 1 }
            else -> null
        }
    val first = parts.first()
    return AnalyzedToken(
        text = text,
        startIndex = first.start,
        endIndex = first.start + text.length,
        partOfSpeech = if (suruVerb) PartOfSpeech.VERB else partOfSpeechOf(head),
        reading = reading,
        baseForm = baseForm,
    )
}

private fun partOfSpeechOf(morpheme: Morpheme): PartOfSpeech =
    when (morpheme.pos) {
        NOUN ->
            when (morpheme.detail) {
                "代名詞" -> PartOfSpeech.PRONOUN
                "形容動詞語幹" -> PartOfSpeech.ADJECTIVE
                else -> PartOfSpeech.NOUN
            }
        VERB -> PartOfSpeech.VERB
        ADJECTIVE, "連体詞" -> PartOfSpeech.ADJECTIVE
        "副詞" -> PartOfSpeech.ADVERB
        "接続詞" -> PartOfSpeech.CONJUNCTION
        PARTICLE, AUXILIARY -> PartOfSpeech.PARTICLE
        else -> PartOfSpeech.OTHER
    }

private fun isKanji(ch: Char) = ch.code in 0x4E00..0x9FFF || ch == '々'

private fun isKana(text: String) = text.isNotEmpty() && text.all { it.code in 0x3040..0x30FF }

private fun toHiragana(text: String) =
    buildString(text.length) {
        text.forEach { ch -> append(if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar() else ch) }
    }
