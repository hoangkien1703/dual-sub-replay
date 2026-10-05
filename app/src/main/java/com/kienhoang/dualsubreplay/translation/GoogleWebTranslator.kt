package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ResponseLimitExceededException
import com.kienhoang.dualsubreplay.data.readUtf8WithLimit
import kotlinx.coroutines.Dispatchers
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
 * Opt-in online engine: Google Translate's free web endpoint, the one many browser extensions use.
 * It needs no key but is not an official API, so Google can throttle or block it at any time. The
 * app tells the user when it fails and offers to switch back to on-device translation.
 */

private const val GOOGLE_WEB_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
internal const val MAX_GOOGLE_RESPONSE_BYTES = 1024 * 1024
private const val GOOGLE_REQUEST_TIMEOUT_MS = 15_000L

/** Client ids the endpoint accepts. Since September 2026 `gtx` is refused for some users, so `at` comes first. */
internal val GOOGLE_WEB_CLIENTS = listOf("at", "gtx")

class GoogleTranslateException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Google's language codes match the app's except for Chinese. */
internal fun googleLanguageCode(appCode: String): String =
    when (val normalized = TranslationLanguages.normalize(appCode)) {
        "zh" -> "zh-CN"
        else -> normalized
    }

/** Google answers a refused client with 403 or 429 ("automated queries"); another client may still work. */
internal fun isBlockedGoogleStatus(code: Int): Boolean = code == 403 || code == 429

/** Client indices to try, starting with the one that last worked. */
internal fun googleClientOrder(
    preferred: Int,
    count: Int,
): List<Int> = (0 until count).map { (it + preferred.coerceIn(0, maxOf(0, count - 1))) % count }

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
    client: String,
    source: String,
    target: String,
): HttpUrl =
    GOOGLE_WEB_TRANSLATE_URL
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("client", client)
        .addQueryParameter("sl", source)
        .addQueryParameter("tl", target)
        .addQueryParameter("dt", "t")
        .build()

class GoogleWebTranslator(
    cacheDirectory: File? = null,
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

    @Volatile private var preferredClient = 0

    suspend fun translate(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String {
        val source = TranslationLanguages.normalize(sourceLanguageCode)
        val target = TranslationLanguages.normalize(targetLanguageCode)
        if (text.isBlank() || source == target) return text
        cache.get(source, target, text)?.let { return it }
        withContext(Dispatchers.IO) { diskCache?.get(source, target, text) }?.let { cached ->
            cache.put(source, target, text, cached)
            return cached
        }
        val translated = request(googleLanguageCode(source), googleLanguageCode(target), text)
        cache.put(source, target, text, translated)
        withContext(Dispatchers.IO) { diskCache?.put(source, target, text, translated) }
        return translated
    }

    private suspend fun request(
        source: String,
        target: String,
        text: String,
    ): String {
        var refused: GoogleTranslateException? = null
        for (index in googleClientOrder(preferredClient, GOOGLE_WEB_CLIENTS.size)) {
            val request =
                Request
                    .Builder()
                    .url(googleTranslateUrl(GOOGLE_WEB_CLIENTS[index], source, target))
                    .post(FormBody.Builder().add("q", text).build())
                    .build()
            val (code, body) = execute(request)
            if (isBlockedGoogleStatus(code)) {
                refused = GoogleTranslateException("Google Translate refused the request (HTTP $code).")
                continue
            }
            if (code !in 200..299) throw GoogleTranslateException("Google Translate returned HTTP $code.")
            preferredClient = index
            return parseGoogleTranslation(body)
        }
        throw checkNotNull(refused)
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
                            continuation.resumeWithException(GoogleTranslateException("Google Translate could not be reached.", error))
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
