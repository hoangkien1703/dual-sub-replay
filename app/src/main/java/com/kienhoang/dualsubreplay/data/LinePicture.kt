package com.kienhoang.dualsubreplay.data

import android.content.Context
import org.json.JSONException
import org.json.JSONTokener
import java.io.File
import java.util.Base64

/**
 * Pictures on Anki cards (owner pick 2026-11-10, spec docs/specs/2026-11-10-anki-card-picture-and-front.md):
 * a still the YouTube page takes from its own video while the word's line is on screen, else the
 * video's thumbnail.
 */
internal const val LINE_PICTURE_DIRECTORY = "line-pictures"

/** The widest still kept; enough for a phone card, about 40 to 80 KB as JPEG. */
internal const val LINE_PICTURE_MAX_WIDTH = 640

/** A still or thumbnail bigger than this is not a picture we asked for. */
internal const val LINE_PICTURE_MAX_BYTES = 1_500_000

private const val JPEG_DATA_URL = "data:image/jpeg;base64,"

/** The video's time a still for [line] may be taken at: the line on screen, or just after it ends. */
internal fun linePictureTime(
    line: AudioLine,
    positionMs: Long,
): Boolean = positionMs in line.startMs..line.windowEndMs

/** [word]'s line for a picture: any line of a video, also one too long to record. */
internal fun pictureLineOf(word: SavedWord): AudioLine? =
    word.videoId?.takeIf { validClipRange(it, word.startMs, word.endMs) }?.let { AudioLine(it, word.startMs, word.endMs) }

/** The still's file name: like the clip's, so every word of a line shares it. */
internal val AudioLine.pictureName: String get() = "$videoId-$startMs-$endMs.jpg"

/** True for bytes that look like a JPEG of a sane size. */
internal fun isJpeg(bytes: ByteArray?): Boolean =
    bytes != null &&
        bytes.size in 4..LINE_PICTURE_MAX_BYTES &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

/** The page's answer to a still request (the raw `evaluateJavascript` value, a JPEG data URL), or null. */
internal fun parseLinePicture(rawValue: String?): ByteArray? {
    if (rawValue.isNullOrBlank()) return null
    return try {
        val text = JSONTokener(rawValue).nextValue() as? String ?: return null
        if (!text.startsWith(JPEG_DATA_URL) || text.length > LINE_PICTURE_MAX_BYTES * 2) return null
        Base64.getDecoder().decode(text.substring(JPEG_DATA_URL.length)).takeIf(::isJpeg)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The address of [videoId]'s 320 × 180 thumbnail on YouTube's image host. */
internal fun videoThumbnailUrl(videoId: String): String? =
    videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

/** The stills and thumbnails, in `files/line-pictures/` (left out of backups). */
internal class LinePictureStore(
    private val directory: File,
) {
    fun file(line: AudioLine): File? = existing(line.pictureName)

    fun thumbnail(videoId: String): File? = existing(thumbnailName(videoId))

    fun save(
        line: AudioLine,
        jpeg: ByteArray,
    ): File? = write(line.pictureName, jpeg)

    fun saveThumbnail(
        videoId: String,
        jpeg: ByteArray,
    ): File? = write(thumbnailName(videoId), jpeg)

    /** Deletes pictures older than a day that no line in [needed] (or its video's thumbnail) uses. */
    fun prune(
        needed: Set<AudioLine>,
        nowMs: Long,
    ) {
        val keep = needed.flatMapTo(HashSet()) { listOf(it.pictureName, thumbnailName(it.videoId)) }
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name !in keep && nowMs - file.lastModified() > LINE_AUDIO_UNUSED_MS) file.delete()
        }
    }

    private fun existing(name: String): File? = File(directory, name).takeIf { it.isFile && it.length() > 0 }

    private fun write(
        name: String,
        jpeg: ByteArray,
    ): File? {
        if (!isJpeg(jpeg)) return null
        directory.mkdirs()
        val part = File(directory, "$name.part")
        val file = File(directory, name)
        return try {
            part.writeBytes(jpeg)
            if (part.renameTo(file)) file else null
        } catch (_: java.io.IOException) {
            null
        } finally {
            part.delete()
        }
    }

    private fun thumbnailName(videoId: String) = "$videoId-thumbnail.jpg"

    companion object {
        fun of(context: Context): LinePictureStore = LinePictureStore(File(context.applicationContext.filesDir, LINE_PICTURE_DIRECTORY))
    }
}
