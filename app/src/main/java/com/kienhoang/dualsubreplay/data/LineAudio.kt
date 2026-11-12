package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Sound kept before a line starts and after it ends: subtitle times are rough, and speech starts and trails off. */
internal const val LINE_AUDIO_LEAD_MS = 250L
internal const val LINE_AUDIO_TAIL_MS = 400L

/** The longest line recorded; longer ones are usually several sentences with music. */
internal const val LINE_AUDIO_MAX_MS = 30_000L

/** How much played sound the page keeps; a line that ended longer ago can't be cut any more. */
internal const val LINE_AUDIO_KEPT_MS = 90_000L

/** Clips nothing needs are deleted after this long. */
internal const val LINE_AUDIO_UNUSED_MS = 24 * 60 * 60 * 1000L

private const val LINE_AUDIO_BIT_RATE = 40_000
private const val CODEC_TIMEOUT_US = 10_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val PCM_CHUNK_SAMPLES = 4_096

/** One subtitle line of one video: what a sentence clip is recorded for. */
internal data class AudioLine(
    val videoId: String,
    val startMs: Long,
    val endMs: Long,
) {
    /** The clip's file name: the line itself, so every word saved from it shares the clip. */
    val fileName: String get() = "$videoId-$startMs-$endMs.m4a"

    /** The part of the video cut for this line, in milliseconds. */
    val windowStartMs: Long get() = (startMs - LINE_AUDIO_LEAD_MS).coerceAtLeast(0L)
    val windowEndMs: Long get() = endMs + LINE_AUDIO_TAIL_MS
}

/** Extra sound played before a line's lead when it is replayed for its clip: the recorder needs a moment after the seek. */
internal const val LINE_AUDIO_REPLAY_EARLY_MS = 500L

/**
 * [word] with the times its line is replayed at so the page records the whole clip, lead and tail
 * included, for a card sent before the line had played to its end (lion737, #81, 2026-11-11);
 * null when the line can't be recorded.
 */
internal fun soundReplayClip(word: SavedWord): SavedWord? {
    val line = audioLineOf(word) ?: return null
    return word.copy(startMs = (line.windowStartMs - LINE_AUDIO_REPLAY_EARLY_MS).coerceAtLeast(0L), endMs = line.windowEndMs)
}

/** [word]'s line, or null when it has no usable video line (or one too long to record). */
internal fun audioLineOf(word: SavedWord): AudioLine? = audioLine(word.videoId, word.startMs, word.endMs)

internal fun audioLine(
    videoId: String?,
    startMs: Long,
    endMs: Long,
): AudioLine? {
    if (!validClipRange(videoId, startMs, endMs) || endMs - startMs > LINE_AUDIO_MAX_MS) return null
    return AudioLine(videoId!!, startMs, endMs)
}

/**
 * Whether the page could cut [line] now: it ended, with its tail, before [positionMs] (a video
 * [paused] at the line's end, as Pause after each line leaves it, needs no tail), recently enough
 * to be kept.
 */
internal fun lineAudioReady(
    line: AudioLine,
    positionMs: Long,
    paused: Boolean = false,
): Boolean {
    val end = if (paused) line.endMs else line.windowEndMs
    return positionMs >= end && positionMs - line.windowEndMs < LINE_AUDIO_KEPT_MS - LINE_AUDIO_MAX_MS
}

/** Sound the page cut: 16-bit mono samples at [sampleRate]. */
internal class LinePcm(
    val sampleRate: Int,
    val samples: ShortArray,
)

/** Quieter than this everywhere (about -54 dB), a clip holds no speech: it never goes on a card. */
internal const val LINE_AUDIO_SILENT_PEAK = 64

/**
 * Whether [pcm] has any sound in it. A page that hands over silence (a muted or not yet running
 * recorder) must not leave a card with a sound button that plays nothing (lion737, #81,
 * 2026-11-12); the card keeps waiting for the line instead.
 */
internal fun audible(pcm: LinePcm): Boolean = pcm.samples.any { it > LINE_AUDIO_SILENT_PEAK || it < -LINE_AUDIO_SILENT_PEAK }

/**
 * The page's answer to a clip request (the raw `evaluateJavascript` value): a JSON text with the
 * sample rate and base64 little-endian samples, or null when the page doesn't hold the whole line.
 */
internal fun parseLinePcm(rawValue: String?): LinePcm? {
    if (rawValue.isNullOrBlank()) return null
    return try {
        val text = JSONTokener(rawValue).nextValue() as? String ?: return null
        val reply = JSONObject(text)
        val rate = reply.optInt("rate", 0)
        val bytes = Base64.getDecoder().decode(reply.optString("pcm", ""))
        if (rate !in 8_000..96_000 || bytes.size < 2) return null
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        LinePcm(rate, samples)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Writes [pcm] as AAC in an MPEG-4 file ([out], usually `.m4a`), which AnkiDroid, Anki and AnkiMobile all play. */
internal fun encodeM4a(
    pcm: LinePcm,
    out: File,
): Boolean {
    val format =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, LINE_AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_SAMPLES * 2)
        }
    val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return false
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    return try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inputDone = false
        var track = -1
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val count = minOf(PCM_CHUNK_SAMPLES, buffer.capacity() / 2, pcm.samples.size - fed)
                    val time = fed * MICROS_PER_SECOND / pcm.sampleRate
                    if (count <= 0) {
                        codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm.samples, fed, count)
                        codec.queueInputBuffer(index, 0, count * 2, time, 0)
                        fed += count
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                out >= 0 -> {
                    val data = codec.getOutputBuffer(out)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxerStarted) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        muxerStarted
    } catch (_: Exception) {
        // A phone without a working AAC encoder, or a full disk: the card goes without the clip.
        false
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        if (!muxerStarted) out.delete()
    }
}

