package com.kienhoang.dualsubreplay.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Caption details the YouTube page in the app already received. YouTube sometimes refuses the
 * app's own player requests with a bot check ("Sign in to confirm you're not a bot") while the
 * page, with its cookies and browser checks, plays the same video. The page hands over its
 * caption track list, and the parameters its own caption requests carried (`pot` and the
 * client fields), so the provider can download the full transcript without asking YouTube's
 * player again. Nothing here starts a request or changes the page.
 */
class PageCaptionSource {
    private data class PageTracks(
        val videoId: String,
        val playerResponse: String,
    )

    @Volatile private var tracks: PageTracks? = null

    @Volatile private var timedTextParameters: Pair<String, Map<String, String>>? = null

    /** The page's user agent, so downloads look like the page that is playing the video. */
    @Volatile var userAgent: String? = null

    /** Cookies the page holds for a YouTube address; only ever sent back to YouTube. */
    @Volatile var cookies: (HttpUrl) -> String? = { null }

    /** Records the page's trimmed player response (see `WEB_PAGE_CAPTION_TRACKS_SCRIPT`). */
    fun recordPlayerResponse(json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val videoId =
            root
                .optJSONObject("videoDetails")
                ?.optString("videoId")
                ?.takeIf(PAGE_VIDEO_ID::matches)
                ?: return
        tracks = PageTracks(videoId, json)
    }

    /** Records the parameters of a caption request the page's own player made. */
    fun recordTimedTextRequest(url: String) {
        val parameters = pageTimedTextParameters(url) ?: return
        timedTextParameters = parameters
    }

    fun hasTracks(videoId: String): Boolean = tracks?.videoId == videoId

    fun playerResponse(videoId: String): JSONObject? =
        tracks
            ?.takeIf { it.videoId == videoId }
            ?.let { runCatching { JSONObject(it.playerResponse) }.getOrNull() }

    fun timedTextParameters(videoId: String): Map<String, String> = timedTextParameters?.takeIf { it.first == videoId }?.second.orEmpty()

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageCaptionSource()
    }
}

private val PAGE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** Fields the page's player adds to its caption requests and YouTube may check. */
internal val PAGE_TIMED_TEXT_PARAMETERS =
    listOf("pot", "potc", "c", "cver", "cplayer", "cbr", "cbrver", "cos", "cosver", "cplatform", "xorb", "xobt", "xovt")

/** The video id and [PAGE_TIMED_TEXT_PARAMETERS] of a trusted `/api/timedtext` address, or null. */
internal fun pageTimedTextParameters(url: String): Pair<String, Map<String, String>>? {
    val parsed = trustedYouTubeCaptionUrl(url)?.takeIf { it.encodedPath == "/api/timedtext" } ?: return null
    val videoId = parsed.queryParameter("v")?.takeIf(PAGE_VIDEO_ID::matches) ?: return null
    val parameters =
        PAGE_TIMED_TEXT_PARAMETERS
            .mapNotNull { name -> parsed.queryParameter(name)?.let { name to it } }
            .toMap()
    return (videoId to parameters).takeIf { "pot" in parameters }
}

/** [baseUrl] with the page's own caption parameters added where it lacks them. */
internal fun withPageTimedTextParameters(
    baseUrl: String,
    parameters: Map<String, String>,
): String {
    val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
    if (parameters.isEmpty()) return baseUrl
    val builder = parsed.newBuilder()
    parameters.forEach { (name, value) -> if (parsed.queryParameter(name) == null) builder.addQueryParameter(name, value) }
    return builder.build().toString()
}
