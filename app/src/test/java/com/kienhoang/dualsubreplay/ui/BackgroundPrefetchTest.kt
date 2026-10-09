package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

/** Minutes 2 to 5 reach the online engine a batch at a time, only while the video plays. */
class BackgroundPrefetchTest {
    // A row every 3 s for 10 minutes.
    private val rows = List(200) { SubtitleSegment(it.toLong(), it * 3_000L, (it + 1) * 3_000L, "Caption $it") }

    @Test
    fun minutesTwoToFiveAreSentOneBatchAtATimeEachTextOnce() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val batches = Collections.synchronizedList(mutableListOf<List<String>>())
                val job =
                    launch {
                        prefetchInBackground(store, requests, pauseMs = 1, restMs = 1) { texts ->
                            texts.take(10).also { if (it.isNotEmpty()) batches += it }
                        }
                    }
                try {
                    // Rows starting after 1 minute, up to 5 minutes: captions 21 to 100.
                    withTimeout(5_000) { while (batches.sumOf { it.size } < 80) delay(5) }
                    delay(50)
                    assertEquals((21..100).map { "Caption $it" }, batches.flatten())
                    assertTrue(batches.all { it.size <= 10 })

                    // Nothing is sent while paused, even after a jump.
                    requests.value = requests.value.copy(paused = true)
                    delay(20)
                    requests.value = requests.value.copy(timeMs = 120_000, seekGeneration = 1)
                    delay(50)
                    assertEquals(80, batches.sumOf { it.size })

                    // Playing again from 2 minutes: rows up to 7 minutes, without resending captions 61 to 100.
                    requests.value = requests.value.copy(paused = false)
                    withTimeout(5_000) { while (batches.sumOf { it.size } < 120) delay(5) }
                    delay(50)
                    assertEquals((21..140).map { "Caption $it" }, batches.flatten())
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aFailedStepRestsAndTriesAgain() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val sent = Collections.synchronizedList(mutableListOf<String>())
                var calls = 0
                val job =
                    launch {
                        prefetchInBackground(store, requests, pauseMs = 1, restMs = 1) { texts ->
                            if (++calls == 1) throw IllegalStateException("Google Translate returned HTTP 503.")
                            texts.also { sent += it }
                        }
                    }
                try {
                    withTimeout(5_000) { while (sent.size < 80) delay(5) }
                    assertEquals((21..100).map { "Caption $it" }, sent.toList())
                    assertTrue(job.isActive)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    private suspend fun withStore(block: suspend (SubtitleStore) -> Unit) {
        val directory = Files.createTempDirectory("background-prefetch-test").toFile()
        try {
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
