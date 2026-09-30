package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Original captions keep following playback whatever happens to translation. */
class TranslationFailureTest {
    private val rows = List(200) { SubtitleSegment(it.toLong(), it * 3400L, (it + 1) * 3400L, "Caption $it") }

    private class Publication(
        val rows: List<SubtitleSegment>,
        val preparing: Boolean,
    )

    @Test
    fun failureBeforeTheFirstTranslationKeepsFollowingOriginalCaptions() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val publications = MutableStateFlow<Publication?>(null)
                val failures = mutableListOf<Exception>()
                var calls = 0
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            calls++
                            throw IllegalStateException("The translation model download took too long.")
                        }, { failures += it }) { snapshot, preparing -> publications.value = Publication(snapshot, preparing) }
                    }
                try {
                    withTimeout(5000) { publications.first { it != null && failures.isNotEmpty() && !it.preparing } }
                    assertEquals("The translation model download took too long.", failures.single().message)

                    // Playback keeps moving and a seek jumps five minutes ahead: originals follow, no retry loop.
                    requests.value = requests.value.copy(timeMs = 1000)
                    requests.value = requests.value.copy(timeMs = 300_000, seekGeneration = 1)
                    val afterSeek =
                        withTimeout(5000) {
                            publications.first {
                                it != null &&
                                    it.rows.any { row -> row.startMs <= 300_000 && row.endMs > 300_000 }
                            }
                        }
                    assertTrue(afterSeek!!.rows.none { it.translatedText != null })
                    assertTrue(!afterSeek.preparing)
                    assertEquals(1, calls)
                    assertEquals(1, failures.size)
                    assertTrue(job.isActive)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun failureMidWindowKeepsTheRowsAlreadyTranslated() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val publications = MutableStateFlow<Publication?>(null)
                val failures = mutableListOf<Exception>()
                var calls = 0
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            if (++calls > 1) throw IllegalStateException("Translation failed")
                            "translated: $text"
                        }, { failures += it }) { snapshot, preparing -> publications.value = Publication(snapshot, preparing) }
                    }
                try {
                    val last = withTimeout(5000) { publications.first { it != null && failures.isNotEmpty() && !it.preparing } }!!
                    assertEquals("translated: Caption 0", last.rows.first().translatedText)
                    assertTrue(last.rows.drop(1).all { it.translatedText == null })
                    assertEquals(2, calls)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun retryTranslatesAgainWithoutReloadingCaptions() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val publications = MutableStateFlow<Publication?>(null)
                val failures = mutableListOf<Exception>()
                var online = false
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            if (!online) throw IllegalStateException("offline")
                            "translated: $text"
                        }, { failures += it }) { snapshot, preparing -> publications.value = Publication(snapshot, preparing) }
                    }
                try {
                    withTimeout(5000) { publications.first { it != null && failures.isNotEmpty() && !it.preparing } }
                    online = true
                    requests.value = requests.value.copy(translationAttempt = 1)
                    val ready =
                        withTimeout(5000) {
                            publications.first {
                                it != null && !it.preparing &&
                                    it.rows.first().translatedText != null
                            }
                        }!!
                    assertEquals("translated: Caption 0", ready.rows.first().translatedText)
                    assertEquals(1, failures.size)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun failedSentencePrefixStillSplitsTheWholeSentenceTranslation() =
        runBlocking {
            val sentence = "The French Revolution temporarily stalled relocation efforts."
            val display = captionDisplaySegments(listOf(SubtitleSegment(0, 0, 4000, sentence)), CaptionFormat.SHORT_PHRASES, natural = true)
            check(display.size == 2)
            val translation = "Cuộc Cách mạng Pháp tạm thời làm đình trệ các nỗ lực di dời."
            withStore(display) { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val ready = CompletableDeferred<List<SubtitleSegment>>()
                val failures = mutableListOf<Exception>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, { text ->
                            if (text == sentence) translation else throw IllegalStateException("prefix failed")
                        }, { failures += it }) { snapshot, preparing -> if (!preparing) ready.complete(snapshot) }
                    }
                try {
                    val output = withTimeout(5000) { ready.await() }
                    assertTrue(output.all { !it.translatedText.isNullOrBlank() })
                    assertEquals(translation, output.joinToString(" ") { it.translatedText.orEmpty() })
                    assertTrue(failures.isEmpty())
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun cancellingTheLoopMidTranslationIsNotAFailure() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val started = CompletableDeferred<Unit>()
                val failures = mutableListOf<Exception>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            started.complete(Unit)
                            awaitCancellation()
                        }, { failures += it }) { _, _ -> }
                    }
                withTimeout(5000) { started.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(failures.isEmpty())
            }
        }

    @Test
    fun aCancelledTranslatorTaskIsAFailureWhileTheLoopKeepsRunning() =
        runBlocking {
            withStore { store ->
                val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                val publications = MutableStateFlow<Publication?>(null)
                val failures = mutableListOf<Exception>()
                val job =
                    launch {
                        translatePlaybackWindow(store, requests, {
                            throw CancellationException("ML Kit task was cancelled")
                        }, { failures += it }) { snapshot, preparing -> publications.value = Publication(snapshot, preparing) }
                    }
                try {
                    withTimeout(5000) { publications.first { it != null && failures.isNotEmpty() && !it.preparing } }
                    assertTrue(job.isActive)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun rowsShowWhyTheirTranslationIsMissing() {
        assertEquals("Translating…", pendingTranslationText(downloadingModel = false, translationUnavailable = false))
        assertEquals("Downloading translation model…", pendingTranslationText(downloadingModel = true, translationUnavailable = false))
        assertEquals(TRANSLATION_UNAVAILABLE_TEXT, pendingTranslationText(downloadingModel = true, translationUnavailable = true))
    }

    @Test
    fun failureMessagesStayShortAndReadable() {
        assertEquals("Model missing.", translationFailureMessage(IllegalStateException(" Model missing. ")))
        val fallback = translationFailureMessage(IllegalStateException(""))
        assertTrue(fallback.startsWith("Translation is unavailable"))
        assertEquals(fallback, translationFailureMessage(IllegalStateException("x".repeat(500))))
    }

    @Test
    fun retryReloadsCaptionsUnlessOnlyTranslationFailed() {
        val showing =
            DualSubUiState(
                activeVideoId = "video",
                segments = listOf(SubtitleSegment(0, 0, 1000, "Hello")),
                translationError = "offline",
            )
        assertTrue(onlyTranslationFailed(showing))
        assertTrue(!onlyTranslationFailed(showing.copy(translationError = null)))
        assertTrue(!onlyTranslationFailed(showing.copy(errorMessage = "Captions failed")))
        assertTrue(!onlyTranslationFailed(showing.copy(liveFallback = true)))
        assertTrue(!onlyTranslationFailed(showing.copy(segments = emptyList())))
    }

    private suspend fun withStore(
        segments: List<SubtitleSegment> = rows,
        block: suspend (SubtitleStore) -> Unit,
    ) {
        val directory = Files.createTempDirectory("translation-failure-test").toFile()
        try {
            SubtitleStore.create(directory, segments).use { block(it) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
