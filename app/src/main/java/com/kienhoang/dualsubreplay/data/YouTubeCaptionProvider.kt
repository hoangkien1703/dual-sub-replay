package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal const val MAX_YOUTUBE_RESPONSE_BYTES = 8 * 1024 * 1024

internal class ResponseLimitExceededException(message: String) : Exception(message)

internal fun trustedYouTubeCaptionUrl(url: String): HttpUrl? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    val trustedHost = parsed.host == "youtube.com" || parsed.host.endsWith(".youtube.com")
    return parsed.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && trustedHost
    }
}

internal fun readUtf8WithLimit(
    input: InputStream,
    maxBytes: Int = MAX_YOUTUBE_RESPONSE_BYTES,
): String {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count > maxBytes - totalBytes) {
            throw ResponseLimitExceededException(
                "YouTube returned a response larger than the 8 MiB safety limit.",
            )
        }
        output.write(buffer, 0, count)
        totalBytes += count
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

/**
 * Retrieves public caption tracks from YouTube's undocumented Innertube endpoint.
 * This is deliberately isolated because YouTube can change the endpoint at any time.
 */
class YouTubeCaptionProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) : CaptionProvider {

    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
    ): CaptionTrackResult = withContext(Dispatchers.IO) {
        runCatching { fetchInternal(videoId, preferredLanguages) }
            .getOrElse { error ->
                if (error is CaptionUnavailableException) throw error
                if (error is ResponseLimitExceededException) {
                    throw CaptionUnavailableException(error.message.orEmpty(), error)
                }
                throw CaptionUnavailableException(
                    "Captions could not be loaded. YouTube may have changed its transcript service.",
                    error,
                )
            }
    }

    private fun fetchInternal(videoId: String, preferredLanguages: List<String>): CaptionTrackResult {
        val watchHtml = executeText(
            Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                .header("User-Agent", WEB_USER_AGENT)
                .build(),
        )
        val apiKey = INNERTUBE_KEY_REGEX.find(watchHtml)?.groupValues?.getOrNull(1)
            ?: FALLBACK_INNERTUBE_KEY

        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "ANDROID")
                        .put("clientVersion", ANDROID_CLIENT_VERSION)
                        .put("androidSdkVersion", 35)
                        .put("hl", "en")
                        .put("gl", "US"),
                ),
            )
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val playerJson = executeText(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey")
                .header("User-Agent", ANDROID_USER_AGENT)
                .header("X-YouTube-Client-Name", "3")
                .header("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )

        val root = JSONObject(playerJson)
        val tracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: throw CaptionUnavailableException("This video does not provide a public caption track.")

        val selected = selectTrack(tracks, preferredLanguages)
            ?: throw CaptionUnavailableException("No compatible caption track was found.")
        val cues = fetchCaptionCues(selected.getString("baseUrl"))

        return CaptionTrackResult(
            languageCode = selected.optString("languageCode", "en"),
            isGenerated = selected.optString("kind") == "asr" ||
                selected.optString("vssId").startsWith("a."),
            cues = cues,
            availableLanguages = availableLanguages(tracks),
        )
    }

    private fun availableLanguages(tracks: JSONArray): List<CaptionLanguage> =
        (0 until tracks.length())
            .mapNotNull(tracks::optJSONObject)
            .mapNotNull { track ->
                val code = track.optString("languageCode").takeIf(String::isNotBlank) ?: return@mapNotNull null
                CaptionLanguage(
                    code = code,
                    name = track.optJSONObject("name")?.let(::captionName).orEmpty()
                        .ifBlank { code.uppercase() },
                )
            }
            .distinctBy { it.code.substringBefore('-').lowercase() }

    private fun captionName(name: JSONObject): String {
        name.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
        val runs = name.optJSONArray("runs") ?: return ""
        return (0 until runs.length())
            .mapNotNull(runs::optJSONObject)
            .joinToString(separator = "") { it.optString("text") }
    }

    private fun fetchCaptionCues(baseUrl: String): List<RawCaptionCue> {
        // YouTube often supplies fmt=srv3. Requesting json3 without removing it leaves
        // two fmt parameters and can return XML that a JSON-only parser sees as empty.
        val cleanUrl = trustedYouTubeCaptionUrl(baseUrl)?.newBuilder()
            ?: throw CaptionUnavailableException("YouTube returned an untrusted caption URL.")
        val normalizedUrl = cleanUrl
            .removeAllQueryParameters("fmt")
            .build()
        val candidateUrls = listOf(
            normalizedUrl,
            normalizedUrl.newBuilder().addQueryParameter("fmt", "json3").build(),
            normalizedUrl.newBuilder().addQueryParameter("fmt", "srv3").build(),
        ).distinct()

        candidateUrls.forEach { url ->
            val cues = try {
                val captionText = executeText(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", ANDROID_USER_AGENT)
                        .build(),
                )
                CaptionDocumentParser.parse(captionText)
            } catch (error: ResponseLimitExceededException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            if (cues.isNotEmpty()) return cues
        }
        throw CaptionUnavailableException(
            "This caption track exists, but YouTube returned no readable timed text.",
        )
    }

    private fun selectTrack(tracks: JSONArray, preferredLanguages: List<String>): JSONObject? {
        val normalized = preferredLanguages.map { it.substringBefore('-').lowercase() }
        return (0 until tracks.length())
            .mapNotNull { tracks.optJSONObject(it) }
            .maxByOrNull { track ->
                val language = track.optString("languageCode").substringBefore('-').lowercase()
                val preferenceIndex = normalized.indexOf(language)
                val languageScore = if (preferenceIndex >= 0) 1_000 - preferenceIndex * 100 else 0
                val manualScore = if (track.optString("kind") == "asr") 0 else 20
                languageScore + manualScore
            }
    }

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw CaptionUnavailableException("YouTube returned HTTP ${response.code} while loading captions.")
        }
        val body = response.body
            ?: throw CaptionUnavailableException("YouTube returned an empty response.")
        if (body.contentLength() > MAX_YOUTUBE_RESPONSE_BYTES) {
            throw ResponseLimitExceededException(
                "YouTube returned a response larger than the 8 MiB safety limit.",
            )
        }
        body.byteStream().use(::readUtf8WithLimit)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val INNERTUBE_KEY_REGEX = Regex("\\\"INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)\\\"")
        const val FALLBACK_INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val ANDROID_CLIENT_VERSION = "20.10.38"
        const val ANDROID_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}
