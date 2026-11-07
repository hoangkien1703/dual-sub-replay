package com.kienhoang.dualsubreplay.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
private const val ARTWORK_TIMEOUT_MS = 10_000L
private const val ARTWORK_MAX_BYTES = 512L * 1024
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

/** Downloads the picture the media notification and lock screen show for the playing video. */
internal object VideoArtwork {
    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(ARTWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /** The video's picture without its black bars, or null when it cannot be loaded. */
    suspend fun load(videoId: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val url = videoArtworkUrl(videoId) ?: return@withContext null
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body.contentLength() > ARTWORK_MAX_BYTES) return@use null
                    val buffer = body.source().apply { request(ARTWORK_MAX_BYTES + 1) }.buffer
                    if (buffer.size > ARTWORK_MAX_BYTES) return@use null
                    val data = buffer.readByteArray()
                    // A JPEG has no transparency; half the memory keeps the notification small.
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)?.let(::withoutLetterbox)
                }
            }.getOrNull()
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
