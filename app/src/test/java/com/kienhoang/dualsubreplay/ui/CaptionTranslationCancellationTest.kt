package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.MAX_WINDOW_SEGMENTS
import com.kienhoang.dualsubreplay.data.SUBTITLE_LOOK_BEHIND_MS
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
                    check(output.first().startMs >= position - SUBTITLE_LOOK_BEHIND_MS - 4_000)
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
                                check(snapshot.all { it.startMs > target - SUBTITLE_LOOK_BEHIND_MS - 4_000 })
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
    fun pausedVideoTranslatesTheNextMinuteAndThenSleeps() =
        runBlocking {
            withStore { store ->
                val position = 60 * 60 * 1000L
                val requests = MutableStateFlow(CaptionPlaybackRequest(position, paused = true, enabled = true))
                val inputs = mutableListOf<String>()
                val ready = CompletableDeferred<List<SubtitleSegment>>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            inputs.add(text)
                            "translated: $text"
                        }) { snapshot, preparing -> if (!preparing) ready.complete(snapshot) }
                    }
                try {
                    val output = withTimeout(5000) { ready.await() }
                    check(inputs.first() == "Caption ${position / 3400}")
                    // Every row starting within the next minute is translated before play.
                    check(output.filter { it.startMs in position..position + 60_000 }.all { it.translatedText != null })
                    check(output.last().startMs <= position + 60_000)
                    val sent = inputs.size
                    kotlinx.coroutines.yield()
                    check(inputs.size == sent) // Sleeps until the position changes.
                    check(job.isActive)
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
    fun unknownPlaybackDoesNotStartWorkButAPausedVideoDoes() =
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
                    check(count > 0)
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
        val earlier = SubtitleSegment(1, 0, 2_000, "Earlier", translatedText = "Trước")
        check(
            nextWindowTranslation(
                listOf(earlier) + later.map { it.copy(id = 2) },
                CaptionPlaybackRequest(10_000, paused = false, enabled = true),
            ) ==
                null,
        )
        check(nextWindowTranslation(later, CaptionPlaybackRequest(40_000, paused = false, enabled = true)) == 0)
        check(nextWindowTranslation(later, CaptionPlaybackRequest(40_000, paused = true, enabled = true)) == 0)
        check(nextWindowTranslation(later, CaptionPlaybackRequest(40_000, paused = true, enabled = false)) == null)
    }

    @Test
    fun aFirstLineAfterALongIntroIsTranslatedWhilePaused() {
        // The only row a window holds when the video's first line starts more than a minute in.
        val firstLine = listOf(SubtitleSegment(0, 100_000, 101_000, "But now the sky is open light"))
        check(nextWindowTranslation(firstLine, CaptionPlaybackRequest(24_000, paused = true, enabled = true)) == 0)
        check(
            nextWindowTranslation(
                firstLine.map {
                    it.copy(translatedText = "Nhưng giờ bầu trời")
                },
                CaptionPlaybackRequest(24_000, paused = true, enabled = true),
            ) ==
                null,
        )
    }

    @Test
    fun shortRowsAreTranslatedThroughTheirWholeSentence() =
        runBlocking {
            val sentence = "The French Revolution temporarily stalled relocation efforts."
            val display =
                captionDisplaySegments(
                    listOf(SubtitleSegment(0, 0, 4000, sentence)),
                    CaptionFormat.SHORT_PHRASES,
                    natural = true,
                )
            check(display.map { it.originalText } == listOf("The French Revolution temporarily stalled", "relocation efforts."))
            val dictionary =
                mapOf(
                    sentence to "Cuộc Cách mạng Pháp tạm thời làm đình trệ các nỗ lực di dời.",
                    display[0].originalText to "Cuộc Cách mạng Pháp tạm thời bị đình trệ",
                )
            withStore(display) { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val inputs = mutableListOf<String>()
                val ready = CompletableDeferred<List<SubtitleSegment>>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            inputs.add(text)
                            dictionary.getValue(text)
                        }) { snapshot, preparing -> if (!preparing) ready.complete(snapshot) }
                    }
                try {
                    val output = withTimeout(5000) { ready.await() }
                    check(inputs == listOf(sentence, display[0].originalText)) { inputs }
                    check(output.map { it.translatedText } == listOf("Cuộc Cách mạng Pháp tạm thời làm đình trệ", "các nỗ lực di dời.")) {
                        output.map { it.translatedText }
                    }
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun aPausedVideoBeforeALongIntroEndsShowsItsFirstLineTranslated() =
        runBlocking {
            // AI subtitles of a song playlist: the first line comes 100 s in, the video is paused at 0:24.
            val song = List(30) { SubtitleSegment(it.toLong(), 100_000 + it * 4000L, 104_000 + it * 4000L, "Line $it") }
            withStore(song) { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(24_000, paused = true, enabled = true))
                val shown = MutableStateFlow<List<SubtitleSegment>>(emptyList())
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text -> "Dịch $text" }) { window, _ -> shown.value = window }
                    }
                try {
                    val window = withTimeout(5000) { shown.first { it.isNotEmpty() && it.all { row -> row.translatedText != null } } }
                    check(window.map { it.translatedText } == listOf("Dịch Line 0")) { window.toString() }
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    private suspend fun withStore(
        segments: List<SubtitleSegment> = rows,
        block: suspend (SubtitleStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("playback-window-test").toFile()
        try {
            SubtitleStore.create(directory, segments).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
