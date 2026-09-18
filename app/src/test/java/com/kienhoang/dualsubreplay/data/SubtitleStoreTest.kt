package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files

class SubtitleStoreTest {
    @Test
    fun denseCaptionsHaveAHardEntryCapAndIncludePlaybackAfterLargeSeeks() =
        runBlocking {
            val rows = List(100_000) { SubtitleSegment(it.toLong(), it * 10L, (it + 1) * 10L, "行 $it") }
            withStore(rows) { store ->
                listOf(0L, 500_000L, 999_990L).forEach { time ->
                    val window = store.read(store.windowIndices(time))
                    check(window.size <= MAX_WINDOW_SEGMENTS)
                    check(window.any { time >= it.startMs && time < it.endMs })
                    check(window.map { it.id }.distinct().size == window.size)
                }
            }
        }

    @Test
    fun diskRoundTripPreservesWordsIdsAndUnicode() =
        runBlocking {
            val rows = listOf(SubtitleSegment(42, 100, 900, "皆を守る 🛡️", words = listOf(SubtitleWord("皆", 100, 250))))
            withStore(rows) { store -> check(store.read(0..0) == rows) }
        }

    @Test
    fun emptyTrackAndSilenceAreSafe() =
        runBlocking {
            withStore(emptyList()) { store -> check(store.read(store.windowIndices(0)).isEmpty()) }
            val rows = listOf(SubtitleSegment(0, 100_000, 101_000, "Later"))
            withStore(rows) { store -> check(store.read(store.windowIndices(0)) == rows) }
        }

    @Test
    fun closingAStoreDeletesItsTranscriptFile() =
        runBlocking {
            val directory = Files.createTempDirectory("subtitle-close-test").toFile()
            try {
                val store = SubtitleStore.create(directory, listOf(SubtitleSegment(0, 0, 10, "Hi")))
                check(directory.listFiles()!!.size == 1)
                store.close()
                check(directory.listFiles()!!.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun withStore(
        rows: List<SubtitleSegment>,
        block: suspend (SubtitleStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("subtitle-store-test").toFile()
        try {
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
