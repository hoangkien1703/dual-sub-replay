package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

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
        val baseUrl = selected.getString("baseUrl")
        val captionText = executeText(
            Request.Builder()
                .url(baseUrl + if (baseUrl.contains('?')) "&fmt=json3" else "?fmt=json3")
                .header("User-Agent", ANDROID_USER_AGENT)
                .build(),
        )

        val cues = if (captionText.trimStart().startsWith("<")) {
            parseXml(captionText)
        } else {
            parseJson3(captionText)
        }
        if (cues.isEmpty()) throw CaptionUnavailableException("The selected caption track was empty.")

        CaptionTrackResult(
            languageCode = selected.optString("languageCode", "en"),
            isGenerated = selected.optString("kind") == "asr" ||
                selected.optString("vssId").startsWith("a."),
            cues = cues,
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

    private fun parseJson3(text: String): List<RawCaptionCue> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val segments = event.optJSONArray("segs") ?: return@mapNotNull null
            val content = buildString {
                for (segmentIndex in 0 until segments.length()) {
                    append(segments.optJSONObject(segmentIndex)?.optString("utf8").orEmpty())
                }
            }.normalizeCaptionText()
            if (content.isBlank()) return@mapNotNull null
            val start = event.optLong("tStartMs", -1L)
            if (start < 0) return@mapNotNull null
            val duration = event.optLong("dDurationMs", 1_200L).coerceAtLeast(200L)
            RawCaptionCue(start, start + duration, content)
        }
    }

    private fun parseXml(text: String): List<RawCaptionCue> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(text))
        }
        val cues = mutableListOf<RawCaptionCue>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "text") {
                val startMs = ((parser.getAttributeValue(null, "start")?.toDoubleOrNull() ?: 0.0) * 1_000).toLong()
                val durationMs = ((parser.getAttributeValue(null, "dur")?.toDoubleOrNull() ?: 1.2) * 1_000).toLong()
                val content = parser.nextText().normalizeCaptionText()
                if (content.isNotBlank()) cues += RawCaptionCue(startMs, startMs + durationMs, content)
            }
            event = parser.next()
        }
        return cues
    }

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw CaptionUnavailableException("YouTube returned HTTP ${response.code} while loading captions.")
        }
        response.body?.string() ?: throw CaptionUnavailableException("YouTube returned an empty response.")
    }

    private fun String.normalizeCaptionText(): String = replace(Regex("\\s+"), " ").trim()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val INNERTUBE_KEY_REGEX = Regex("\\\"INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)\\\"")
        const val FALLBACK_INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val ANDROID_CLIENT_VERSION = "20.10.38"
        const val ANDROID_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}
