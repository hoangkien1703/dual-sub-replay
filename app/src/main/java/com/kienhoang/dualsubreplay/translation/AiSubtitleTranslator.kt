package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * Settings → Translation → Translate with AI (off by default, owner request 2026-10-28): the AI service
 * and key from the AI assistant's settings translate subtitles, as 4You's own server does. A request
 * carries a window of sentences, each split into the rows the screen shows, plus the sentences before
 * them as context only; the model answers with one translation per row. Rows then get exactly the
 * words meant for them instead of a character-count cut of one Google sentence. Google translates
 * first and stays the fallback (owner request 2026-10-28, "show Google first, then stream"): the
 * playing minute shows Google's lines at once, and each sentence switches to the AI's as soon as the
 * model has written it ([AiReplyReader]), so nobody waits for the whole request.
 */

/**
 * One request carries at most this many sentences and characters: few requests per video (about 8
 * for 30 minutes of speech) for free keys' daily limits, while a reply still comes in seconds.
 */
internal const val MAX_AI_BATCH_SENTENCES = 60
internal const val MAX_AI_BATCH_CHARS = 6_000

/** After a failed request (network, limit, refusal) the AI is not asked for this long; Google translates meanwhile. */
internal const val AI_FAILURE_REST_MS = 30_000L

/** Markup some models add (`<i>sự</i>`); subtitles show plain text. */
private val MARKUP = Regex("</?[A-Za-z][^<>]*>")

/** Raised when the prompt changes, so lines cached with the old one are translated again. */
internal const val AI_PROMPT_VERSION = 2

/** Sentences of the previous request sent again as context, so pronouns and tone carry over. */
internal const val AI_CONTEXT_SENTENCES = 4

/** A model the user set up for the AI assistant. [scope] (service and model) keeps their translations apart. */
internal interface AiTranslationModel {
    val scope: String

    /**
     * Sends one request and returns the reply text, or throws an [IOException]. [onText] receives the
     * reply piece by piece while the service writes it (all at once where it cannot stream).
     */
    suspend fun ask(
        system: String,
        user: String,
        onText: (String) -> Unit = {},
    ): String
}

/** The model answered with something that is not the translation asked for. */
internal class AiTranslationException(
    message: String,
) : IOException(message)

/**
 * A sentence to translate and the rows it shows as. [prefixes] are the texts of rows 1..k (k < rows),
 * whose translations the player asks for to place its row breaks.
 */
internal data class AiSentence(
    val text: String,
    val rows: List<String>,
    val prefixes: List<String> = emptyList(),
)

/**
 * Groups the texts the player asks for (each sentence followed by its row prefixes, see
 * `translationTexts`) back into sentences with rows. A prefix is a shorter text the sentence starts with.
 */
internal fun aiSentences(texts: List<String>): List<AiSentence> {
    val sentences = mutableListOf<Pair<String, MutableList<String>>>()
    texts.forEach { text ->
        val current = sentences.lastOrNull()
        if (current != null && text.isNotBlank() && text.length < current.first.length && current.first.startsWith(text)) {
            current.second += text
        } else if (text.isNotBlank()) {
            sentences += text to mutableListOf()
        }
    }
    return sentences.map { (text, prefixes) ->
        val kept = prefixes.distinct().sortedBy { it.length }.filter { it.length < text.length }
        var start = 0
        val rows =
            (kept.map { it.length } + text.length).map { end ->
                text.substring(start, end).trim().also { start = end }
            }
        if (rows.any(String::isEmpty)) AiSentence(text, listOf(text)) else AiSentence(text, rows, kept)
    }
}

