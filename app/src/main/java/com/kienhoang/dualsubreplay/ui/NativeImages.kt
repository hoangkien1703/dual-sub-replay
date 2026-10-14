package com.kienhoang.dualsubreplay.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

/** Thumbnails and avatars load only from YouTube's own image hosts, over HTTPS. */
internal fun isTrustedNativeImageUrl(url: String?): Boolean {
    val parsed = url?.toHttpUrlOrNull() ?: return false
    val host = parsed.host
    val trustedHost =
        host == "i.ytimg.com" || Regex("i[0-9]\\.ytimg\\.com").matches(host) ||
            host == "yt3.ggpht.com" || host == "yt3.googleusercontent.com"
    return parsed.isHttps && parsed.port == 443 && parsed.username.isEmpty() && trustedHost
}

/** The medium thumbnail YouTube serves for every video. */
internal fun nativeThumbnailUrl(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

private object NativeImageCache {
    val memory =
        object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.width * value.height * 4
        }
    val client: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun download(url: String): ImageBitmap? {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        return response.use {
            // A redirect must still end on one of YouTube's image hosts.
            if (!it.isSuccessful || !isTrustedNativeImageUrl(it.request.url.toString())) return null
            val bytes = it.body?.byteStream()?.use { stream -> readBytesWithLimit(stream) } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    private fun readBytesWithLimit(stream: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_IMAGE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** Loads [url] once per app run; null while loading, on failure, or for an untrusted address. */
@Composable
internal fun rememberNativeImage(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let { NativeImageCache.memory.get(it) }) }
    LaunchedEffect(url) {
        val address = url?.takeIf(::isTrustedNativeImageUrl)
        if (image != null || address == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { runCatching { NativeImageCache.download(address) }.getOrNull() }
        if (loaded != null) {
            NativeImageCache.memory.put(address, loaded)
            image = loaded
        }
    }
    return image
}
