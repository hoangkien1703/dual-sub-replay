package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_YOUTUBE_RESPONSE_BYTES = 8 * 1024 * 1024
internal const val CAPTION_LOOKUP_TIMEOUT_MS = 20_000L
internal const val YOUTUBE_REQUEST_TIMEOUT_MS = 3_500L

internal class ResponseLimitExceededException(message: String) : Exception(message)
internal class CaptionLookupTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

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
    val embedUrl: String? = null,
)

private val INNERTUBE_CLIENT_VERSION_REGEX =
    Regex("""["']INNERTUBE_CLIENT_VERSION["']\s*:\s*["']([^"']+)["']""")
private val INITIAL_PLAYER_RESPONSE_REGEX =
    Regex("""(?:var\s+)?ytInitialPlayerResponse\s*=\s*""")

internal fun extractWebInnertubeClientVersion(watchHtml: String): String? =
    INNERTUBE_CLIENT_VERSION_REGEX.find(watchHtml)?.groupValues?.getOrNull(1)

/**
 * OkHttp 4 tries resolved addresses sequentially. A short whole-call timeout can therefore expire
 * on a broken IPv6 route before the IPv4 route is attempted, while Chromium/WebView succeeds via
 * its own connection racing. Prefer IPv4 when both families are available, but retain IPv6 as a
 * fallback so IPv6-only/NAT64 networks still work.
 */
internal fun preferIpv4Addresses(addresses: List<InetAddress>): List<InetAddress> =
    addresses.sortedBy { address -> if (address is Inet4Address) 0 else 1 }

/**
 * The normal YouTube watch page often already embeds the exact player response that contains
 * caption tracks. Reuse it before making any extra Innertube player request. This is especially
 * important when the WebView can play the video but direct player POSTs are temporarily blocked or
 * timing out.
 */
internal fun extractInitialPlayerResponse(watchHtml: String): JSONObject? {
    val marker = INITIAL_PLAYER_RESPONSE_REGEX.find(watchHtml) ?: return null
    val objectStart = watchHtml.indexOf('{', marker.range.last + 1)
    if (objectStart < 0) return null
    val jsonText = extractJsonObject(watchHtml, objectStart) ?: return null
    return runCatching { JSONObject(jsonText) }.getOrNull()
}

internal fun extractJsonObject(text: String, objectStart: Int): String? {
    if (objectStart !in text.indices || text[objectStart] != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in objectStart until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return text.substring(objectStart, index + 1)
                if (depth < 0) return null
            }
        }
    }
    return null
}

/**
 * Keep the most reliable logged-out caption clients first. YouTube changed player access again in
 * mid-2026: older Android/iOS/Web profiles can now return UNPLAYABLE even while the same video is
 * playing normally in the embedded WebView. visionOS is the current no-JS player fallback and
 * Android VR plus the embedded web player provide additional compatibility for videos that reject
 * one client family.
 */
internal fun youtubePlayerClients(webClientVersion: String?): List<YouTubePlayerClient> = listOf(
    YouTubePlayerClient(
        label = "visionOS",
        clientName = "VISIONOS",
        clientNumber = "101",
        clientVersion = "1.02",
        userAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        deviceMake = "Apple",
        deviceModel = "RealityDevice17,1",
        osName = "visionOS",
        osVersion = "26.5.23O471",
    ),
    YouTubePlayerClient(
        label = "Android VR",
        clientName = "ANDROID_VR",
        clientNumber = "28",
        clientVersion = "1.65.10",
        userAgent =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        androidSdkVersion = 32,
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        osName = "Android",
        osVersion = "12L",
    ),
    YouTubePlayerClient(
        label = "Web embedded",
        clientName = "WEB_EMBEDDED_PLAYER",
        clientNumber = "56",
        clientVersion = webClientVersion?.takeIf(String::isNotBlank) ?: DEFAULT_WEB_CLIENT_VERSION,
        userAgent = WEB_USER_AGENT,
        embedUrl = "https://www.reddit.com/",
    ),
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
        label = "TV downgraded",
        clientName = "TVHTML5",
        clientNumber = "7",
        clientVersion = "5.20260707",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
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

internal fun playerApiUrls(apiKey: String): List<String> = listOf(
    "https://youtubei.googleapis.com/youtubei/v1/player?key=$apiKey",
    "https://www.youtube.com/youtubei/v1/player?key=$apiKey",
)

internal fun playerRequestBody(videoId: String, profile: YouTubePlayerClient): JSONObject {
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

    val context = JSONObject().put("client", clientContext)
    profile.embedUrl?.let { embedUrl ->
        context.put("thirdParty", JSONObject().put("embedUrl", embedUrl))
    }
    return JSONObject()
        .put("context", context)
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)
}

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

