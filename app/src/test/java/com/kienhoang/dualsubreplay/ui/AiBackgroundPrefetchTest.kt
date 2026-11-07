package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owner report 2026-11-07: AI translation ran into Gemini's free limits even with two keys. While the
 * video played, every background step sent a request for the one or two sentences that had just
 * crossed the five-minute mark, so free keys (which count requests, not their size) ran out.
 */
class AiBackgroundPrefetchTest {
    // A sentence every 3 s for 30 minutes.
    private val rows = List(600) { SubtitleSegment(it.toLong(), it * 3_000L, (it + 1) * 3_000L, "Line number $it is spoken here.") }

    /** Answers every sentence of a request, and counts the requests. */
    private class CountingModel : AiTranslationModel {
        override val scope = "fake/model"
        val requests = AtomicInteger()

        override suspend fun ask(
            system: String,
            user: String,
            onText: (String) -> Unit,
        ): String {
            requests.incrementAndGet()
            val sentences = JSONObject(user).getJSONArray("sentences")
            val answers = JSONArray()
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                val rows = sentence.getJSONArray("rows")
                answers.put(
                    JSONObject()
                        .put(
                            "id",
                            sentence.getInt("id"),
                        ).put("rows", JSONArray(List(rows.length()) { "Dòng ${rows.getString(it)}" })),
                )
            }
            return JSONObject().put("sentences", answers).toString().also(onText)
        }
    }

    @Test
    fun playingThirtyMinutesSendsFullRequestsNotOnePerNewSentence() =
        runBlocking {
            val directory = Files.createTempDirectory("ai-background-prefetch-test").toFile()
            try {
                SubtitleStore.create(directory, rows).use { store ->
                    val translator = AiSubtitleTranslator(null)
                    val model = CountingModel()
                    val requests = MutableStateFlow(CaptionPlaybackRequest(0, paused = false, enabled = true))
                    val job =
                        launch {
                            prefetchInBackground(store, requests, pauseMs = { 1 }, restMs = 1) { texts ->
                                translator.translateNextBatch(model, "en", "vi", texts)
                            }
                        }
                    try {
                        // The playing window's first request, as when the video opens: minute 1, filled up to 60 sentences.
                        translator.translateAll(
                            model,
                            "en",
                            "vi",
                            translationTexts(rows.take(20)),
                        ) { translationTexts(rows.subList(20, 100)) }
                        // Playback moves 3 s at a time, a row each step, as the background loop sees it.
                        for (timeMs in 0L..(25 * 60_000L) step 3_000L) {
                            requests.value = requests.value.copy(timeMs = timeMs)
                            delay(4)
                            // The minute after the playing time is already translated, so rows never wait.
                            val next = rows.last { it.startMs <= timeMs + PREFETCH_AHEAD_MS }
                            assertNotNull(
                                "No AI line for ${next.originalText} at $timeMs ms",
                                translator.cachedTranslation(model, "en", "vi", next.originalText),
                            )
                        }
                    } finally {
                        job.cancelAndJoin()
                    }
                    // About 500 sentences reach the five-minute mark in 25 minutes: 60 to a request is
                    // 9 requests; a request each time one crosses it was about 500.
                    assertTrue("Sent ${model.requests.get()} requests", model.requests.get() <= 16)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
}
