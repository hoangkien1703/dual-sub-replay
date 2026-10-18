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
 * phone's own voices whenever it fails. Used for Chinese, where many phones have no Mandarin
 * voice installed and Google's voice sounds natural.
 */

internal const val GOOGLE_SPEECH_URL = "https://translate.googleapis.com/translate_tts"

/** Google reads at most about 200 characters per request; longer text is left to the phone. */
internal const val MAX_GOOGLE_SPEECH_CHARS = 200

/** A spoken word is a few kilobytes of MP3; anything far larger is not speech. */
internal const val MAX_GOOGLE_SPEECH_BYTES = 512 * 1024
private const val GOOGLE_SPEECH_TIMEOUT_MS = 8_000L

/**
 * Google's voice for a subtitle [language], or null when the phone's voices are used instead.
 * Only Mandarin: simplified or mainland Chinese is read with Google's mainland voice, traditional
 * or Taiwanese Chinese with its Taiwanese voice. Hong Kong and Macau text is often Cantonese, so it
 * stays with the phone.
 */
internal fun googleSpeechLanguage(language: String): String? {
    val locale = Locale.forLanguageTag(language.trim().replace('_', '-'))
    if (locale.language != "zh" && locale.language != "cmn") return null
    return when {
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
): HttpUrl =
    GOOGLE_SPEECH_URL
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("ie", "UTF-8")
        .addQueryParameter("client", "gtx")
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

class GoogleWebSpeech(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(GOOGLE_SPEECH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(GOOGLE_SPEECH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(GOOGLE_SPEECH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
) {
    /** Writes Google's MP3 of [text] in [language] to [file]; false when Google has no voice for it or fails. */
    suspend fun record(
        text: String,
        language: String,
        file: File,
    ): Boolean {
        val googleLanguage = googleSpeechLanguage(language) ?: return false
        if (text.isBlank() || text.trim().length > MAX_GOOGLE_SPEECH_CHARS) return false
        val request =
            Request
                .Builder()
                .url(googleSpeechUrl(text, googleLanguage))
                .get()
                .build()
        val audio = download(request)?.takeIf(::looksLikeMp3) ?: return false
        return withContext(Dispatchers.IO) { runCatching { file.writeBytes(audio) }.isSuccess }
    }

    private suspend fun download(request: Request): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val body =
                            response.use {
                                val stream = it.body?.byteStream()
                                if (!it.isSuccessful || stream == null) null else runCatching { readBytesWithLimit(stream) }.getOrNull()
                            }
                        if (continuation.isActive) continuation.resume(body)
                    }
                },
            )
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
