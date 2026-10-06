package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ResponseLimitExceededException
import com.kienhoang.dualsubreplay.data.readUtf8WithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Online engine: Google Translate's free web endpoints, the ones many browser extensions use. They
 * need no key but are not an official API, so Google can throttle or block them at any time. Like
 * those extensions, the app sends many texts per request, retries short outages, and tells the user
 * when it still fails so they can switch to on-device translation.
 */

internal const val MAX_GOOGLE_RESPONSE_BYTES = 1024 * 1024
private const val GOOGLE_REQUEST_TIMEOUT_MS = 15_000L

/** One request carries at most this many texts and characters, well under the endpoints' limits. */
internal const val MAX_GOOGLE_BATCH_TEXTS = 32
internal const val MAX_GOOGLE_BATCH_CHARS = 4_000

/** Pauses before retrying a throttled, failing or unreachable Google; after the last one the error is shown. */
internal val GOOGLE_RETRY_DELAYS_MS = listOf(2_000L, 6_000L)

/**
 * Endpoints in the order they are tried. A refused one (HTTP 403/429) hands over to the next, and the
 * one that last worked is tried first. The batch endpoints translate many texts in one request.
 */
internal enum class GoogleEndpoint(
    val url: String,
    val client: String,
    val batch: Boolean,
) {
    BATCH("https://translate.googleapis.com/translate_a/t", "gtx", batch = true),
    EXTENSION_BATCH("https://clients5.google.com/translate_a/t", "dict-chrome-ex", batch = true),

    /** One text per request; since September 2026 `gtx` is refused here for some users, so it uses `at`. */
    SINGLE("https://translate.googleapis.com/translate_a/single", "at", batch = false),
}

class GoogleTranslateException(
    message: String,
    cause: Throwable? = null,
    /** Throttling, a server error or no connection: worth retrying after a pause. */
    val retryable: Boolean = false,
) : IOException(message, cause)

/** Google's language codes match the app's except for Chinese. */
internal fun googleLanguageCode(appCode: String): String =
    when (val normalized = TranslationLanguages.normalize(appCode)) {
        "zh" -> "zh-CN"
        else -> normalized
    }

/** Google answers a refused client with 403 or 429 ("automated queries"); another endpoint may still work. */
internal fun isBlockedGoogleStatus(code: Int): Boolean = code == 403 || code == 429

/** Endpoint indices to try, starting with the one that last worked. */
internal fun googleClientOrder(
    preferred: Int,
    count: Int,
): List<Int> = (0 until count).map { (it + preferred.coerceIn(0, maxOf(0, count - 1))) % count }

/** Splits [texts] into requests of at most [maxTexts] texts and [maxChars] characters (a longer text goes alone). */
internal fun googleBatches(
    texts: List<String>,
    maxTexts: Int = MAX_GOOGLE_BATCH_TEXTS,
    maxChars: Int = MAX_GOOGLE_BATCH_CHARS,
): List<List<String>> {
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var chars = 0
    for (text in texts) {
        if (current.isNotEmpty() && (current.size >= maxTexts || chars + text.length > maxChars)) {
            batches += current
            current = mutableListOf()
            chars = 0
        }
        current += text
        chars += text.length
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** Reads a batch reply: one entry per text, either the translation or `[translation, detected language]`. */
internal fun parseGoogleBatchTranslation(
    body: String,
    count: Int,
): List<String> {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("[")) throw GoogleTranslateException("Google Translate returned a web page instead of a translation.")
    val entries =
        try {
            JSONArray(trimmed)
        } catch (error: JSONException) {
            throw GoogleTranslateException("Google Translate returned an unreadable reply.", error)
        }
    if (entries.length() != count) throw GoogleTranslateException("Google Translate returned ${entries.length()} of $count translations.")
    return (0 until count).map { index ->
        val entry = entries.opt(index)
        val text = (if (entry is JSONArray) entry.opt(0) else entry) as? String
        text?.trim()?.takeIf { it.isNotEmpty() } ?: throw GoogleTranslateException("Google Translate returned an empty translation.")
    }
}

/** Joins the translated text of every sentence segment in Google's `[[["text","source",…],…],…]` reply. */
internal fun parseGoogleTranslation(body: String): String {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("[")) throw GoogleTranslateException("Google Translate returned a web page instead of a translation.")
    val segments =
        try {
            JSONArray(trimmed).optJSONArray(0)
        } catch (error: JSONException) {
            throw GoogleTranslateException("Google Translate returned an unreadable reply.", error)
        } ?: throw GoogleTranslateException("Google Translate returned no translation.")
    val translated =
        buildString {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                if (!segment.isNull(0)) append(segment.optString(0))
            }
        }.trim()
    if (translated.isEmpty()) throw GoogleTranslateException("Google Translate returned an empty translation.")
    return translated
}

