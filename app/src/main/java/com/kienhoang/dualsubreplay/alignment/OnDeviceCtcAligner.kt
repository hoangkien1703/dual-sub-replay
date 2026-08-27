package com.kienhoang.dualsubreplay.alignment

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.kienhoang.dualsubreplay.data.KaraokeSyncDiagnostic
import com.kienhoang.dualsubreplay.data.KaraokeSyncDiagnostics
import com.kienhoang.dualsubreplay.data.KaraokeSyncMode
import com.kienhoang.dualsubreplay.data.KaraokeSyncPreferences
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleTimingSource
import com.kienhoang.dualsubreplay.data.SubtitleWord
import com.kienhoang.dualsubreplay.data.YouTubeAudioStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AcousticModelStatus(
    val installed: Boolean,
    val sizeBytes: Long,
)

/**
 * WhisperX-style forced alignment without a cloud service.
 *
 * The English Wav2Vec2 model is intentionally not bundled in the APK. Users can
 * download it from More settings if they want acoustic word synchronization.
 * Generated-caption YouTube timestamps can then be tested either as the strict
 * PR #33 anchors or as soft priors that acoustic evidence may move slightly.
 */
class OnDeviceCtcAligner(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val appContext = context.applicationContext
    private val modelStore = AlignmentModelStore(appContext, httpClient)

    fun modelStatus(): AcousticModelStatus = modelStore.status()

    suspend fun downloadModel(): AcousticModelStatus {
        modelStore.requireModel()
        KaraokeSyncPreferences.setAcousticModelEnabled(appContext, true)
        return modelStore.status()
    }

    fun deleteModel(): Boolean {
        KaraokeSyncPreferences.setAcousticModelEnabled(appContext, false)
        return modelStore.deleteModel()
    }

    suspend fun alignSegments(
        source: YouTubeAudioStream,
        segments: List<SubtitleSegment>,
        preferredIndex: Int,
        onAligned: suspend (index: Int, segment: SubtitleSegment) -> Unit,
    ): Int {
        val mode = KaraokeSyncPreferences.mode(appContext)
        if (mode == KaraokeSyncMode.ESTIMATED_ONLY) return 0
        if (!KaraokeSyncPreferences.acousticModelEnabled(appContext)) return 0

        val eligibleIndices = alignmentOrder(segments.size, preferredIndex).filter { index ->
            when (mode) {
                KaraokeSyncMode.PR33_CURRENT -> segments[index].words.any { word ->
                    word.timingSource != SubtitleTimingSource.YOUTUBE_EXACT
                }
                KaraokeSyncMode.SOFT_ANCHOR,
                KaraokeSyncMode.ENHANCED,
                -> segments[index].words.isNotEmpty()
                KaraokeSyncMode.ESTIMATED_ONLY -> false
            }
        }
        if (eligibleIndices.isEmpty()) return 0

        val modelFile = modelStore.requireModel()
        val environment = OrtEnvironment.getEnvironment()
        var alignedCount = 0

        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(
                Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_INFERENCE_THREADS),
            )
            environment.createSession(modelFile.absolutePath, options).use { session ->
                for (index in eligibleIndices) {
                    currentCoroutineContext().ensureActive()
                    val aligned = runCatching {
                        alignOne(session, source, segments[index], mode)
                    }.onFailure { error ->
                        Log.w(LOG_TAG, "Acoustic alignment skipped segment $index.", error)
                    }.getOrNull()
                    if (aligned != null) {
                        onAligned(index, aligned)
                        alignedCount += 1
                    }
                }
            }
        }
        return alignedCount
    }

    private suspend fun alignOne(
        session: OrtSession,
        source: YouTubeAudioStream,
        segment: SubtitleSegment,
        mode: KaraokeSyncMode,
    ): SubtitleSegment? {
        if (segment.words.isEmpty()) return null
        val target = ctcTargetForWords(segment.words.map(SubtitleWord::text)) ?: return null
        if (target.labels.size < MIN_TARGET_LABELS) return null

        val contextMs = if (mode == KaraokeSyncMode.ENHANCED) {
            ENHANCED_ALIGNMENT_CONTEXT_MS
        } else {
            ALIGNMENT_CONTEXT_MS
        }
        val windowStart = (segment.startMs - contextMs).coerceAtLeast(0L)
        val windowEnd = segment.endMs + contextMs
        val decodedAudio = YouTubeAudioDecoder.decodeWindow(source, windowStart, windowEnd) ?: return null
        val audio = if (mode == KaraokeSyncMode.ENHANCED) {
            trimOuterSilence(decodedAudio)
        } else {
            decodedAudio
        }
        val normalizedAudio = normalizeAudio(audio.samples) ?: return null
        val inference = runInference(session, normalizedAudio) ?: return null

        val greedyText = greedyCtcText(
            logits = inference.logits,
            frameCount = inference.frameCount,
            vocabSize = inference.vocabSize,
        )
        val coverage = orderedTextCoverage(target.normalizedText, greedyText)
        val requiredCoverage = if (mode == KaraokeSyncMode.PR33_CURRENT) {
            MIN_TEXT_COVERAGE
        } else {
            MIN_SOFT_ANCHOR_TEXT_COVERAGE
        }
        if (coverage < requiredCoverage) return null

        val labelSpans = withContext(Dispatchers.Default) {
            viterbiCtcAlignment(
                logits = inference.logits,
                frameCount = inference.frameCount,
                vocabSize = inference.vocabSize,
                target = target,
            )
        } ?: return null
        val wordSpans = ctcWordFrameSpans(target, labelSpans)
        if (wordSpans.isEmpty()) return null

        val audioDurationMs = normalizedAudio.size * 1_000.0 / ALIGNMENT_SAMPLE_RATE
        val millisecondsPerFrame = audioDurationMs / inference.frameCount.toDouble()
        val rawAcousticWords = segment.words.mapIndexed { index, word ->
            val span = wordSpans[index] ?: return@mapIndexed word
            if (
                mode == KaraokeSyncMode.PR33_CURRENT &&
                word.timingSource == SubtitleTimingSource.YOUTUBE_EXACT
            ) {
                return@mapIndexed word
            }
            val start = audio.startMs + (span.firstFrame * millisecondsPerFrame).toLong()
            val end = audio.startMs + ((span.lastFrame + 1) * millisecondsPerFrame).toLong()
            if (end <= start) {
                word
            } else {
                SubtitleWord(
                    text = word.text,
                    startMs = start,
                    endMs = end,
                    timingSource = SubtitleTimingSource.ACOUSTIC_ALIGNED,
                )
            }
        }

        val stabilized = when (mode) {
            KaraokeSyncMode.PR33_CURRENT ->
                stabilizeAroundExactAnchors(segment.words, rawAcousticWords)
            KaraokeSyncMode.SOFT_ANCHOR,
            KaraokeSyncMode.ENHANCED,
            -> applySoftAnchorLimits(segment.words, rawAcousticWords)
            KaraokeSyncMode.ESTIMATED_ONLY -> return null
        }
        if (stabilized.none { it.timingSource == SubtitleTimingSource.ACOUSTIC_ALIGNED }) return null
        if (!isMonotonicEnough(stabilized)) return null

        if (KaraokeSyncPreferences.diagnosticsEnabled(appContext)) {
            stabilized.forEachIndexed { index, used ->
                if (used.timingSource != SubtitleTimingSource.ACOUSTIC_ALIGNED) return@forEachIndexed
                val original = segment.words[index]
                val rawAcoustic = rawAcousticWords[index]
                KaraokeSyncDiagnostics.record(
                    KaraokeSyncDiagnostic(
                        word = original.text,
                        originalSource = original.timingSource,
                        originalStartMs = original.startMs,
                        acousticStartMs = rawAcoustic.startMs,
                        usedStartMs = used.startMs,
                    ),
                )
                Log.d(
                    LOG_TAG,
                    "word=${original.text} source=${original.timingSource} " +
                        "original=${original.startMs} acoustic=${rawAcoustic.startMs} used=${used.startMs}",
                )
            }
        }

        return segment.copy(
            startMs = stabilized.minOf(SubtitleWord::startMs),
            endMs = stabilized.maxOf(SubtitleWord::endMs),
            words = stabilized,
        )
    }

    private suspend fun runInference(
        session: OrtSession,
        normalizedAudio: FloatArray,
    ): InferenceOutput? = withContext(Dispatchers.Default) {
        val environment = OrtEnvironment.getEnvironment()
        val inputName = session.inputNames.firstOrNull() ?: return@withContext null
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(normalizedAudio),
            longArrayOf(1L, normalizedAudio.size.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as? OnnxTensor ?: return@withContext null
                val shape = output.info.shape
                if (shape.size != 3 || shape[0] != 1L) return@withContext null
                val frameCount = shape[1].toInt()
                val vocabSize = shape[2].toInt()
                if (frameCount <= 0 || vocabSize != CTC_VOCAB_SIZE) return@withContext null
                val floatBuffer = output.floatBuffer ?: return@withContext null
                val logits = FloatArray(frameCount * vocabSize)
                if (floatBuffer.remaining() < logits.size) return@withContext null
                floatBuffer.get(logits)
                InferenceOutput(logits, frameCount, vocabSize)
            }
        }
    }

    private fun normalizeAudio(samples: FloatArray): FloatArray? {
        if (samples.size < ALIGNMENT_SAMPLE_RATE / 4) return null
        var sum = 0.0
        samples.forEach { sum += it }
        val mean = sum / samples.size
        var varianceSum = 0.0
        samples.forEach { sample ->
            val delta = sample - mean
            varianceSum += delta * delta
        }
        val standardDeviation = sqrt(varianceSum / samples.size + 1e-7)
        if (!standardDeviation.isFinite() || standardDeviation < 1e-5) return null
        return FloatArray(samples.size) { index ->
            ((samples[index] - mean) / standardDeviation).toFloat()
        }
    }

    /**
     * Enhanced mode uses larger overlapping segment windows. Trim only silence
     * at the outer edges, keeping generous padding so we never cut a phoneme.
     */
    private fun trimOuterSilence(audio: DecodedAudioWindow): DecodedAudioWindow {
        val samples = audio.samples
        if (samples.size < ALIGNMENT_SAMPLE_RATE) return audio

        val frameSamples = ALIGNMENT_SAMPLE_RATE * VAD_FRAME_MS / 1_000
        if (frameSamples <= 0) return audio
        val frameCount = samples.size / frameSamples
        if (frameCount <= 1) return audio

        val rms = FloatArray(frameCount)
        var peak = 0f
        for (frame in 0 until frameCount) {
            var sumSquares = 0.0
            val start = frame * frameSamples
            val end = start + frameSamples
            for (index in start until end) {
                val value = samples[index].toDouble()
                sumSquares += value * value
            }
            val value = sqrt(sumSquares / frameSamples).toFloat()
            rms[frame] = value
            peak = maxOf(peak, value)
        }
        if (peak <= 0f) return audio

        val threshold = maxOf(MIN_VAD_RMS, peak * VAD_PEAK_FRACTION)
        val firstActiveFrame = rms.indexOfFirst { it >= threshold }.takeIf { it >= 0 } ?: return audio
        val lastActiveFrame = rms.indexOfLast { it >= threshold }.takeIf { it >= 0 } ?: return audio
        val paddingSamples = ALIGNMENT_SAMPLE_RATE * VAD_PADDING_MS / 1_000
        val startSample = (firstActiveFrame * frameSamples - paddingSamples).coerceAtLeast(0)
        val endSample = ((lastActiveFrame + 1) * frameSamples + paddingSamples).coerceAtMost(samples.size)
        if (endSample - startSample < ALIGNMENT_SAMPLE_RATE / 2) return audio

        return DecodedAudioWindow(
            samples = samples.copyOfRange(startSample, endSample),
            startMs = audio.startMs + startSample * 1_000L / ALIGNMENT_SAMPLE_RATE,
        )
    }

    private fun stabilizeAroundExactAnchors(
        original: List<SubtitleWord>,
        candidate: List<SubtitleWord>,
    ): List<SubtitleWord> {
        if (original.size != candidate.size) return original
        val previousExactEnd = LongArray(original.size) { Long.MIN_VALUE }
        var previous = Long.MIN_VALUE
        original.indices.forEach { index ->
            previousExactEnd[index] = previous
            if (original[index].timingSource == SubtitleTimingSource.YOUTUBE_EXACT) {
                previous = original[index].endMs
            }
        }

        val nextExactStart = LongArray(original.size) { Long.MAX_VALUE }
        var next = Long.MAX_VALUE
        for (index in original.indices.reversed()) {
            nextExactStart[index] = next
            if (original[index].timingSource == SubtitleTimingSource.YOUTUBE_EXACT) {
                next = original[index].startMs
            }
        }

        return candidate.mapIndexed { index, word ->
            if (word.timingSource != SubtitleTimingSource.ACOUSTIC_ALIGNED) {
                return@mapIndexed word
            }
            val lower = previousExactEnd[index]
            val upper = nextExactStart[index]
            val boundedStart = if (lower == Long.MIN_VALUE) word.startMs else maxOf(word.startMs, lower)
            val boundedEnd = if (upper == Long.MAX_VALUE) word.endMs else minOf(word.endMs, upper)
            if (boundedEnd - boundedStart < MIN_ALIGNED_WORD_MS) {
                original[index]
            } else {
                word.copy(startMs = boundedStart, endMs = boundedEnd)
            }
        }
    }

    /**
     * Auto-caption anchors are priors in the experimental modes, not immutable
     * phoneme boundaries. Strong CTC timing may move an anchored word within a
     * conservative +/-400 ms search range while estimated words remain free.
     */
    private fun applySoftAnchorLimits(
        original: List<SubtitleWord>,
        candidate: List<SubtitleWord>,
    ): List<SubtitleWord> {
        if (original.size != candidate.size) return original
        return candidate.mapIndexed { index, word ->
            if (word.timingSource != SubtitleTimingSource.ACOUSTIC_ALIGNED) {
                return@mapIndexed word
            }
            val anchor = original[index]
            if (anchor.timingSource != SubtitleTimingSource.YOUTUBE_EXACT) {
                return@mapIndexed word
            }

            val minimumStart = anchor.startMs - KaraokeSyncPreferences.SOFT_ANCHOR_RANGE_MS
            val maximumStart = anchor.startMs + KaraokeSyncPreferences.SOFT_ANCHOR_RANGE_MS
            val boundedStart = word.startMs.coerceIn(minimumStart, maximumStart)
            val appliedShift = boundedStart - word.startMs
            val boundedEnd = (word.endMs + appliedShift)
                .coerceAtLeast(boundedStart + MIN_ALIGNED_WORD_MS)
            word.copy(startMs = boundedStart, endMs = boundedEnd)
        }
    }

    private fun isMonotonicEnough(words: List<SubtitleWord>): Boolean {
        if (words.isEmpty()) return false
        var previousStart = Long.MIN_VALUE
        words.forEach { word ->
            if (word.endMs <= word.startMs) return false
            if (previousStart != Long.MIN_VALUE && word.startMs < previousStart) {
                return false
            }
            previousStart = word.startMs
        }
        return true
    }

    private data class InferenceOutput(
        val logits: FloatArray,
        val frameCount: Int,
        val vocabSize: Int,
    )

    companion object {
        private const val LOG_TAG = "DualSubAlignment"
        private const val ALIGNMENT_SAMPLE_RATE = 16_000
        private const val ALIGNMENT_CONTEXT_MS = 1_500L
        private const val ENHANCED_ALIGNMENT_CONTEXT_MS = 3_000L
        private const val MIN_TARGET_LABELS = 2
        private const val MIN_TEXT_COVERAGE = 0.52f
        private const val MIN_SOFT_ANCHOR_TEXT_COVERAGE = 0.65f
        private const val MIN_ALIGNED_WORD_MS = 25L
        private const val MAX_INFERENCE_THREADS = 4
        private const val VAD_FRAME_MS = 20
        private const val VAD_PADDING_MS = 350
        private const val MIN_VAD_RMS = 0.008f
        private const val VAD_PEAK_FRACTION = 0.08f
    }
}

