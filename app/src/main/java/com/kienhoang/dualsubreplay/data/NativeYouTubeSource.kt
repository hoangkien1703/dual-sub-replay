package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lists and details for native mode. Like [CaptionProvider], this is the replaceable boundary
 * around YouTube's undocumented web endpoints: screens only see the models in NativeYouTubeModels.
 */
internal interface NativeYouTubeSource {
    suspend fun search(query: String): NativePage

    suspend fun moreSearch(continuation: String): NativePage

    /** The next page of a channel's videos or a playlist. */
    suspend fun moreBrowse(continuation: String): NativePage

    suspend fun watch(videoId: String): NativeWatchDetails

    suspend fun moreRelated(continuation: String): NativePage

    suspend fun comments(continuation: String): NativeCommentPage

    suspend fun channel(channelId: String): NativeChannelPage

    suspend fun playlist(playlistId: String): NativePlaylistPage

    /** A channel's newest uploads with their upload times, newest first. */
    suspend fun latestUploads(channelId: String): List<Pair<NativeVideo, Long>>
}

internal class NativeYouTubeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private const val NATIVE_WEB_CLIENT_VERSION = "2.20260708.00.00"
private const val NATIVE_REQUEST_TIMEOUT_MS = 15_000L
private const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"

/** The request body YouTube's own web app sends; [hl] and [gl] follow the device language. */
internal fun nativeRequestBody(
    fields: Map<String, String>,
    locale: Locale = Locale.getDefault(),
): JSONObject {
    val client =
        JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", NATIVE_WEB_CLIENT_VERSION)
            .put("hl", locale.toLanguageTag().ifBlank { "en" })
    locale.country.takeIf { it.length == 2 }?.let { client.put("gl", it) }
    val body = JSONObject().put("context", JSONObject().put("client", client))
    fields.forEach { (key, value) -> body.put(key, value) }
    return body
}

internal class InnertubeNativeYouTubeSource(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(NATIVE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build(),
) : NativeYouTubeSource {
    override suspend fun search(query: String): NativePage = parseNativeSearch(post("search", mapOf("query" to query.trim())))

    override suspend fun moreSearch(continuation: String): NativePage =
        parseNativeContinuation(post("search", mapOf("continuation" to continuation)))

    override suspend fun moreBrowse(continuation: String): NativePage =
        parseNativeContinuation(post("browse", mapOf("continuation" to continuation)))

    override suspend fun watch(videoId: String): NativeWatchDetails {
        require(isNativeVideoId(videoId))
        return parseNativeWatch(videoId, post("next", mapOf("videoId" to videoId)))
    }

    override suspend fun moreRelated(continuation: String): NativePage =
        parseNativeContinuation(post("next", mapOf("continuation" to continuation)))

    override suspend fun comments(continuation: String): NativeCommentPage =
        parseNativeComments(post("next", mapOf("continuation" to continuation)))

    override suspend fun channel(channelId: String): NativeChannelPage {
        require(isNativeChannelId(channelId))
        return parseNativeChannel(channelId, post("browse", mapOf("browseId" to channelId, "params" to CHANNEL_VIDEOS_TAB)))
    }

    override suspend fun playlist(playlistId: String): NativePlaylistPage {
        require(isNativePlaylistId(playlistId))
        return parseNativePlaylist(playlistId, post("browse", mapOf("browseId" to "VL$playlistId")))
    }

    override suspend fun latestUploads(channelId: String): List<Pair<NativeVideo, Long>> {
        require(isNativeChannelId(channelId))
        val request = Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId").build()
        return parseNativeChannelFeed(execute(request)).sortedByDescending { it.second }
    }

    private suspend fun post(
        endpoint: String,
        fields: Map<String, String>,
    ): JSONObject {
        val request =
            Request
                .Builder()
                .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                .post(nativeRequestBody(fields).toString().toRequestBody("application/json".toMediaType()))
                .build()
        return JSONObject(execute(request))
    }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    NativeYouTubeException("YouTube request failed.", e),
                                )
                            }
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            val result =
                                runCatching {
                                    response.use {
                                        if (!it.isSuccessful) throw NativeYouTubeException("YouTube returned HTTP ${it.code}.")
                                        val body = it.body ?: throw NativeYouTubeException("YouTube returned an empty response.")
                                        body.byteStream().use { stream -> readUtf8WithLimit(stream) }
                                    }
                                }
                            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                        }
                    },
                )
            }
        }
}