/** The sentence clips, one `.m4a` per line in `files/line-audio/` (left out of backups). */
internal class LineAudioStore(
    private val directory: File,
) {
    fun file(line: AudioLine): File? = File(directory, line.fileName).takeIf { it.isFile && it.length() > 0 }

    fun has(line: AudioLine): Boolean = file(line) != null

    /** Encodes [pcm] into [line]'s clip; false when encoding failed. */
    fun save(
        line: AudioLine,
        pcm: LinePcm,
    ): Boolean {
        directory.mkdirs()
        val part = File(directory, "${line.fileName}.part")
        if (!encodeM4a(pcm, part)) return false
        return part.renameTo(File(directory, line.fileName)) ||
            run {
                part.delete()
                false
            }
    }

    /** Deletes clips older than [LINE_AUDIO_UNUSED_MS] that no line in [needed] uses. */
    fun prune(
        needed: Set<AudioLine>,
        nowMs: Long,
    ) {
        val keep = needed.mapTo(HashSet()) { it.fileName }
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name !in keep && nowMs - file.lastModified() > LINE_AUDIO_UNUSED_MS) file.delete()
        }
    }

    companion object {
        fun of(context: Context): LineAudioStore = LineAudioStore(File(context.applicationContext.filesDir, LINE_AUDIO_DIRECTORY))
    }
}

internal const val LINE_AUDIO_DIRECTORY = "line-audio"

/** The flashcard app a waiting card is in ("ankidroid" or "ankiconnect", as `send_to_app` stores them). */
internal const val PENDING_ANKIDROID = "ankidroid"
internal const val PENDING_ANKICONNECT = "ankiconnect"

/** Cards wait at most this long for their sentence. */
internal const val PENDING_AUDIO_MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000L
internal const val PENDING_AUDIO_MAX = 300

/**
 * A card sent before its line's clip was ready: once the clip is, [field] of note [noteId] becomes
 * [base] followed by the clip's sound.
 */
internal data class PendingCardAudio(
    val app: String,
    val noteId: Long,
    val line: AudioLine,
    val field: String,
    val base: String,
    val addedAtMs: Long,
)

internal fun pendingCardAudioJson(items: List<PendingCardAudio>): String =
    JSONArray(
        items.map {
            JSONObject()
                .put("app", it.app)
                .put("note", it.noteId)
                .put("video", it.line.videoId)
                .put("start", it.line.startMs)
                .put("end", it.line.endMs)
                .put("field", it.field)
                .put("base", it.base)
                .put("at", it.addedAtMs)
        },
    ).toString()

internal fun parsePendingCardAudio(text: String?): List<PendingCardAudio> {
    if (text.isNullOrBlank()) return emptyList()
    val list = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val line = audioLine(item.optString("video"), item.optLong("start", -1), item.optLong("end", -1)) ?: return@mapNotNull null
        val app = item.optString("app").takeIf { it == PENDING_ANKIDROID || it == PENDING_ANKICONNECT } ?: return@mapNotNull null
        val field = item.optString("field").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PendingCardAudio(app, item.optLong("note"), line, field, item.optString("base"), item.optLong("at"))
    }
}

/** [items] with [added] (replacing the same note's entry), without old ones, newest [PENDING_AUDIO_MAX] kept. */
internal fun withPendingCardAudio(
    items: List<PendingCardAudio>,
    added: PendingCardAudio?,
    nowMs: Long,
): List<PendingCardAudio> {
    val kept =
        items.filter {
            nowMs - it.addedAtMs < PENDING_AUDIO_MAX_AGE_MS &&
                !(added != null && it.app == added.app && it.noteId == added.noteId)
        }
    return (kept + listOfNotNull(added)).takeLast(PENDING_AUDIO_MAX)
}

/** Cards waiting for their sentence clip, kept in `anki_audio_pending` preferences. */
internal class PendingCardAudioStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences("anki_audio_pending", 0)

    @Synchronized
    fun all(nowMs: Long = System.currentTimeMillis()): List<PendingCardAudio> =
        withPendingCardAudio(parsePendingCardAudio(preferences.getString(KEY, null)), null, nowMs)

    @Synchronized
    fun add(item: PendingCardAudio) {
        val items = withPendingCardAudio(parsePendingCardAudio(preferences.getString(KEY, null)), item, item.addedAtMs)
        preferences.edit().putString(KEY, pendingCardAudioJson(items)).apply()
    }

    @Synchronized
    fun remove(item: PendingCardAudio) {
        val items = parsePendingCardAudio(preferences.getString(KEY, null)).filterNot { it.app == item.app && it.noteId == item.noteId }
        preferences.edit().putString(KEY, pendingCardAudioJson(items)).apply()
    }

    private companion object {
        const val KEY = "items"
    }
}
