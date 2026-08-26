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

internal data class YouTubePlayerClient(
    val label: String,
    val clientName: String,
    val clientNumber: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
)

private val INNERTUBE_CLIENT_VERSION_REGEX =
    Regex("""["']INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([^"']+)["']""")

internal fun extractWebInnertubeClientVersion(watchHtml: String): String? =
    INNERTUBE_CLIENT_VERSION_REGEX.find(watchHtml)?.groupValues?.getOrNull(1)

internal fun youtubePlayerClients(webClientVersion: String?): List<YouTubePlayerClient> = listOf(
    YouTubePlayerClient(
        label = "Android current",
        clientName = "ANDROID",
        clientNumber = "3",
        clientVersion = "21.26.364",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        androidSdkVersion = 30,
        osName = "Android",
        osVersion = "11",
    ),
    YouTubePlayerClient(
        label = "iOS",
        clientName = "IOS",
        clientNumber = "5",
        clientVersion = "21.26.4",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
    ),
    YouTubePlayerClient(
        label = "TV",
        clientName = "TVHTML5",
        clientNumber = "7",
        clientVersion = "7.20260707.07.00",
        userAgent =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
                "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
    ),
    YouTubePlayerClient(
        label = "Web",
        clientName = "WEB",
        clientNumber = "1",
        clientVersion = webClientVersion?.takeIf(String::isNotBlank) ?: DEFAULT_WEB_CLIENT_VERSION,
        userAgent = WEB_USER_AGENT,
    ),
)

internal fun trustedYouTubeCaptionUrl(url: String): HttpUrl? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    val trustedHost = parsed.host == "youtube.com" || parsed.host.endsWith(".youtube.com")
    return parsed.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && trustedHost
    }
}

/**
 * Prefer YouTube formats that contain per-word/chunk offsets. The unformatted
 * endpoint is kept last as a compatibility fallback because it often returns
 * legacy XML with cue-level timing only.
 */
