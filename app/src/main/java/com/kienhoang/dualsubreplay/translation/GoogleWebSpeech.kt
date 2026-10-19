package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/*
 * Online voice: the speech endpoint Google Translate's web page reads text aloud with. Like the
 * translation endpoints it needs no key but is not an official API, so callers fall back to the
 * phone's own voices whenever it fails. Every language uses it while Google translates subtitles
 * (owner decision 2026-10-19): many phones lack a voice for the language learned, Chinese above all,
 * and Google's voices sound natural. When no address answers, the phone speaks at once for a while.
 */

/** Addresses in the order they are tried; the one that last answered is tried first. */
internal enum class GoogleSpeechEndpoint(
    val url: String,
    val client: String,
) {
    API("https://translate.googleapis.com/translate_tts", "gtx"),
    WEB("https://translate.google.com/translate_tts", "tw-ob"),
    EXTENSION("https://clients5.google.com/translate_tts", "gtx"),
}

/** Google reads at most about 200 characters per request; longer text is left to the phone. */
internal const val MAX_GOOGLE_SPEECH_CHARS = 200

/** A spoken word is a few kilobytes of MP3; anything far larger is not speech. */
internal const val MAX_GOOGLE_SPEECH_BYTES = 512 * 1024
private const val GOOGLE_SPEECH_TIMEOUT_MS = 8_000L

/** A short connect timeout, so an unreachable address hands over to the next one quickly. */
private const val GOOGLE_SPEECH_CONNECT_TIMEOUT_MS = 3_000L

/**
 * Google's voice for a subtitle [language], or null when the phone's voices are used instead.
 * Simplified or mainland Chinese is read with Google's mainland voice, traditional or Taiwanese
 * Chinese with its Taiwanese voice; Hong Kong and Macau text is often Cantonese, so it stays with
 * the phone. Other languages use Google's voice of that language; Google answers a language it has
 * no voice for with an error, and the phone speaks instead.
 */
internal fun googleSpeechLanguage(language: String): String? {
    val locale = Locale.forLanguageTag(language.trim().replace('_', '-'))
    return when {
        locale.language.isBlank() || locale.language in setOf("und", "auto", "yue") -> null
        locale.language != "zh" && locale.language != "cmn" -> locale.language
        locale.country in setOf("HK", "MO") -> null
        locale.script == "Hant" || locale.country == "TW" -> "zh-TW"
        else -> "zh-CN"
    }
}

/** Whether [text] in [language] is spoken by Google's voice: only while Google translates subtitles (the default). */
internal fun speaksWithGoogle(
    text: String,
    language: String,
    engine: TranslationEngine,
): Boolean =
    engine == TranslationEngine.GOOGLE_WEB &&
        text.isNotBlank() &&
        text.trim().length <= MAX_GOOGLE_SPEECH_CHARS &&
        googleSpeechLanguage(language) != null

internal fun googleSpeechUrl(
    text: String,
    googleLanguage: String,
    endpoint: GoogleSpeechEndpoint = GoogleSpeechEndpoint.API,
): HttpUrl =
    endpoint.url
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("ie", "UTF-8")
        .addQueryParameter("client", endpoint.client)
        .addQueryParameter("tl", googleLanguage)
        .addQueryParameter("q", text.trim())
        .build()

/** Whether [bytes] start like an MP3 file: an ID3 tag or an MPEG audio frame. */
internal fun looksLikeMp3(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        (
            (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
                (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0)
        )

/** What one address answered: audio, an answer without audio (no voice for the text), or nothing (unreachable or refused). */
private sealed interface SpeechReply {
    class Audio(
        val bytes: ByteArray,
    ) : SpeechReply

    data object NoAudio : SpeechReply

    data object Down : SpeechReply
}

/**
 * Remembers, for every [GoogleWebSpeech] in the app, when no Google address answered, so the phone
 * speaks at once for [restMs] instead of waiting for Google on every word.
 */
internal class GoogleSpeechHealth(
    private val restMs: Long = GOOGLE_RECHECK_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var downSince: Long? = null

    @Volatile var preferred = 0

    fun isResting(): Boolean = downSince?.let { now() - it < restMs } == true

    fun markDown() {
        downSince = now()
    }

    fun markUp(endpoint: Int) {
        downSince = null
        preferred = endpoint
    }

    companion object {
        val Shared = GoogleSpeechHealth()
    }
}

class GoogleWebSpeech internal constructor(
    private val client: OkHttpClient,
    private val health: GoogleSpeechHealth,
) {
    constructor() : this(
        OkHttpClient
            .Builder()
            .connectTimeout(GOOGLE_SPEECH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(GOOGLE_SPEECH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(GOOGLE_SPEECH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
        GoogleSpeechHealth.Shared,
    )

    /**
     * Writes Google's MP3 of [text] in [language] to [file], trying each address; false when Google has
     * no voice for it, or no address answered (then the next words skip Google for a while).
     */
    suspend fun record(
        text: String,
        language: String,
        file: File,
    ): Boolean {
        val googleLanguage = googleSpeechLanguage(language) ?: return false
        if (text.isBlank() || text.trim().length > MAX_GOOGLE_SPEECH_CHARS || health.isResting()) return false
        val endpoints = GoogleSpeechEndpoint.entries
        var answered = false
        for (index in googleClientOrder(health.preferred, endpoints.size)) {
            val request =
                Request
                    .Builder()
                    .url(googleSpeechUrl(text, googleLanguage, endpoints[index]))
                    .get()
                    .build()
            when (val reply = download(request)) {
                is SpeechReply.Audio -> {
                    health.markUp(index)
                    return withContext(Dispatchers.IO) { runCatching { file.writeBytes(reply.bytes) }.isSuccess }
                }
                SpeechReply.NoAudio -> answered = true
                SpeechReply.Down -> Unit
            }
        }
        // An address that answered without audio has no voice for this text; that is not an outage.
        if (!answered) health.markDown()
        return false
    }

    private suspend fun download(request: Request): SpeechReply =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) continuation.resume(SpeechReply.Down)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val reply = response.use(::readSpeechReply)
                        if (continuation.isActive) continuation.resume(reply)
                    }
                },
            )
        }

    private fun readSpeechReply(response: Response): SpeechReply {
        if (isBlockedGoogleStatus(response.code) || response.code >= 500) return SpeechReply.Down
        val stream = response.body?.byteStream()
        val bytes = if (!response.isSuccessful || stream == null) null else runCatching { readBytesWithLimit(stream) }.getOrNull()
        return bytes?.takeIf(::looksLikeMp3)?.let(SpeechReply::Audio) ?: SpeechReply.NoAudio
    }
}

/** The whole of [input], or null once it passes [maxBytes]. */
internal fun readBytesWithLimit(
    input: InputStream,
    maxBytes: Int = MAX_GOOGLE_SPEECH_BYTES,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > maxBytes) return null
        output.write(buffer, 0, count)
    }
}
