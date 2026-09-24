package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal const val SUBTITLE_LOOK_AHEAD_MS = 60_000L
internal const val SUBTITLE_LOOK_BEHIND_MS = 30_000L
internal const val MAX_WINDOW_SEGMENTS = 96

/** Only timestamps and file offsets stay in memory; text and word timings live on disk. */
internal class SubtitleStore private constructor(
    private val file: File,
    private val starts: LongArray,
    private val offsets: LongArray,
) : Closeable {
    val size: Int get() = starts.size

    fun windowIndices(timeMs: Long): IntRange {
        if (size == 0) return IntRange.EMPTY
        val current = (upperBound(timeMs) - 1).coerceAtLeast(0)
        // Keep one boundary cue for a sentence that started before the time window.
        val first = maxOf(0, upperBound(timeMs - SUBTITLE_LOOK_BEHIND_MS) - 1, current - MAX_WINDOW_SEGMENTS / 3)
        val last = minOf(size - 1, upperBound(timeMs + SUBTITLE_LOOK_AHEAD_MS) - 1, first + MAX_WINDOW_SEGMENTS - 1)
        return first..maxOf(first, last)
    }

    private fun upperBound(timeMs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (starts[middle] <= timeMs) low = middle + 1 else high = middle
        }
        return low
    }

    /** Call on an IO dispatcher. Each read owns its handle so cancellation cannot leak it. */
    suspend fun read(indices: IntRange): List<SubtitleSegment> {
        if (indices.isEmpty()) return emptyList()
        require(indices.first >= 0 && indices.last < size) {
            "Subtitle window ${indices.first}..${indices.last} is outside 0..${size - 1}."
        }
        return FileInputStream(file).use { source ->
            source.channel.position(offsets[indices.first])
            DataInputStream(BufferedInputStream(source)).use { input ->
                indices.map {
                    currentCoroutineContext().ensureActive()
                    input.readSegment()
                }
            }
        }
    }

    override fun close() {
        file.delete()
    }

    companion object {
        /** The provider's response is capped separately at 8 MiB. Bound expanded storage too. */
        private const val MAX_STORE_BYTES = 64L * 1024 * 1024
        private const val MAX_SEGMENTS = 200_000

        suspend fun create(
            directory: File,
            segments: List<SubtitleSegment>,
        ): SubtitleStore = create(directory) { append -> append(segments) }

        /**
         * Writes caption batches directly to disk. This keeps formatting a very long transcript
         * bounded too; lazy translation is not useful if preparation first materializes the whole
         * track a second time.
         */
        suspend fun create(
            directory: File,
            writeBatches: suspend (append: suspend (List<SubtitleSegment>) -> Unit) -> Unit,
        ): SubtitleStore {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle storage." }
            val file = File.createTempFile("transcript-", ".bin", directory)
            try {
                val starts = mutableListOf<Long>()
                val offsets = mutableListOf<Long>()
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
                    writeBatches { batch ->
                        require(starts.size + batch.size <= MAX_SEGMENTS) {
                            "Caption track contains too many entries."
                        }
                        batch.forEach { sourceSegment ->
                            currentCoroutineContext().ensureActive()
                            val segment = sourceSegment
                            // Overlapping YouTube ASR cues can make a split chunk start after the
                            // following cue. Keep the display order, but clamp only the lookup key
                            // so binary search remains valid instead of crashing with an unnamed
                            // IllegalArgumentException ("Failed requirement").
                            starts += maxOf(segment.startMs, starts.lastOrNull() ?: Long.MIN_VALUE)
                            offsets += output.size().toLong()
                            output.writeSegment(segment)
                            check(output.size().toLong() <= MAX_STORE_BYTES) {
                                "Caption storage limit exceeded."
                            }
                        }
                    }
                }
                return SubtitleStore(file, starts.toLongArray(), offsets.toLongArray())
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }
}

private fun DataOutput.writeSegment(segment: SubtitleSegment) {
    writeLong(segment.id)
    writeLong(segment.startMs)
    writeLong(segment.endMs)
    writeText(segment.originalText)
    require(segment.words.size <= 16_384) { "A caption contains too many timed words." }
    writeInt(segment.words.size)
    segment.words.forEach { word ->
        writeText(word.text)
        writeLong(word.startMs)
        writeLong(word.endMs)
    }
    val sentence = segment.sentence
    writeBoolean(sentence != null)
    if (sentence != null) {
        writeText(sentence.text)
        require(sentence.cuts.size <= 16_384) { "A caption sentence has too many rows." }
        writeInt(sentence.cuts.size)
        sentence.cuts.forEach(::writeInt)
        writeInt(sentence.index)
    }
}

private fun DataInput.readSegment(): SubtitleSegment {
    val id = readLong()
    val start = readLong()
    val end = readLong()
    val text = readText()
    val count = readInt()
    require(count in 0..16_384) { "Stored caption word count is invalid." }
    val words = List(count) { SubtitleWord(readText(), readLong(), readLong()) }
    val sentence = if (readBoolean()) readSentence() else null
    return SubtitleSegment(id, start, end, text, words = words, sentence = sentence)
}

private fun DataInput.readSentence(): SentenceSlice {
    val text = readText()
    val count = readInt()
    require(count in 0..16_384) { "Stored caption sentence is invalid." }
    return SentenceSlice(text, List(count) { readInt() }, readInt())
}

private fun DataOutput.writeText(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 1024 * 1024) { "A caption text entry is larger than 1 MiB." }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInput.readText(): String {
    val count = readInt()
    require(count in 0..1024 * 1024) { "Stored caption text length is invalid." }
    val bytes = ByteArray(count)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}