internal fun captionCandidateUrls(baseUrl: String): List<HttpUrl> {
    val normalizedUrl = trustedYouTubeCaptionUrl(baseUrl)
        ?.newBuilder()
        ?.removeAllQueryParameters("fmt")
        ?.build()
        ?: return emptyList()
    return listOf(
        normalizedUrl.newBuilder().addQueryParameter("fmt", "json3").build(),
        normalizedUrl.newBuilder().addQueryParameter("fmt", "srv3").build(),
        normalizedUrl,
    ).distinct()
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
 * Multiple official client profiles are attempted because YouTube can roll endpoint
 * changes out to one client family before another.
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
        val watchResult = runCatching {
            executeText(
                Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                    .header("User-Agent", WEB_USER_AGENT)
                    .build(),
                stage = "watch-page discovery",
            )
        }
        val watchHtml = watchResult.getOrNull().orEmpty()
        val apiKey = INNERTUBE_KEY_REGEX.find(watchHtml)?.groupValues?.getOrNull(1)
            ?: FALLBACK_INNERTUBE_KEY
        val profiles = youtubePlayerClients(extractWebInnertubeClientVersion(watchHtml))

        val failures = mutableListOf<String>()
        var lastError: Throwable? = watchResult.exceptionOrNull()
        var lastServiceFailure: String? = watchResult.exceptionOrNull()?.message?.let {
            "watch-page discovery: $it"
        }
        var sawNonTrackFailure = false

        for (profile in profiles) {
            try {
                return fetchWithClient(
                    videoId = videoId,
                    preferredLanguages = preferredLanguages,
                    apiKey = apiKey,
                    profile = profile,
                )
            } catch (error: ResponseLimitExceededException) {
                throw error
            } catch (error: NoCaptionTracksException) {
                lastError = error
                failures += "${profile.label}: ${error.message}"
            } catch (error: Exception) {
                lastError = error
                sawNonTrackFailure = true
                val failure = "${profile.label}: ${error.message ?: error.javaClass.simpleName}"
                failures += failure
                lastServiceFailure = failure
            }
        }

        if (!sawNonTrackFailure && failures.isNotEmpty()) {
            throw CaptionUnavailableException("This video does not provide a public caption track.", lastError)
        }

        val detail = lastServiceFailure
            ?: failures.lastOrNull()
            ?: "No YouTube client returned usable captions."
        throw CaptionUnavailableException(
            "Captions could not be loaded after trying YouTube client fallbacks. Last failure: $detail",
            lastError,
        )
    }

    private fun fetchWithClient(
        videoId: String,
        preferredLanguages: List<String>,
        apiKey: String,
        profile: YouTubePlayerClient,
    ): CaptionTrackResult {
        val clientContext = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("userAgent", profile.userAgent)
            .put("hl", "en")
            .put("gl", "US")
        profile.androidSdkVersion?.let { clientContext.put("androidSdkVersion", it) }
        profile.deviceMake?.let { clientContext.put("deviceMake", it) }
        profile.deviceModel?.let { clientContext.put("deviceModel", it) }
        profile.osName?.let { clientContext.put("osName", it) }
        profile.osVersion?.let { clientContext.put("osVersion", it) }

        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val playerJson = executeText(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey")
                .header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.clientNumber)
                .header("X-YouTube-Client-Version", profile.clientVersion)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            stage = "player request (${profile.label})",
        )

        val root = JSONObject(playerJson)
        val playability = root.optJSONObject("playabilityStatus")
        val playabilityStatus = playability?.optString("status").orEmpty()
        if (playabilityStatus.isNotBlank() && playabilityStatus != "OK") {
            val reason = playability?.optString("reason").orEmpty()
            throw CaptionUnavailableException(
                buildString {
                    append("Player returned ")
                    append(playabilityStatus)
                    if (reason.isNotBlank()) append(": ").append(reason)
                },
            )
        }

        val tracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: throw NoCaptionTracksException("This client returned no public caption track.")

        val selected = selectTrack(tracks, preferredLanguages)
            ?: throw NoCaptionTracksException("No compatible caption track was found.")
        val cues = fetchCaptionCues(
            baseUrl = selected.getString("baseUrl"),
            userAgent = profile.userAgent,
        )

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

    private fun fetchCaptionCues(baseUrl: String, userAgent: String): List<RawCaptionCue> {
        val candidateUrls = captionCandidateUrls(baseUrl)
        if (candidateUrls.isEmpty()) {
            throw CaptionUnavailableException("YouTube returned an untrusted caption URL.")
        }

        var lastError: Throwable? = null
        candidateUrls.forEach { url ->
            val format = url.queryParameter("fmt") ?: "legacy"
            val cues = try {
                val captionText = executeText(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .build(),
                    stage = "caption download ($format)",
                )
                CaptionDocumentParser.parse(captionText).also {
                    if (it.isEmpty()) {
                        lastError = CaptionUnavailableException(
                            "YouTube returned an empty or unreadable $format caption document.",
                        )
                    }
                }
            } catch (error: ResponseLimitExceededException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                emptyList()
            }
            if (cues.isNotEmpty()) return cues
        }

        throw CaptionUnavailableException(
            "This caption track exists, but YouTube returned no readable timed text. " +
                "Last failure: ${lastError?.message ?: "empty caption response"}",
            lastError,
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

    private fun executeText(request: Request, stage: String): String =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CaptionUnavailableException(
                    "YouTube returned HTTP ${response.code} during $stage.",
                )
            }
            val body = response.body
                ?: throw CaptionUnavailableException("YouTube returned an empty response during $stage.")
            if (body.contentLength() > MAX_YOUTUBE_RESPONSE_BYTES) {
                throw ResponseLimitExceededException(
                    "YouTube returned a response larger than the 8 MiB safety limit during $stage.",
                )
            }
            body.byteStream().use(::readUtf8WithLimit)
        }

    private class NoCaptionTracksException(message: String) : Exception(message)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val INNERTUBE_KEY_REGEX = Regex("\\\"INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)\\\"")
        const val FALLBACK_INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    }
}

private const val DEFAULT_WEB_CLIENT_VERSION = "2.20260708.00.00"
private const val WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
