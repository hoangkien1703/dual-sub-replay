package com.kienhoang.dualsubreplay.ui

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.tanh

internal const val PRONUNCIATION_VOLUME_PREFERENCE = "pronunciation_volume"

/**
 * Words are spoken in the phone's own voice (picked in Android's text-to-speech settings, male or
 * female) instead of Google's online voice; asked for by lion737 in discussion #81 (owner pick
 * 2026-11-09). Off by default.
 */
internal const val PHONE_VOICE_PREFERENCE = "phone_voice"
internal const val DEFAULT_PRONUNCIATION_VOLUME = 1f
internal const val MIN_PRONUNCIATION_VOLUME = 0.25f
internal const val MAX_PRONUNCIATION_VOLUME = 2f

/** The slider moves in 25 % steps between its ends. */
internal const val PRONUNCIATION_VOLUME_STEPS = 6

/** Recorded speech is raised until its loudest moment reaches this share of full scale (about -1 dBFS). */
private const val SPEECH_PEAK_TARGET = 0.89f

/** At most four times louder (+12 dB), so a near-silent recording does not turn into loud noise. */
private const val MAX_SPEECH_GAIN = 4f

/** Above this share of full scale, samples are rounded off instead of cut, so loud settings do not crackle. */
private const val SOFT_LIMIT_START = 0.8f

internal fun storedPronunciationVolume(raw: Float): Float =
    if (raw.isNaN()) DEFAULT_PRONUNCIATION_VOLUME else raw.coerceIn(MIN_PRONUNCIATION_VOLUME, MAX_PRONUNCIATION_VOLUME)

internal fun readPronunciationVolume(context: Context): Float =
    storedPronunciationVolume(
        context
            .getSharedPreferences("dual_sub_preferences", 0)
            .getFloat(PRONUNCIATION_VOLUME_PREFERENCE, DEFAULT_PRONUNCIATION_VOLUME),
    )

internal fun readPhoneVoice(context: Context): Boolean =
    context.getSharedPreferences("dual_sub_preferences", 0).getBoolean(PHONE_VOICE_PREFERENCE, false)

/** The gain that brings speech whose loudest sample is [peak] (of 32767) up to the target, never down. */
internal fun speechNormalizingGain(peak: Int): Float {
    if (peak <= 0) return 1f
    return (SPEECH_PEAK_TARGET * Short.MAX_VALUE / peak).coerceIn(1f, MAX_SPEECH_GAIN)
}

/** [sample] (-1..1) after [gain], rounded off near full scale instead of clipped. */
internal fun amplifiedSample(
    sample: Float,
    gain: Float,
): Float {
    val raised = sample * gain
    val size = abs(raised)
    if (size <= SOFT_LIMIT_START) return raised
    val room = 1f - SOFT_LIMIT_START
    val limited = SOFT_LIMIT_START + room * tanh((size - SOFT_LIMIT_START) / room)
    return if (raised < 0) -limited else limited
}

/** Where the 16-bit PCM samples of a WAV file lie, or null when it is not 16-bit PCM. */
internal fun wavPcm16Data(bytes: ByteArray): IntRange? {
    if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
        return null
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var pcm16 = false
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = buffer.getInt(offset + 4)
        val start = offset + 8
        if (size < 0) return null
        when (id) {
            "fmt " -> {
                if (start + 16 > bytes.size) return null
                pcm16 = buffer.getShort(start).toInt() == 1 && buffer.getShort(start + 14).toInt() == 16
            }
            "data" -> {
                if (!pcm16) return null
                // Some engines write the header before they know the length; use what is there.
                val end = minOf(bytes.size.toLong(), start.toLong() + size).toInt()
                return start until end - (end - start) % 2
            }
        }
        offset = start + size + size % 2
    }
    return null
}

/**
 * Raises the speech in a WAV [bytes] recording to a steady loudness, in place. Speech engines
 * record at very different levels, and some far below the phone's volume. Returns false when the
 * file is not 16-bit PCM and was left as it was.
 */
internal fun normalizeSpeechWav(bytes: ByteArray): Boolean {
    val data = wavPcm16Data(bytes) ?: return false
    val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var peak = 0
    for (index in data step 2) peak = maxOf(peak, abs(samples.getShort(index).toInt()))
    val gain = speechNormalizingGain(peak)
    if (gain == 1f) return true
    for (index in data step 2) {
        val sample = samples.getShort(index) / Short.MAX_VALUE.toFloat()
        samples.putShort(index, (amplifiedSample(sample, gain) * Short.MAX_VALUE).roundToInt().toShort())
    }
    return true
}

internal fun normalizeSpeechFile(file: File): Boolean =
    runCatching {
        val bytes = file.readBytes()
        normalizeSpeechWav(bytes) && file.writeBytes(bytes).let { true }
    }.getOrDefault(false)

/** The player volume (0..1) for [volume]; louder settings use [extraGainMillibels] on top. */
internal fun playerVolume(volume: Float): Float = volume.coerceIn(0f, 1f)

/** Extra loudness above the phone's media volume, in millibels, for settings above 100 %. */
internal fun extraGainMillibels(volume: Float): Int = if (volume <= 1f) 0 else (2000 * log10(volume)).roundToInt()