internal fun alignmentOrder(size: Int, preferredIndex: Int): List<Int> {
    if (size <= 0) return emptyList()
    val focus = preferredIndex.coerceIn(0, size - 1)
    return (0 until size).sortedWith(
        compareBy<Int> { abs(it - focus) }.thenBy { it },
    )
}

private class AlignmentModelStore(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    private val directory = File(context.noBackupFilesDir, "acoustic_alignment")
    private val modelFile = File(directory, MODEL_FILE_NAME)
    private val verifiedMarker = File(directory, "$MODEL_FILE_NAME.sha256")

    fun status(): AcousticModelStatus = AcousticModelStatus(
        installed = modelFile.isFile && verifiedMarker.readTextOrNull()?.trim() == MODEL_SHA256,
        sizeBytes = modelFile.takeIf(File::isFile)?.length() ?: 0L,
    )

    fun deleteModel(): Boolean {
        File(directory, "$MODEL_FILE_NAME.part").delete()
        verifiedMarker.delete()
        modelFile.delete()
        return !modelFile.exists()
    }

    suspend fun requireModel(): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        if (isVerifiedModel()) return@withContext modelFile
        downloadAndVerify()
        modelFile
    }

    private fun isVerifiedModel(): Boolean {
        if (!modelFile.isFile) return false
        if (verifiedMarker.readTextOrNull()?.trim() == MODEL_SHA256) return true
        val actual = sha256(modelFile)
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            modelFile.delete()
            verifiedMarker.delete()
            return false
        }
        verifiedMarker.writeText(MODEL_SHA256)
        return true
    }

    private fun downloadAndVerify() {
        val temporary = File(directory, "$MODEL_FILE_NAME.part")
        temporary.delete()
        val request = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "DualSub-Replay acoustic alignment")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Alignment model download failed with HTTP ${response.code}.")
            }
            val body = response.body ?: throw IOException("Alignment model response was empty.")
            temporary.outputStream().buffered().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }

        val actualSha = sha256(temporary)
        if (!actualSha.equals(MODEL_SHA256, ignoreCase = true)) {
            temporary.delete()
            throw IOException("Alignment model checksum did not match.")
        }

        runCatching {
            Files.move(
                temporary.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        verifiedMarker.writeText(MODEL_SHA256)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { takeIf { it.isFile }?.readText() }.getOrNull()

    companion object {
        private const val MODEL_FILE_NAME = "wav2vec2-base-960h-int8.onnx"
        private const val MODEL_URL =
            "https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX/resolve/main/onnx/model_int8.onnx?download=true"
        private const val MODEL_SHA256 =
            "2aa77535eea4282df0374ff011c330e7abd84010f5d0cc079e5ed6c8ede2b6c5"
    }
}
