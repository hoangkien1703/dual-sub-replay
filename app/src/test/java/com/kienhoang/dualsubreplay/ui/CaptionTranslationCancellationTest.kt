package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.MAX_WINDOW_SEGMENTS
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.file.Files

class CaptionTranslationCancellationTest {
    private val rows = List(23_655) { SubtitleSegment(it.toLong(), it * 3400L, (it + 1) * 3400L, "Caption $it") }

    @Test
    fun twentyTwoHourVideoTranslatesOnlyTheCurrentWindowAndThenSleeps() =
        runBlocking {
            withStore { store ->
                val position = 10 * 60 * 60 * 1000L
                val requests = MutableStateFlow(CaptionPlaybackRequest(position, paused = false, enabled = true))
                val inputs = mutableListOf<String>()
                val ready = CompletableDeferred<List<SubtitleSegment>>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            inputs.add(text)
                            "translated: $text"
                        }) { snapshot, preparing ->
                            check(snapshot.size <= MAX_WINDOW_SEGMENTS)
                            if (!preparing) ready.complete(snapshot)
                        }
                    }
                try {
                    val output = withTimeout(5000) { ready.await() }
                    check(inputs.first() == "Caption ${position / 3400}")
                    check(inputs.size in 1..MAX_WINDOW_SEGMENTS)
                    check(output.all { it.translatedText != null })
                    check(output.first().startMs >= position - 34_000)
                    check(output.last().startMs <= position + 60_000)
                    check(job.isActive) // Waiting for playback, not draining the rest of the transcript.
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun seekDropsTheOldQueueAndNeverPublishesItsLateResult() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                val inputs = mutableListOf<String>()
                val target = 12 * 60 * 60 * 1000L
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            inputs.add(text)
                            if (inputs.size == 1) {
                                started.complete(Unit)
                                release.await()
                            }
                            text
                        }) { snapshot, preparing ->
                            if (requests.value.seekGeneration == 1L) {
                                check(snapshot.all { it.startMs > target - 34_000 })
                                if (!preparing) ready.complete(Unit)
                            }
                        }
                    }
                try {
                    withTimeout(5000) { started.await() }
                    requests.value = CaptionPlaybackRequest(target, paused = false, enabled = true, seekGeneration = 1)
                    release.complete(Unit)
                    withTimeout(5000) { ready.await() }
                    check(inputs[1] == "Caption ${target / 3400}")
                    check(inputs.drop(1).none { it == "Caption 1" })
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun pauseFinishesOnlyTheInFlightEntryAndResumeContinues() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val paused = CompletableDeferred<Unit>()
                val resumed = CompletableDeferred<Unit>()
                var count = 0
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            count++
                            if (count == 1) {
                                started.complete(Unit)
                                release.await()
                            }
                            it
                        }) { _, preparing ->
                            if (!preparing && requests.value.paused) paused.complete(Unit)
                            if (!preparing && !requests.value.paused) resumed.complete(Unit)
                        }
                    }
                try {
                    withTimeout(5000) { started.await() }
                    requests.value = requests.value.copy(paused = true)
                    release.complete(Unit)
                    withTimeout(5000) { paused.await() }
                    check(count == 1)
                    requests.value = requests.value.copy(paused = false)
                    withTimeout(5000) { resumed.await() }
                    check(count > 1)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun supersededSessionCannotPublishEvenIfProviderCompletesAfterCancellation() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var translatedPublication = false
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            started.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                            "obsolete translation"
                        }) { snapshot, _ -> translatedPublication = snapshot.any { it.translatedText != null } }
                    }
                withTimeout(5000) { started.await() }
                job.cancel()
                release.complete(Unit)
                job.join()
                check(!translatedPublication)
            }
        }

    @Test
    fun unknownPlaybackAndBackgroundDoNotStartWork() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest())
                var count = 0
                val ready = CompletableDeferred<Unit>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            count++
                            it
                        }) { _, preparing ->
                            if (!preparing) ready.complete(Unit)
                        }
                    }
                try {
                    kotlinx.coroutines.yield()
                    check(count == 0)
                    requests.value = CaptionPlaybackRequest(0, paused = true, enabled = true)
                    withTimeout(5000) { ready.await() }
                    check(count == 0)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun backgroundingFinishesAtMostOneEntryAndDoesNotPublishUntilForeground() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                var count = 0
                var publications = 0
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            count++
                            if (count == 1) {
                                started.complete(Unit)
                                release.await()
                            }
                            it
                        }) { _, preparing ->
                            publications++
                            if (!preparing) ready.complete(Unit)
                        }
                    }
                try {
                    withTimeout(5000) { started.await() }
                    val before = publications
                    requests.value = requests.value.copy(enabled = false)
                    release.complete(Unit)
                    withTimeout(5000) { requests.subscriptionCount.first { it > 0 } }
                    check(count == 1)
                    check(publications == before)
                    requests.value = requests.value.copy(enabled = true)
                    withTimeout(5000) { ready.await() }
                    check(count > 1)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun longSilencesDoNotTriggerTranslationOutsideTheTimeWindow() {
        val later = listOf(SubtitleSegment(0, 100_000, 101_000, "Later"))
        check(nextWindowTranslation(later, CaptionPlaybackRequest(0, paused = false, enabled = true)) == null)
        check(nextWindowTranslation(later, CaptionPlaybackRequest(40_000, paused = false, enabled = true)) == 0)
    }

    private suspend fun withStore(block: suspend (SubtitleStore) -> Unit) {
        val directory = Files.createTempDirectory("playback-window-test").toFile()
        try {
            SubtitleStore.create(directory, rows).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