internal fun boundedYouTubeRequestTimeoutNanos(remainingNanos: Long): Long {
    require(remainingNanos > 0)
    return minOf(
        remainingNanos,
        TimeUnit.MILLISECONDS.toNanos(YOUTUBE_REQUEST_TIMEOUT_MS),
    )
}

/**
 * Retrieves public caption tracks from YouTube's undocumented Innertube endpoint.
 * Multiple official client profiles are attempted because YouTube can roll endpoint
 * changes out to one client family before another.
 *
 * The complete fallback chain has a hard deadline. Without it, several sequential
 * network timeouts can leave the UI spinning for minutes when YouTube stops replying.
 */
class YouTubeCaptionProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    preferIpv4Addresses(Dns.SYSTEM.lookup(hostname))
            },
        )
        .connectTimeout(YOUTUBE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(YOUTUBE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(YOUTUBE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(YOUTUBE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
) : CaptionProvider {

    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
    ): CaptionTrackResult = withContext(Dispatchers.IO) {
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(CAPTION_LOOKUP_TIMEOUT_MS)
        try {
            fetchInternal(videoId, preferredLanguages, deadlineNanos)
        } catch (error: CancellationException) {
            // Never turn a cancelled/replaced load into a caption failure. The
            // ViewModel uses cancellation whenever the video or language changes.
            throw error
        } catch (error: CaptionUnavailableException) {
            throw error
        } catch (error: ResponseLimitExceededException) {
            throw CaptionUnavailableException(error.message.orEmpty(), error)
        } catch (error: CaptionLookupTimeoutException) {
            throw CaptionUnavailableException(error.message.orEmpty(), error)
        } catch (error: Exception) {
            throw CaptionUnavailableException(
                "Captions could not be loaded. YouTube may have changed its transcript service.",
                error,
            )
        }
    }

    private fun fetchInternal(
        videoId: String,
        preferredLanguages: List<String>,
        deadlineNanos: Long,
    ): CaptionTrackResult {
        ensureLookupTimeRemaining(deadlineNanos, "starting caption discovery")
        val watchResult = runCatching {
            executeText(
                Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId&hl=en")
                    .header("User-Agent", WEB_USER_AGENT)
                    .build(),
                stage = "watch-page discovery",
                deadlineNanos = deadlineNanos,
            )
        }
        watchResult.exceptionOrNull()?.let { error ->
            if (error is CaptionLookupTimeoutException) throw error
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

        extractInitialPlayerResponse(watchHtml)?.let { embeddedPlayer ->
            try {
                return captionResultFromPlayerResponse(
                    root = embeddedPlayer,
                    preferredLanguages = preferredLanguages,
                    userAgent = WEB_USER_AGENT,
                    deadlineNanos = deadlineNanos,
                    noTracksMessage = "The watch page embedded no public caption track.",
                )
            } catch (error: CaptionLookupTimeoutException) {
                throw error
            } catch (error: ResponseLimitExceededException) {
                throw error
            } catch (error: NoCaptionTracksException) {
                lastError = error
                failures += "Watch page: ${error.message}"
            } catch (error: Exception) {
                lastError = error
                sawNonTrackFailure = true
                val failure = "Watch page: ${error.message ?: error.javaClass.simpleName}"
                failures += failure
                lastServiceFailure = failure
            }
        }

        for (profile in profiles) {
            ensureLookupTimeRemaining(deadlineNanos, "trying ${profile.label}")
            try {
                return fetchWithClient(
                    videoId = videoId,
                    preferredLanguages = preferredLanguages,
                    apiKey = apiKey,
                    profile = profile,
                    deadlineNanos = deadlineNanos,
                )
            } catch (error: CaptionLookupTimeoutException) {
                throw error
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
            "Captions could not be loaded after trying the watch page and YouTube client fallbacks. " +
                "Last failure: $detail",
            lastError,
        )
    }

    private fun fetchWithClient(
        videoId: String,
        preferredLanguages: List<String>,
        apiKey: String,
        profile: YouTubePlayerClient,
        deadlineNanos: Long,
    ): CaptionTrackResult {
        val body = playerRequestBody(videoId, profile)

        var lastError: Exception? = null
        for (playerUrl in playerApiUrls(apiKey)) {
            ensureLookupTimeRemaining(deadlineNanos, "trying ${profile.label}")
            val host = playerUrl.toHttpUrlOrNull()?.host ?: "player endpoint"
            try {
                val playerJson = executeText(
                    Request.Builder()
                        .url(playerUrl)
                        .header("User-Agent", profile.userAgent)
                        .header("Origin", "https://www.youtube.com")
                        .header("X-YouTube-Client-Name", profile.clientNumber)
                        .header("X-YouTube-Client-Version", profile.clientVersion)
                        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build(),
                    stage = "player request (${profile.label}, $host)",
                    deadlineNanos = deadlineNanos,
                )
                return captionResultFromPlayerResponse(
                    root = JSONObject(playerJson),
                    preferredLanguages = preferredLanguages,
                    userAgent = profile.userAgent,
                    deadlineNanos = deadlineNanos,
                    noTracksMessage = "This client returned no public caption track.",
                )
            } catch (error: CaptionLookupTimeoutException) {
                throw error
            } catch (error: ResponseLimitExceededException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: CaptionUnavailableException("No YouTube player endpoint responded.")
    }

    private fun captionResultFromPlayerResponse(
        root: JSONObject,
        preferredLanguages: List<String>,
        userAgent: String,
        deadlineNanos: Long,
        noTracksMessage: String,
    ): CaptionTrackResult {
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
            ?: throw NoCaptionTracksException(noTracksMessage)

        val selected = selectTrack(tracks, preferredLanguages)
            ?: throw NoCaptionTracksException("No compatible caption track was found.")
        val cues = fetchCaptionCues(
            baseUrl = selected.getString("baseUrl"),
            userAgent = userAgent,
            deadlineNanos = deadlineNanos,
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

    private fun fetchCaptionCues(
        baseUrl: String,
        userAgent: String,
        deadlineNanos: Long,
    ): List<RawCaptionCue> {
        val candidateUrls = captionCandidateUrls(baseUrl)
        if (candidateUrls.isEmpty()) {
            throw CaptionUnavailableException("YouTube returned an untrusted caption URL.")
        }

        var lastError: Throwable? = null
        candidateUrls.forEach { url ->
            ensureLookupTimeRemaining(deadlineNanos, "downloading captions")
            val format = url.queryParameter("fmt") ?: "legacy"
            val cues = try {
                val captionText = executeText(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .build(),
                    stage = "caption download ($format)",
                    deadlineNanos = deadlineNanos,
                )
                CaptionDocumentParser.parse(captionText).also {
                    if (it.isEmpty()) {
                        lastError = CaptionUnavailableException(
                            "YouTube returned an empty or unreadable $format caption document.",
                        )
                    }
                }
            } catch (error: CaptionLookupTimeoutException) {
                throw error
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

    private fun executeText(
        request: Request,
        stage: String,
        deadlineNanos: Long,
    ): String {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            throw lookupTimeout(stage)
        }
        val call = client.newCall(request)
        call.timeout().timeout(
            boundedYouTubeRequestTimeoutNanos(remainingNanos),
            TimeUnit.NANOSECONDS,
        )
        try {
            return call.execute().use { response ->
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
        } catch (error: InterruptedIOException) {
            if (System.nanoTime() >= deadlineNanos) {
                throw lookupTimeout(stage, error)
            }
            throw CaptionUnavailableException(
                "YouTube request timed out during $stage.",
                error,
            )
        }
    }

    private fun ensureLookupTimeRemaining(deadlineNanos: Long, stage: String) {
        if (System.nanoTime() >= deadlineNanos) throw lookupTimeout(stage)
    }

    private fun lookupTimeout(stage: String, cause: Throwable? = null): CaptionLookupTimeoutException =
        CaptionLookupTimeoutException(
            "Caption discovery stopped after ${CAPTION_LOOKUP_TIMEOUT_MS / 1_000} seconds " +
                "instead of continuing to spin. Last stage: $stage.",
            cause,
        )

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