internal fun googleTranslateUrl(
    endpoint: GoogleEndpoint,
    source: String,
    target: String,
): HttpUrl =
    endpoint.url
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("client", endpoint.client)
        .addQueryParameter("sl", source)
        .addQueryParameter("tl", target)
        .apply { if (!endpoint.batch) addQueryParameter("dt", "t") }
        .build()

class GoogleWebTranslator(
    cacheDirectory: File? = null,
    private val retryDelaysMs: List<Long> = GOOGLE_RETRY_DELAYS_MS,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(GOOGLE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(GOOGLE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(GOOGLE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
) {
    // Separate from the on-device caches, so switching engines never shows the other engine's result.
    private val diskCache = cacheDirectory?.let { TranslationDiskCache(it) }
    private val cache = TranslationCache()

    @Volatile private var preferredEndpoint = 0

    suspend fun translate(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String = translateAll(sourceLanguageCode, targetLanguageCode, listOf(text)).single()

    /** Translates [texts] in as few requests as possible; cached and repeated texts are not sent again. */
    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
    ): List<String> {
        val source = TranslationLanguages.normalize(sourceLanguageCode)
        val target = TranslationLanguages.normalize(targetLanguageCode)
        if (source == target) return texts
        val results = HashMap<String, String>()
        val missing = LinkedHashSet<String>()
        for (text in texts.distinct()) {
            val known = if (text.isBlank()) text else cached(source, target, text)
            if (known != null) results[text] = known else missing += text
        }
        for (batch in googleBatches(missing.toList())) {
            val translated = requestWithRetry(googleLanguageCode(source), googleLanguageCode(target), batch)
            batch.zip(translated).forEach { (text, translation) ->
                results[text] = translation
                cache.put(source, target, text, translation)
            }
            withContext(Dispatchers.IO) {
                batch.zip(translated).forEach { (text, translation) ->
                    diskCache?.put(source, target, text, translation)
                }
            }
        }
        return texts.map(results::getValue)
    }

    private suspend fun cached(
        source: String,
        target: String,
        text: String,
    ): String? =
        cache.get(source, target, text)
            ?: withContext(Dispatchers.IO) { diskCache?.get(source, target, text) }?.also { cache.put(source, target, text, it) }

    private suspend fun requestWithRetry(
        source: String,
        target: String,
        texts: List<String>,
    ): List<String> {
        var attempt = 0
        while (true) {
            try {
                return request(source, target, texts)
            } catch (error: GoogleTranslateException) {
                if (!error.retryable || attempt >= retryDelaysMs.size) throw error
                delay(retryDelaysMs[attempt++])
            }
        }
    }

    private suspend fun request(
        source: String,
        target: String,
        texts: List<String>,
    ): List<String> {
        var refused: GoogleTranslateException? = null
        val endpoints = GoogleEndpoint.entries
        for (index in googleClientOrder(preferredEndpoint, endpoints.size)) {
            val endpoint = endpoints[index]
            val translated =
                if (endpoint.batch) {
                    send(endpoint, source, target, texts)?.let { parseGoogleBatchTranslation(it, texts.size) }
                } else {
                    sendEach(endpoint, source, target, texts)
                }
            if (translated == null) {
                refused = GoogleTranslateException("Google Translate refused the request (HTTP 429 or 403).", retryable = true)
                continue
            }
            preferredEndpoint = index
            return translated
        }
        throw checkNotNull(refused)
    }

    /** One request per text, for the endpoint without batches; null when it refused one. */
    private suspend fun sendEach(
        endpoint: GoogleEndpoint,
        source: String,
        target: String,
        texts: List<String>,
    ): List<String>? {
        val translated = ArrayList<String>(texts.size)
        for (text in texts) translated += parseGoogleTranslation(send(endpoint, source, target, listOf(text)) ?: return null)
        return translated
    }

    /** The reply body, or null when this endpoint refused the request. */
    private suspend fun send(
        endpoint: GoogleEndpoint,
        source: String,
        target: String,
        texts: List<String>,
    ): String? {
        val form = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val request =
            Request
                .Builder()
                .url(googleTranslateUrl(endpoint, source, target))
                .post(form)
                .build()
        val (code, body) = execute(request)
        if (isBlockedGoogleStatus(code)) return null
        if (code !in 200..299) throw GoogleTranslateException("Google Translate returned HTTP $code.", retryable = code >= 500)
        return body
    }

    private suspend fun execute(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                GoogleTranslateException("Google Translate could not be reached.", error, retryable = true),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result = runCatching { response.use(::readReply) }
                        if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                },
            )
        }

    private fun readReply(response: Response): Pair<Int, String> {
        if (!response.isSuccessful) return response.code to ""
        val body = response.body ?: throw GoogleTranslateException("Google Translate returned an empty reply.")
        return try {
            response.code to body.byteStream().use { readUtf8WithLimit(it, MAX_GOOGLE_RESPONSE_BYTES) }
        } catch (error: ResponseLimitExceededException) {
            throw GoogleTranslateException("Google Translate returned a reply larger than 1 MiB.", error)
        }
    }
}
