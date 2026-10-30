package com.kienhoang.dualsubreplay.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * YouTube's own web search, the request youtube.com sends when someone searches. It needs no key
 * or cookies but is not an official API, like the caption fallbacks, so it stays behind this one
 * file. The AI assistant uses it to suggest videos, and TV home to find them with a remote.
 */

/** The search address; nothing else is ever asked. */
internal val YOUTUBE_SEARCH_URL: HttpUrl = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false".toHttpUrl()

/** A search reply is about 0.5 MiB for 20 videos. */
internal const val MAX_YOUTUBE_SEARCH_BYTES = 4 * 1024 * 1024

/** Videos given to the model at most. */
internal const val MAX_YOUTUBE_SEARCH_RESULTS = 10

private const val WEB_CLIENT_VERSION = "2.20250101.00.00"
private const val MAX_TEXT_CHARS = 200

/** YouTube's search filters: videos only, or videos with subtitles. */
private const val VIDEOS_ONLY_FILTER = "EgIQAQ=="
private const val VIDEOS_WITH_SUBTITLES_FILTER = "EgQQASgB"

/** One video in the results; the texts are YouTube's, in English, and may be empty. */
internal data class YouTubeVideoResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val length: String,
    val views: String,
    val published: String,
    /** YouTube marks the video CC: it has subtitles someone wrote. */
    val subtitles: Boolean,
)

/** The search request: the web client, English texts, the phone's country, and the filter. */
internal fun youTubeSearchRequestBody(
    query: String,
    subtitlesOnly: Boolean,
    country: String,
): String {
    val client =
        JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", WEB_CLIENT_VERSION)
            .put("hl", "en")
            .apply { country.takeIf { it.matches(Regex("[A-Z]{2}")) }?.let { put("gl", it) } }
    return JSONObject()
        .put("context", JSONObject().put("client", client))
        .put("query", query)
        .put("params", if (subtitlesOnly) VIDEOS_WITH_SUBTITLES_FILTER else VIDEOS_ONLY_FILTER)
        .toString()
}

/**
 * The videos in a search reply, in YouTube's order, at most [limit]. Anything else in the reply
 * (channels, playlists, shelves, ads) is skipped, and so is a video without a usable ID or title.
 */
internal fun parseYouTubeSearchReply(
    body: String,
    limit: Int = MAX_YOUTUBE_SEARCH_RESULTS,
): List<YouTubeVideoResult> {
    val root =
        try {
            JSONObject(body)
        } catch (_: JSONException) {
            return emptyList()
        }
    val renderers = mutableListOf<JSONObject>()
    collectVideoRenderers(root.opt("contents"), renderers, depth = 0)
    return renderers
        .mapNotNull(::videoResult)
        .distinctBy { it.videoId }
        .take(limit)
}

private const val MAX_SEARCH_DEPTH = 40

private fun collectVideoRenderers(
    node: Any?,
    found: MutableList<JSONObject>,
    depth: Int,
) {
    if (depth > MAX_SEARCH_DEPTH) return
    when (node) {
        is JSONObject ->
            node.keys().forEach { key ->
                val value = node.opt(key)
                if (key == "videoRenderer" && value is JSONObject) found += value else collectVideoRenderers(value, found, depth + 1)
            }
        is JSONArray -> for (index in 0 until node.length()) collectVideoRenderers(node.opt(index), found, depth + 1)
    }
}

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

private fun videoResult(renderer: JSONObject): YouTubeVideoResult? {
    val id = renderer.optString("videoId").takeIf(VIDEO_ID::matches) ?: return null
    val title = youTubeText(renderer.optJSONObject("title")).ifEmpty { return null }
    val badges = renderer.optJSONArray("badges") ?: JSONArray()
    val subtitles =
        (0 until badges.length()).any { index ->
            badges
                .optJSONObject(index)
                ?.optJSONObject("metadataBadgeRenderer")
                ?.optString("label")
                .equals("CC", ignoreCase = true)
        }
    return YouTubeVideoResult(
        videoId = id,
        title = title,
        channel = youTubeText(renderer.optJSONObject("ownerText") ?: renderer.optJSONObject("longBylineText")),
        length = youTubeText(renderer.optJSONObject("lengthText")),
        views = youTubeText(renderer.optJSONObject("viewCountText")),
        published = youTubeText(renderer.optJSONObject("publishedTimeText")),
        subtitles = subtitles,
    )
}

/** A YouTube text: `simpleText`, or its `runs` joined; one line, cut to [MAX_TEXT_CHARS]. */
private fun youTubeText(text: JSONObject?): String {
    if (text == null) return ""
    val raw =
        text.optString("simpleText").ifEmpty {
            val runs = text.optJSONArray("runs") ?: return ""
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }
    return raw.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)
}

/** TV home's grid holds more videos than the assistant is given. */
internal const val TV_YOUTUBE_SEARCH_RESULTS = 24

private const val YOUTUBE_SEARCH_TIMEOUT_MS = 20_000L

/** YouTube's web search for TV home: the videos found, or an [IOException] when YouTube cannot be asked. */
internal object YouTubeVideoSearch {
    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(YOUTUBE_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    suspend fun search(
        query: String,
        subtitlesOnly: Boolean,
        country: String,
        limit: Int = TV_YOUTUBE_SEARCH_RESULTS,
    ): List<YouTubeVideoResult> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(YOUTUBE_SEARCH_URL)
                    .header("Accept", "application/json")
                    .post(youTubeSearchRequestBody(query, subtitlesOnly, country).toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("YouTube answered HTTP ${response.code}.")
                val source = response.body.source()
                source.request(MAX_YOUTUBE_SEARCH_BYTES + 1L)
                if (source.buffer.size > MAX_YOUTUBE_SEARCH_BYTES) throw IOException("YouTube's reply was too large.")
                parseYouTubeSearchReply(source.buffer.readUtf8(), limit)
            }
        }
}
