package com.kienhoang.dualsubreplay.assistant

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/** Pictures are scaled so their longer side is at most this many pixels, which every service reads well. */
internal const val MAX_AI_PICTURE_SIDE = 1568
private const val AI_PICTURE_JPEG_QUALITY = 85

/** Larger PDFs and text files are refused rather than cut, so the model never reads half a document. */
internal const val MAX_AI_PDF_BYTES = 8 * 1024 * 1024
internal const val MAX_AI_TEXT_FILE_BYTES = 2 * 1024 * 1024

internal enum class AiAttachmentProblem {
    TOO_BIG,
    UNSUPPORTED,
    UNREADABLE,
}

/** A chosen picture or file, read and ready to send, or why it was not added. */
internal sealed interface AiAttachmentRead {
    val name: String

    data class Added(
        val attachment: AiAttachment,
    ) : AiAttachmentRead {
        override val name: String get() = attachment.name
    }

    data class Refused(
        override val name: String,
        val problem: AiAttachmentProblem,
    ) : AiAttachmentRead
}

/** The size a picture is sent at: its own, or smaller so its longer side is [maxSide]. */
internal fun aiPictureTargetSize(
    width: Int,
    height: Int,
    maxSide: Int = MAX_AI_PICTURE_SIDE,
): Pair<Int, Int> {
    val longer = max(width, height)
    if (longer <= maxSide) return width to height
    val scale = maxSide.toDouble() / longer
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

/** The power-of-two step a picture is decoded at, so a huge photo never fills memory before it is scaled. */
internal fun aiPictureSampleSize(
    width: Int,
    height: Int,
    maxSide: Int = MAX_AI_PICTURE_SIDE,
): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** A text file's bytes as text: UTF-16 when it starts with that byte-order mark, otherwise UTF-8. */
internal fun aiDecodeText(bytes: ByteArray): String {
    val utf16 =
        bytes.size >= 2 &&
            (
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )
    return String(bytes, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
}

internal fun aiDataUrl(
    mimeType: String,
    bytes: ByteArray,
): String = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

/** The bytes of a `data:…;base64,` URL, or null when it is not one. */
internal fun aiDataUrlBytes(dataUrl: String): ByteArray? {
    if (!dataUrl.startsWith("data:")) return null
    val comma = dataUrl.indexOf(";base64,")
    if (comma < 0) return null
    return runCatching { Base64.getDecoder().decode(dataUrl.substring(comma + ";base64,".length)) }.getOrNull()
}

/** A small picture for the chips and bubbles that show a sent or chosen picture. */
internal fun aiPictureThumbnail(
    dataUrl: String,
    maxSide: Int,
): Bitmap? {
    val bytes = aiDataUrlBytes(dataUrl) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = aiPictureSampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/**
 * Reads pictures and files the user chose for a question. Pictures are scaled down and sent as
 * JPEG, PDFs as they are, and text files as text. Call [read] off the main thread.
 */
internal class AiAttachmentReader(
    private val resolver: ContentResolver,
) {
    fun read(uri: Uri): AiAttachmentRead {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val kind =
            aiAttachmentKindFor(runCatching { resolver.getType(uri) }.getOrNull(), name)
                ?: return AiAttachmentRead.Refused(name, AiAttachmentProblem.UNSUPPORTED)
        return try {
            val data =
                when (kind) {
                    AiAttachmentKind.PICTURE -> picture(uri)
                    AiAttachmentKind.PDF -> bytes(uri, MAX_AI_PDF_BYTES)?.let { aiDataUrl("application/pdf", it) }
                    AiAttachmentKind.TEXT -> bytes(uri, MAX_AI_TEXT_FILE_BYTES)?.let { aiTextAttachment(aiDecodeText(it)) }
                } ?: return AiAttachmentRead.Refused(name, AiAttachmentProblem.TOO_BIG)
            if (data.isEmpty()) {
                AiAttachmentRead.Refused(name, AiAttachmentProblem.UNREADABLE)
            } else {
                AiAttachmentRead.Added(AiAttachment(name, kind, data))
            }
        } catch (error: IOException) {
            AiAttachmentRead.Refused(name, AiAttachmentProblem.UNREADABLE)
        } catch (error: SecurityException) {
            AiAttachmentRead.Refused(name, AiAttachmentProblem.UNREADABLE)
        } catch (error: IllegalArgumentException) {
            AiAttachmentRead.Refused(name, AiAttachmentProblem.UNREADABLE)
        }
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun open(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")

    /** The whole file, or null when it is larger than [limit]. */
    private fun bytes(
        uri: Uri,
        limit: Int,
    ): ByteArray? =
        open(uri).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        }

    private fun picture(uri: Uri): String {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeScaled(uri) else decodeSampled(uri)
        val bitmap = decoded.onWhite()
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                AI_PICTURE_JPEG_QUALITY,
                out,
            )
        ) {
            throw IOException("The picture could not be encoded.")
        }
        return aiDataUrl("image/jpeg", out.toByteArray())
    }

    /** Android 9 and later also turn the picture the way the camera held it. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeScaled(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (width, height) = aiPictureTargetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun decodeSampled(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The file is not a picture.")
        val options = BitmapFactory.Options().apply { inSampleSize = aiPictureSampleSize(bounds.outWidth, bounds.outHeight) }
        val sampled = open(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("The file is not a picture.")
        val (width, height) = aiPictureTargetSize(sampled.width, sampled.height)
        return if (width == sampled.width && height == sampled.height) sampled else sampled.scale(width, height)
    }

    /** JPEG has no transparency, so a see-through picture goes on white instead of black. */
    private fun Bitmap.onWhite(): Bitmap {
        if (!hasAlpha()) return this
        val flat = createBitmap(width, height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@onWhite, 0f, 0f, null)
        }
        return flat
    }
}
