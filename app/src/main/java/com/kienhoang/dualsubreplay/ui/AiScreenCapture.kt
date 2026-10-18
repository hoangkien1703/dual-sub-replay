package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.kienhoang.dualsubreplay.assistant.AiAttachment
import com.kienhoang.dualsubreplay.assistant.AiAttachmentKind
import com.kienhoang.dualsubreplay.assistant.aiDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** The longer side of a screenshot the model sees, in pixels; enough to read subtitles. */
internal const val AI_SCREENSHOT_MAX_SIDE = 1280
private const val AI_SCREENSHOT_JPEG_QUALITY = 80

/** Frames drawn with the panel hidden before the copy, so the copy never shows it half gone. */
private const val HIDDEN_FRAMES = 2

/** The size a screenshot is scaled to: at most [maxSide] on its longer side, never larger than it was. */
internal fun aiScreenshotSize(
    width: Int,
    height: Int,
    maxSide: Int = AI_SCREENSHOT_MAX_SIDE,
): Pair<Int, Int> {
    val longer = maxOf(width, height)
    if (longer <= maxSide || longer <= 0) return width to height
    val scale = maxSide.toFloat() / longer
    return maxOf(1, Math.round(width * scale)) to maxOf(1, Math.round(height * scale))
}

/**
 * Takes the assistant's screenshots of the app window. While [hidden] is true the panel and its dim
 * background draw nothing, so the copy shows the app behind them; it is true only for the two
 * frames before the copy.
 */
internal class AiScreenCapture {
    var hidden by mutableStateOf(false)
        private set

    /** A JPEG of [activity]'s window as a picture attachment, or null when the window can't be copied now. */
    suspend fun capture(activity: Activity): AiAttachment? {
        val bitmap =
            withContext(Dispatchers.Main) {
                val decor = activity.window?.peekDecorView() ?: return@withContext null
                if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return@withContext null
                hidden = true
                try {
                    repeat(HIDDEN_FRAMES) { awaitFrame() }
                    copyWindow(activity, decor.width, decor.height)
                } finally {
                    hidden = false
                }
            } ?: return null
        return withContext(Dispatchers.Default) {
            val (width, height) = aiScreenshotSize(bitmap.width, bitmap.height)
            val scaled = if (width == bitmap.width) bitmap else bitmap.scale(width, height)
            val bytes =
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, AI_SCREENSHOT_JPEG_QUALITY, out)
                    out.toByteArray()
                }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            AiAttachment("screenshot.jpg", AiAttachmentKind.PICTURE, aiDataUrl("image/jpeg", bytes))
        }
    }

    private suspend fun awaitFrame() =
        suspendCancellableCoroutine { continuation ->
            val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
            Choreographer.getInstance().postFrameCallback(callback)
            continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
        }

    private suspend fun copyWindow(
        activity: Activity,
        width: Int,
        height: Int,
    ): Bitmap? {
        val window = activity.window ?: return null
        val bitmap = createBitmap(width, height)
        val copied =
            suspendCancellableCoroutine { continuation ->
                try {
                    PixelCopy.request(
                        window,
                        bitmap,
                        { result -> if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS) },
                        Handler(Looper.getMainLooper()),
                    )
                } catch (_: IllegalArgumentException) {
                    // The window has no surface yet, or lost it.
                    continuation.resume(false)
                }
            }
        if (!copied) bitmap.recycle()
        return bitmap.takeIf { copied }
    }
}
