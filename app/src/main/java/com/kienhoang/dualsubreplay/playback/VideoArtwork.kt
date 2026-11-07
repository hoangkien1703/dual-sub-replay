package com.kienhoang.dualsubreplay.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
private const val ARTWORK_TIMEOUT_MS = 10_000L
private const val ARTWORK_MAX_BYTES = 512L * 1024
private const val CHANNEL_REPLY_MAX_BYTES = 32L * 1024
private const val DARK_CHANNEL_MAX = 40
private const val BAR_SAMPLE_STEP = 8
private const val BAR_EDGE_ROWS = 3

/**
 * YouTube's 480x360 picture of the video, the one its own pages show. Every video has it (the
 * larger `maxresdefault` is missing for many), with black bars above and below a 16:9 frame.
 */
internal fun videoArtworkUrl(videoId: String): String? =
    videoId.takeIf { VIDEO_ID_PATTERN.matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

/**
 * YouTube's public oEmbed description of the video, which names its channel (`author_name`). The
 * app's WebView page gives no channel (seen on the owner's phone 2026-11-07, although Chrome's
 * page has one), so the notification asks YouTube directly, once per video.
 */
internal fun videoChannelUrl(videoId: String): String? =
    videoId.takeIf { VIDEO_ID_PATTERN.matches(it) }?.let {
        val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$it", "UTF-8")
        "https://www.youtube.com/oembed?url=$watchUrl&format=json"
    }

/** The channel name in an oEmbed reply; blank when the reply has none. */
internal fun parseOEmbedChannel(reply: String?): String =
    runCatching { cleanChannelName(JSONObject(reply.orEmpty()).optString("author_name")) }.getOrDefault("")

/**
 * How many rows of black bar to cut from the top and from the bottom of a 4:3 picture holding a
 * 16:9 frame, so the lock screen shows the video and not the bars; 0 when the bands are not dark
 * (a Short or a 4:3 video fills the picture). [pixels] are ARGB, row by row.
 */
internal fun letterboxBarRows(
    width: Int,
    height: Int,
    pixels: IntArray,
): Int {
    if (width <= 0 || pixels.size < width * height) return 0
    val bar = (height - width * 9 / 16) / 2
    if (bar <= 0) return 0
    // JPEG blurs the rows next to the frame, so those are not checked.
    val checked = (bar - BAR_EDGE_ROWS).coerceAtLeast(1)
    val rows = (0 until checked) + (height - checked until height)
    val dark =
        rows.all { row ->
            (0 until width step BAR_SAMPLE_STEP).all { column ->
                val pixel = pixels[row * width + column]
                maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF) <= DARK_CHANNEL_MAX
            }
        }
    return if (dark) bar else 0
}

/** Downloads the picture and channel the media notification and lock screen show for the video. */
internal object VideoArtwork {
    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(ARTWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /** The video's picture without its black bars, or null when it cannot be loaded. */
    suspend fun load(videoId: String): Bitmap? {
        val data = download(videoArtworkUrl(videoId), ARTWORK_MAX_BYTES) ?: return null
        // A JPEG has no transparency; half the memory keeps the notification small.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options)?.let(::withoutLetterbox) }.getOrNull()
        }
    }

    /** The video's channel name, or blank when YouTube does not say. */
    suspend fun loadChannel(videoId: String): String =
        parseOEmbedChannel(download(videoChannelUrl(videoId), CHANNEL_REPLY_MAX_BYTES)?.decodeToString())

    private suspend fun download(
        url: String?,
        maxBytes: Long,
    ): ByteArray? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body.contentLength() > maxBytes) return@use null
                    val buffer = body.source().apply { request(maxBytes + 1) }.buffer
                    if (buffer.size > maxBytes) null else buffer.readByteArray()
                }
            }.getOrNull()
        }
    }

    private fun withoutLetterbox(picture: Bitmap): Bitmap {
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        val bar = letterboxBarRows(picture.width, picture.height, pixels)
        if (bar == 0) return picture
        return Bitmap.createBitmap(picture, 0, bar, picture.width, picture.height - 2 * bar).also {
            if (it !== picture) picture.recycle()
        }
    }
}
