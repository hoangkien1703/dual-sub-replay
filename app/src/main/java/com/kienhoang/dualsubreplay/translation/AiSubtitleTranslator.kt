package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * Settings → Translation → Translate with AI (off by default, owner request 2026-10-28): the AI service
 * and key from the AI assistant's settings translate subtitles, as 4You's own server does. A request
 * carries a window of sentences, each split into the rows the screen shows, plus the sentences before
 * them as context only; the model answers with one translation per row. Rows then get exactly the
 * words meant for them instead of a character-count cut of one Google sentence. Google stays the
 * fallback: whatever the model leaves out or fails on is translated by Google as before.
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

/** Sentences of the previous request sent again as context, so pronouns and tone carry over. */
internal const val AI_CONTEXT_SENTENCES = 4

/** A model the user set up for the AI assistant. [scope] (service and model) keeps their translations apart. */
internal interface AiTranslationModel {
    val scope: String

    /** Sends one request and returns the reply text, or throws an [IOException]. */
    suspend fun ask(
        system: String,
        user: String,
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
): String =
    """
    You translate video subtitles from ${languageName(
        source,
    )} ($source) to ${languageName(target)} ($target) for someone learning ${languageName(source)} who reads ${languageName(target)}.
    The input is JSON: "context" holds earlier sentences of the same video, for meaning only; "sentences" holds the sentences to translate, each split into the rows the screen shows.
    For every sentence, translate the whole sentence naturally, as a native speaker would say it in this situation, keeping the speaker's tone, slang and jokes. Keep names, brands, code and numbers as they are.
    Then split your translation into exactly as many rows as the source sentence has, in the same order. Each row holds the part of your translation that says what its source row says, as far as natural word order allows. Do not leave a row empty unless its source row is only a filler word.
    Answer with JSON only, no comments and no code fences: {"sentences":[{"id":<id>,"rows":["…", …]}, …]} with every id from the input.
    """.trimIndent()

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
    val byId = HashMap<Int, List<String>>()
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val rows = item.optJSONArray("rows") ?: continue
        byId[item.optInt("id", -1)] = (0 until rows.length()).map { rows.optString(it).replace(MARKUP, "").trim() }
    }
    return sentences.mapIndexed { index, sentence ->
        byId[index + 1]?.takeIf { rows -> rows.any(String::isNotEmpty) }?.let { rows -> fitRows(rows, sentence.rows.size) }
    }
}

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

    /** The last sentences sent for each language pair and model, the next request's context. */
    private val recent = HashMap<String, List<String>>()

    /**
     * Sentences already sent and answered: one the model left out is never asked again, so each row
     * that follows does not send a new request for it (Google translates it).
     */
    private val attempted: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    @Volatile private var restUntil = Long.MIN_VALUE

    suspend fun cachedTranslation(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String? {
        val (source, target) = pair(model, sourceLanguageCode, targetLanguageCode)
        if (text.isBlank()) return text
        return cache.get(source, target, text)
            ?: withContext(Dispatchers.IO) { diskCache?.get(source, target, text) }?.also { cache.put(source, target, text, it) }
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
            attemptKey(scopedSource, scopedTarget, sentence.text) !in attempted &&
                cachedTranslation(model, source, target, sentence.text) == null
        }
    }

    private suspend fun translate(
        model: AiTranslationModel,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        batch: List<AiSentence>,
    ) = requests.withLock {
        val (source, target) = pair(model, sourceLanguageCode, targetLanguageCode)
        val pending = batch.filter { cache.get(source, target, it.text) == null && attemptKey(source, target, it.text) !in attempted }
        if (pending.isEmpty() || now() < restUntil) return@withLock
        val contextKey = "$source>$target"
        val reply =
            try {
                model.ask(
                    aiTranslationSystemPrompt(
                        TranslationLanguages.normalize(sourceLanguageCode),
                        TranslationLanguages.normalize(targetLanguageCode),
                    ),
                    aiTranslationRequest(recent[contextKey].orEmpty(), pending),
                )
            } catch (error: IOException) {
                restUntil = now() + AI_FAILURE_REST_MS
                throw error
            }
        pending.forEach { attempted += attemptKey(source, target, it.text) }
        val found =
            pending
                .zip(parseAiTranslationReply(reply, pending))
                .mapNotNull { (sentence, rows) -> rows?.let { aiTranslationEntries(sentence, it) } }
                .fold(emptyMap<String, String>()) { all, entries -> all + entries }
        if (found.isEmpty()) throw AiTranslationException("The AI reply translated none of the ${pending.size} sentences.")
        recent[contextKey] = pending.takeLast(AI_CONTEXT_SENTENCES).map { it.text }
        found.forEach { (text, translation) -> cache.put(source, target, text, translation) }
        withContext(Dispatchers.IO) { found.forEach { (text, translation) -> diskCache?.put(source, target, text, translation) } }
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
    ): Pair<String, String> = "${model.scope}:${TranslationLanguages.normalize(source)}" to TranslationLanguages.normalize(target)
}
