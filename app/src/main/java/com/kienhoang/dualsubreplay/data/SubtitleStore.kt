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
        require(indices.first >= 0 && indices.last < size)
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
        ): SubtitleStore {
            require(segments.size <= MAX_SEGMENTS) { "Caption track contains too many entries." }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle storage." }
            val file = File.createTempFile("transcript-", ".bin", directory)
            try {
                val starts = LongArray(segments.size)
                val offsets = LongArray(segments.size)
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
                    segments.forEachIndexed { index, segment ->
                        currentCoroutineContext().ensureActive()
                        require(index == 0 || segment.startMs >= starts[index - 1])
                        starts[index] = segment.startMs
                        offsets[index] = output.size().toLong()
                        output.writeSegment(segment)
                        check(output.size().toLong() <= MAX_STORE_BYTES) { "Caption storage limit exceeded." }
                    }
                }
                return SubtitleStore(file, starts, offsets)
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
    require(segment.words.size <= 16_384)
    writeInt(segment.words.size)
    segment.words.forEach { word ->
        writeText(word.text)
        writeLong(word.startMs)
        writeLong(word.endMs)
    }
}

private fun DataInput.readSegment(): SubtitleSegment {
    val id = readLong()
    val start = readLong()
    val end = readLong()
    val text = readText()
    val count = readInt()
    require(count in 0..16_384)
    val words = List(count) { SubtitleWord(readText(), readLong(), readLong()) }
    return SubtitleSegment(id, start, end, text, words = words)
}

private fun DataOutput.writeText(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 1024 * 1024)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInput.readText(): String {
    val count = readInt()
    require(count in 0..1024 * 1024)
    val bytes = ByteArray(count)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}
