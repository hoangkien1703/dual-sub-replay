package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/*
 * Settings → Ads and sponsors → Skip sponsors (owner request 2026-10-25): the sponsor parts of a
 * video from SponsorBlock's community database (sponsor.ajay.app, data under CC BY-NC-SA 4.0).
 * The request names only the first 4 characters of the video ID's SHA-256 hash, so SponsorBlock
 * answers for every video sharing them and does not learn which one is watched.
 */

internal const val SPONSORBLOCK_HOST = "sponsor.ajay.app"

/** Characters of the hashed video ID the request names. */
internal const val SPONSOR_HASH_PREFIX_LENGTH = 4

/** A reply covers a few hundred videos and is usually under 100 KiB. */
internal const val MAX_SPONSOR_REPLY_BYTES = 1024 * 1024

private const val SPONSOR_TIMEOUT_MS = 10_000L

/** Videos whose segments stay in memory. */
private const val SPONSOR_CACHE_SIZE = 20

private const val HTTP_NOT_FOUND = 404

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val SEGMENT_ID = Regex("[A-Za-z0-9]{1,80}")

/** One sponsor part of a video, in seconds; [videoDuration] is the length it was recorded for, or 0. */
internal data class SponsorSegment(
    val id: String,
    val start: Double,
    val end: Double,
    val videoDuration: Double,
)

internal fun sponsorHashPrefix(videoId: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(videoId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(SPONSOR_HASH_PREFIX_LENGTH)

/** The only address asked: sponsor parts to skip, for the videos sharing [videoId]'s hash prefix. */
internal fun sponsorSegmentsUrl(videoId: String): HttpUrl =
    HttpUrl
        .Builder()
        .scheme("https")
        .host(SPONSORBLOCK_HOST)
        .addPathSegments("api/skipSegments")
        .addPathSegment(sponsorHashPrefix(videoId))
        .addQueryParameter("categories", """["sponsor"]""")
        .addQueryParameter("actionTypes", """["skip"]""")
        .build()

/** [videoId]'s sponsor parts in a reply, in time order; other videos and anything malformed are left out. */
internal fun parseSponsorSegments(
    body: String,
    videoId: String,
): List<SponsorSegment> {
    val videos =
        try {
            JSONArray(body)
        } catch (_: JSONException) {
            return emptyList()
        }
    val video =
        (0 until videos.length())
            .mapNotNull(videos::optJSONObject)
            .firstOrNull { it.optString("videoID") == videoId } ?: return emptyList()
    val segments = video.optJSONArray("segments") ?: return emptyList()
    return (0 until segments.length())
        .mapNotNull(segments::optJSONObject)
        .filter { it.optString("category") == "sponsor" && it.optString("actionType", "skip") == "skip" }
        .mapNotNull { segment ->
            val times = segment.optJSONArray("segment") ?: return@mapNotNull null
            val start = times.optDouble(0)
            val end = times.optDouble(1)
            val id = segment.optString("UUID").takeIf { it.matches(SEGMENT_ID) } ?: return@mapNotNull null
            val duration = segment.optDouble("videoDuration", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
            if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return@mapNotNull null
            SponsorSegment(id, start, end, duration)
        }.sortedBy { it.start }
}

/** Asks SponsorBlock for a video's sponsor parts; an empty list when it has none or cannot be reached. */
internal class SponsorSegmentSource(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(SPONSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SPONSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SPONSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build(),
) {
    private val cache =
        object : LinkedHashMap<String, List<SponsorSegment>>(SPONSOR_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SponsorSegment>>?) = size > SPONSOR_CACHE_SIZE
        }

    suspend fun segments(videoId: String): List<SponsorSegment> {
        if (!videoId.matches(VIDEO_ID)) return emptyList()
        synchronized(cache) { cache[videoId] }?.let { return it }
        val found = withContext(Dispatchers.IO) { runCatching { download(videoId) }.getOrNull() } ?: return emptyList()
        synchronized(cache) { cache[videoId] = found }
        return found
    }

    /** The video's parts, an empty list when SponsorBlock has none (404), or null when it failed. */
    private fun download(videoId: String): List<SponsorSegment>? {
        val request =
            Request
                .Builder()
                .url(sponsorSegmentsUrl(videoId))
                .header("Accept", "application/json")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return emptyList()
            if (!response.isSuccessful) return null
            val body = response.body.byteStream().use { readUtf8WithLimit(it, MAX_SPONSOR_REPLY_BYTES) }
            return parseSponsorSegments(body, videoId)
        }
    }
}