/** The instructions sent with every request. */
internal fun aiTranslationSystemPrompt(
    source: String,
    target: String,
): String {
    val from = languageName(source)
    val to = languageName(target)
    return """
        You translate video subtitles from $from ($source) to $to ($target) for someone learning $from who reads $to.
        The input is JSON: "context" holds earlier sentences of the same video, for meaning only; "sentences" holds the sentences to translate, in the order they are spoken, each split into the rows the screen shows.
        Captions are often made by automatic speech recognition: punctuation may be missing, so one spoken sentence can be cut across several input sentences, and some words are misheard. Read the sentences as one continuous speech. When a sentence is only a piece of a longer one, translate it as that piece so that reading the translations in order gives the whole sentence naturally. When a word makes no sense in context, translate what the speaker most likely said (a word that sounds similar), not the misheard word.
        Fill in subjects the source leaves out (common in Japanese, Korean, Chinese and Vietnamese) from context; when someone talks about their own life, it is usually "I".
        Translate naturally, as a native speaker would say it in this situation, keeping the speaker's tone, slang and jokes. Keep names, brands, code and numbers as they are.
        Write only the translation in each row, never a copy of the source text.
        Split each sentence's translation into exactly as many rows as the source sentence has, in the same order. Each row holds the part of the translation that says what its source row says, as far as natural word order allows. Do not leave a row empty unless its source row is only a filler word, and do not start a row with a comma.
        Answer with JSON only, no comments and no code fences: {"sentences":[{"id":<id>,"rows":["…", …]}, …]} with every id from the input.
        """.trimIndent()
}

/** The user message: the context sentences and the numbered sentences to translate. */
internal fun aiTranslationRequest(
    context: List<String>,
    sentences: List<AiSentence>,
): String =
    JSONObject()
        .put("context", JSONArray(context))
        .put(
            "sentences",
            JSONArray().apply {
                sentences.forEachIndexed { index, sentence ->
                    put(JSONObject().put("id", index + 1).put("rows", JSONArray(sentence.rows)))
                }
            },
        ).toString()

/**
 * The translated rows of each sentence by position, from the model's reply, or null where the reply
 * has no usable answer for it. A reply in a code fence or with words around the JSON is still read.
 */
internal fun parseAiTranslationReply(
    reply: String,
    sentences: List<AiSentence>,
): List<List<String>?> {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiTranslationException("The AI reply has no JSON.")
    val items =
        try {
            JSONObject(reply.substring(start, end + 1)).getJSONArray("sentences")
        } catch (error: JSONException) {
            throw AiTranslationException("The AI reply is not the JSON asked for: ${error.message}")
        }
    val byId = HashMap<Int, JSONObject>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        byId[item.optInt("id", -1)] = item
    }
    return sentences.mapIndexed { index, sentence -> byId[index + 1]?.let { aiRows(it, sentence) } }
}

/** The rows of one `{"id":…,"rows":[…]}` answer, or null when it has none. */
private fun aiRows(
    item: JSONObject,
    sentence: AiSentence,
): List<String>? {
    val rows = item.optJSONArray("rows") ?: return null
    val sources = sentence.rows + sentence.text
    val cleaned =
        (0 until rows.length())
            .map { withoutSourceEcho(cleanAiRow(rows.optString(it)), sources) }
            // A row that only copies the source, written next to its translation, is dropped.
            .let { all -> if (all.size > sentence.rows.size) all.filterNot { isSourceEcho(it, sources) } else all }
    return cleaned
        .takeIf { it.any { row -> row.isNotEmpty() && !isSourceEcho(row, sources) } }
        ?.let { fitRows(it, sentence.rows.size) }
}

/** Separators a model puts between a copied source and its translation ("…よね - I think…"). */
private const val ECHO_SEPARATORS = "-–—:：=→|/"

/**
 * [row] without a copy of one of [sources] written before its translation ("っていうスキルは…よね I think that
 * skill…", owner report 2026-10-29); the model sometimes repeats the source row. Only a copy long enough not to
 * be a kept name counts (see [canBeSourceEcho]), so "Netflix là…" stays whole.
 */
internal fun withoutSourceEcho(
    row: String,
    sources: List<String>,
): String {
    val trimmed = row.trim()
    val echoes = sources.map(String::trim).filter(::canBeSourceEcho).sortedByDescending { it.length }
    for (source in echoes) {
        if (trimmed.length <= source.length || !trimmed.startsWith(source)) continue
        val rest = trimmed.substring(source.length).trimStart { it.isWhitespace() || it in ECHO_SEPARATORS }
        if (rest.isNotEmpty()) return rest
    }
    return trimmed
}

