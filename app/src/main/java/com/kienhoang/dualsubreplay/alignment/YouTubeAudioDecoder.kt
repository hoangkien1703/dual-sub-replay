package com.kienhoang.dualsubreplay.alignment

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.kienhoang.dualsubreplay.data.YouTubeAudioStream
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DecodedAudioWindow(
    val samples: FloatArray,
    val startMs: Long,
)

/**
 * Decodes only the requested part of YouTube's signed adaptive-audio stream.
 * MediaExtractor performs the HTTP range reads, so the app never downloads the
 * complete video just to align a short subtitle segment.
 */
internal object YouTubeAudioDecoder {
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val CODEC_TIMEOUT_US = 10_000L

    suspend fun decodeWindow(
        source: YouTubeAudioStream,
        startMs: Long,
        endMs: Long,
    ): DecodedAudioWindow? = withContext(Dispatchers.IO) {
        if (endMs <= startMs) return@withContext null
        decodeWindowBlocking(source, startMs.coerceAtLeast(0L), endMs)
    }

    private fun decodeWindowBlocking(
        source: YouTubeAudioStream,
        startMs: Long,
        endMs: Long,
    ): DecodedAudioWindow? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var decoderStarted = false
        try {
            val headers = buildMap {
                source.userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                put("Referer", "https://www.youtube.com/")
            }
            extractor.setDataSource(source.url, headers)

            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/", ignoreCase = true) == true
            } ?: return null

            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = inputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: return null
            var channelCount = inputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: return null
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val startUs = startMs * 1_000L
            val endUs = endMs * 1_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            decoderStarted = true

            val output = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var firstAcceptedSampleUs: Long? = null
            var idleRounds = 0

            while (!outputEnded && idleRounds < 2_000) {
                var madeProgress = false

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        madeProgress = true
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
                        inputBuffer.clear()
                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = if (sampleTimeUs < 0L || sampleTimeUs > endUs) {
                            -1
                        } else {
                            extractor.readSampleData(inputBuffer, 0)
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                sampleTimeUs.coerceAtLeast(0L),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        madeProgress = true
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channelCount = outputFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                        pcmEncoding = outputFormat.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        madeProgress = true
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0 && sampleRate > 0 && channelCount > 0) {
                            val slice = outputBuffer.duplicate().apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }.slice().order(ByteOrder.nativeOrder())

                            val bytesPerSample = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                else -> 0
                            }
                            if (bytesPerSample == 0) return null

                            val sampleCount = slice.remaining() / bytesPerSample
                            val frameCount = sampleCount / channelCount
                            val frameDurationUs = 1_000_000.0 / sampleRate.toDouble()

                            for (frame in 0 until frameCount) {
                                val frameTimeUs = info.presentationTimeUs + (frame * frameDurationUs).toLong()
                                var mono = 0f
                                for (channel in 0 until channelCount) {
                                    val sampleIndex = frame * channelCount + channel
                                    mono += when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT ->
                                            slice.getFloat(sampleIndex * 4)
                                        else ->
                                            slice.getShort(sampleIndex * 2) / 32768f
                                    }
                                }
                                if (frameTimeUs in startUs until endUs) {
                                    if (firstAcceptedSampleUs == null) firstAcceptedSampleUs = frameTimeUs
                                    output.add((mono / channelCount).coerceIn(-1f, 1f))
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                idleRounds = if (madeProgress) 0 else idleRounds + 1
            }

            val decoded = output.toArray()
            if (decoded.isEmpty() || sampleRate <= 0) return null
            val resampled = resampleLinear(decoded, sampleRate, TARGET_SAMPLE_RATE)
            if (resampled.size < TARGET_SAMPLE_RATE / 4) return null
            return DecodedAudioWindow(
                samples = resampled,
                startMs = (firstAcceptedSampleUs ?: startUs) / 1_000L,
            )
        } finally {
            if (decoder != null) {
                if (decoderStarted) runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    internal fun resampleLinear(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int,
    ): FloatArray {
        if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return floatArrayOf()
        if (sourceRate == targetRate) return input
        if (input.size == 1) return floatArrayOf(input[0])

        val sourcePerTarget = sourceRate.toDouble() / targetRate.toDouble()
        val outputSize = (((input.size - 1) / sourcePerTarget).toInt() + 1).coerceAtLeast(1)
        return FloatArray(outputSize) { index ->
            val sourcePosition = index * sourcePerTarget
            val left = sourcePosition.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            input[left] + (input[right] - input[left]) * fraction
        }
    }

    private class FloatArrayBuilder(initialCapacity: Int = 16_384) {
        private var values = FloatArray(initialCapacity)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) {
                values = values.copyOf((values.size * 2).coerceAtLeast(1))
            }
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
