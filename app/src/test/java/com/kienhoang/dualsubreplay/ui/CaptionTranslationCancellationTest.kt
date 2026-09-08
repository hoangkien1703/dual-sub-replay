package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class CaptionTranslationCancellationTest {
    private val rows = listOf(SubtitleSegment(0, 0, 1000, "I gave up"), SubtitleSegment(1, 1000, 2000, "because it was raining."))

    @Test fun eachPhraseReceivesOnlyItsOwnTranslationStartingNearPlayback() =
        runBlocking {
            val inputs = mutableListOf<String>()
            var output = emptyList<SubtitleSegment>()
            translateDisplayCaptions(rows, { 1500 }, { text ->
                inputs.add(text)
                "translated: $text"
            }) { snapshot, _, _ -> output = snapshot }
            assertEquals(rows.reversed().map { it.originalText }, inputs)
            assertEquals(rows.map { "translated: ${it.originalText}" }, output.map { it.translatedText })
        }

    @Test fun supersededTranslationCannotPublishEvenIfProviderCompletesAfterCancellation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published = false
            val job =
                launch {
                    translateDisplayCaptions(rows, { 0 }, {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        "obsolete translation"
                    }) { _, _, _ -> published = true }
                }
            started.await()
            job.cancel()
            release.complete(Unit)
            job.join()
            assertFalse(published)
        }
}