/** Whether [row] is nothing but a copy of one of [sources]. */
private fun isSourceEcho(
    row: String,
    sources: List<String>,
): Boolean = canBeSourceEcho(row) && sources.any { it.trim() == row }

/** Three words or more, or four characters of a script written without spaces: too long to be a name kept as is. */
private fun canBeSourceEcho(text: String): Boolean =
    text.split(SPACES).count(String::isNotEmpty) >= 3 ||
        (text.length >= 4 && text.none(Char::isWhitespace) && writtenWithoutSpaces(text.first()))

private val SPACES = Regex("\\s+")

/**
 * Reads a reply while it streams in: each sentence's `{"id":…,"rows":[…]}` is handed over as soon as
 * its closing brace arrives, so its rows show while the model still writes the rest. Braces inside
 * strings are skipped; the object around the list has no id and is ignored.
 */
internal class AiReplyReader(
    private val sentences: List<AiSentence>,
) {
    private val text = StringBuilder()
    private val starts = ArrayDeque<Int>()
    private val done = HashSet<Int>()
    private var inString = false
    private var escaped = false

    /** Adds [piece] of the reply; returns the sentences it completed, by position, with their rows. */
    fun add(piece: String): List<Pair<Int, List<String>>> {
        val found = mutableListOf<Pair<Int, List<String>>>()
        val from = text.length
        text.append(piece)
        for (position in from until text.length) {
            val char = text[position]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                inString -> inString = char != '"'
                char == '"' -> inString = true
                char == '{' -> starts.addLast(position)
                char == '}' -> starts.removeLastOrNull()?.let { start -> sentenceAt(start, position)?.let(found::add) }
            }
        }
        return found
    }

    private fun sentenceAt(
        start: Int,
        end: Int,
    ): Pair<Int, List<String>>? {
        val item =
            try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: JSONException) {
                return null
            }
        val index = item.optInt("id", -1) - 1
        val sentence = sentences.getOrNull(index) ?: return null
        if (index in done) return null
        val rows = aiRows(item, sentence) ?: return null
        done += index
        return index to rows
    }
}

/** Plain text without markup and without a comma or semicolon left over at the start ("…, and the tone…"). */
internal fun cleanAiRow(row: String): String =
    row
        .replace(MARKUP, "")
        .trim()
        .trimStart(',', ';', '，', '、', '；')
        .trim()

/** Rows the model merged or split are joined into the expected count; the player then cuts them itself. */
private fun fitRows(
    rows: List<String>,
    expected: Int,
): List<String> = if (rows.size == expected) rows else listOf(rows.filter(String::isNotEmpty).joinToString(" "))

/** What the player reads for one sentence: the whole translation, and each row prefix's translation when the rows fit. */
internal fun aiTranslationEntries(
    sentence: AiSentence,
    rows: List<String>,
): Map<String, String> {
    val joined = joinRows(rows)
    if (rows.size != sentence.rows.size || rows.size == 1) return mapOf(sentence.text to joined)
    return mapOf(sentence.text to joined) + sentence.prefixes.mapIndexed { index, prefix -> prefix to joinRows(rows.take(index + 1)) }
}

/** Rows join with a space, except in scripts written without spaces. */
private fun joinRows(rows: List<String>): String {
    val parts = rows.filter(String::isNotEmpty)
    return parts.drop(1).fold(parts.firstOrNull().orEmpty()) { joined, next ->
        val glue = if (writtenWithoutSpaces(joined.lastOrNull()) && writtenWithoutSpaces(next.firstOrNull())) "" else " "
        joined + glue + next
    }
}

private fun writtenWithoutSpaces(char: Char?): Boolean =
    char != null &&
        when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            -> true
            else -> false
        }

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code

/** Splits [sentences] into requests of at most [MAX_AI_BATCH_SENTENCES] sentences and [MAX_AI_BATCH_CHARS] characters. */
internal fun aiBatches(sentences: List<AiSentence>): List<List<AiSentence>> {
    val batches = mutableListOf<MutableList<AiSentence>>()
    var characters = 0
    sentences.forEach { sentence ->
        val current = batches.lastOrNull()
        if (current == null || current.size >= MAX_AI_BATCH_SENTENCES || characters + sentence.text.length > MAX_AI_BATCH_CHARS) {
            batches += mutableListOf(sentence)
            characters = sentence.text.length
        } else {
            current += sentence
            characters += sentence.text.length
        }
    }
    return batches
}

/**
 * Translates subtitle sentences with [model] and keeps the results in memory and in [cacheDirectory].
 * The player reads them with [cachedTranslation]; texts the model did not translate return null, so
 * Google translates them.
 */
internal class AiSubtitleTranslator(
    cacheDirectory: File?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = TranslationCache(maxEntries = 2_048, maxCharacters = 512_000)

    // Larger than Google's: every AI request counts against the user's key, so watching again costs nothing.
    private val diskCache = cacheDirectory?.let { TranslationDiskCache(it, maxEntries = 20_000, maxBytes = 16L * 1024 * 1024) }
    private val requests = Mutex()

    /** Raised each time translations arrive, so rows already showing Google's lines take the AI's. */
    private val arrivals = MutableStateFlow(0L)
    val updates: StateFlow<Long> = arrivals.asStateFlow()

    /** The last sentences sent for each language pair and model, the next request's context. */
    private val recent = HashMap<String, List<String>>()

    /**
     * Sentences already sent and answered: one the model left out is never asked again, so each row
     * that follows does not send a new request for it (Google translates it).
     */
    private val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sentences of a request that is waiting or running: another call does not ask for them again. */
    private val asking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var restUntil = Long.MIN_VALUE

    private val failure = MutableStateFlow<String?>(null)

    /**
     * Why the last request failed (the service's own words), or null once one succeeds. Google's lines
     * show meanwhile, so without this an AI that stopped working looks like plain Google (owner report
     * 2026-10-29); the top-right icon shows it.
     */
    val problem: StateFlow<String?> = failure.asStateFlow()

    /** Ends the rest after a failure, so the next row asks the AI at once. */
    fun tryAgain() {
        restUntil = Long.MIN_VALUE
        failure.value = null
    }

    /** What the AI has translated [text] to in this app run, without reading the disk; null when nothing yet. */
    fun shownTranslation(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String? {
        val (source, target) = pair(model, sourceLanguageCode, targetLanguageCode)
        return cache.get(source, target, text)?.let { withoutSourceEcho(it, listOf(text)) }
    }

    suspend fun cachedTranslation(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String? {
        val (source, target) = pair(model, sourceLanguageCode, targetLanguageCode)
        if (text.isBlank()) return text
        // Lines cached before echoes were removed (v1.8.6 and older) are cleaned as they are read.
        val translation =
            cache.get(source, target, text)
                ?: withContext(Dispatchers.IO) { diskCache?.get(source, target, text) }?.also { cache.put(source, target, text, it) }
        return translation?.let { withoutSourceEcho(it, listOf(text)) }
    }

    /**
     * Translates the sentences of [texts] not translated yet, in as few requests as needed. The last
     * request is filled up to [MAX_AI_BATCH_SENTENCES] with sentences from [ahead] (read only when a
     * request is needed), so a window of one minute does not cost a request of its own and later
     * minutes need fewer requests: free keys count requests, not their size.
     */
    suspend fun translateAll(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        ahead: (suspend () -> List<String>)? = null,
    ) {
        val needed = missing(model, sourceLanguageCode, targetLanguageCode, texts)
        if (needed.isEmpty()) return
        val neededTexts = needed.mapTo(HashSet()) { it.text }
        val extra =
            ahead
                ?.invoke()
                ?.let { missing(model, sourceLanguageCode, targetLanguageCode, it) }
                .orEmpty()
                .filter { it.text !in neededTexts }
        aiBatches(needed + extra).takeWhile { batch -> batch.any { it.text in neededTexts } }.forEach { batch ->
            translate(model, sourceLanguageCode, targetLanguageCode, batch)
        }
    }

    /** Sends one request at most, for the first sentences of [texts] not translated yet; returns the texts it covered. */
    suspend fun translateNextBatch(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
    ): List<String> {
        val batch = aiBatches(missing(model, sourceLanguageCode, targetLanguageCode, texts)).firstOrNull() ?: return emptyList()
        translate(model, sourceLanguageCode, targetLanguageCode, batch)
        return batch.flatMap { listOf(it.text) + it.prefixes }
    }

    private suspend fun missing(
        model: AiTranslationModel,
        source: String,
        target: String,
        texts: List<String>,
    ): List<AiSentence> {
        if (TranslationLanguages.normalize(source) == TranslationLanguages.normalize(target) || now() < restUntil) return emptyList()
        val (scopedSource, scopedTarget) = pair(model, source, target)
        return aiSentences(texts).filter { sentence ->
            val key = attemptKey(scopedSource, scopedTarget, sentence.text)
            key !in attempted && key !in asking && cachedTranslation(model, source, target, sentence.text) == null
        }
    }

    private suspend fun translate(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        batch: List<AiSentence>,
    ) {
        val (source, target) = pair(model, sourceLanguageCode, targetLanguageCode)
        val keys = batch.map { attemptKey(source, target, it.text) }
        asking += keys
        try {
            requests.withLock { translateNow(model, sourceLanguageCode to targetLanguageCode, source to target, batch) }
        } catch (error: IOException) {
            failure.value = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            throw error
        } finally {
            asking -= keys.toSet()
        }
    }

    /** Sends one request; each sentence is cached as soon as the model has written it. */
    private suspend fun translateNow(
        model: AiTranslationModel,
        languages: Pair<String, String>,
        scoped: Pair<String, String>,
        batch: List<AiSentence>,
    ) {
        val (source, target) = scoped
        val pending = batch.filter { cache.get(source, target, it.text) == null && attemptKey(source, target, it.text) !in attempted }
        if (pending.isEmpty() || now() < restUntil) return
        val contextKey = "$source>$target"
        val found = ConcurrentHashMap<String, String>()
        val translated = ConcurrentHashMap.newKeySet<Int>()

        fun keep(
            index: Int,
            rows: List<String>,
        ) {
            if (!translated.add(index)) return
            val entries = aiTranslationEntries(pending[index], rows)
            entries.forEach { (text, translation) -> cache.put(source, target, text, translation) }
            found += entries
            arrivals.update { it + 1 }
        }
        val reader = AiReplyReader(pending)
        val reply =
            try {
                model.ask(
                    aiTranslationSystemPrompt(
                        TranslationLanguages.normalize(languages.first),
                        TranslationLanguages.normalize(languages.second),
                    ),
                    aiTranslationRequest(recent[contextKey].orEmpty(), pending),
                ) { piece -> reader.add(piece).forEach { (index, rows) -> keep(index, rows) } }
            } catch (error: IOException) {
                restUntil = now() + AI_FAILURE_REST_MS
                // Sentences that arrived before the failure stay; the others are asked again after the rest.
                translated.forEach { attempted += attemptKey(source, target, pending[it].text) }
                save(scoped, found)
                throw error
            }
        pending.forEach { attempted += attemptKey(source, target, it.text) }
        // A reply that did not stream, or a sentence the reader could not place, is read whole.
        val whole =
            try {
                parseAiTranslationReply(reply, pending)
            } catch (error: AiTranslationException) {
                if (found.isEmpty()) throw error
                emptyList()
            }
        whole.forEachIndexed { index, rows -> if (rows != null) keep(index, rows) }
        if (found.isEmpty()) throw AiTranslationException("The AI reply translated none of the ${pending.size} sentences.")
        recent[contextKey] = pending.takeLast(AI_CONTEXT_SENTENCES).map { it.text }
        failure.value = null
        save(scoped, found)
    }

    private suspend fun save(
        scoped: Pair<String, String>,
        found: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        found.forEach { (text, translation) -> diskCache?.put(scoped.first, scoped.second, text, translation) }
    }

    private fun attemptKey(
        source: String,
        target: String,
        text: String,
    ) = "$source>$target:$text"

    /** Cache keys: the service and model are part of the source, so another model translates again. */
    private fun pair(
        model: AiTranslationModel,
        source: String,
        target: String,
    ): Pair<String, String> =
        "${model.scope}:p$AI_PROMPT_VERSION:${TranslationLanguages.normalize(source)}" to TranslationLanguages.normalize(target)
}
