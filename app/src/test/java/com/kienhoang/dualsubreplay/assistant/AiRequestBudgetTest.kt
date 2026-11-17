package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.data.AI_SUBTITLE_PART_MS
import com.kienhoang.dualsubreplay.data.AiSubtitleStore
import com.kienhoang.dualsubreplay.translation.AiSubtitleTranslator
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.ui.AiSubtitleRunner
import com.kienhoang.dualsubreplay.ui.LineMeanings
import com.kienhoang.dualsubreplay.ui.MeaningContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections

/**
 * Most free AI keys are Gemini's, which allow 10 to 15 requests a minute per model (owner request
 * 2026-11-15, after free keys ran out on 2026-11-07). Whatever the player asks for, and whatever
 * the service answers, the app must never ask one key for one model more often than this in any
 * minute. The fake service below counts every request the app would send over HTTP, on virtual time,
 * so many minutes of pacing run in milliseconds.
 */
private const val REQUESTS_PER_MINUTE_PER_KEY = 10

private const val MINUTE_MS = 60_000L

/** A request the fake service received: when (virtual milliseconds), with which key, for which model. */
private data class Sent(
    val atMs: Long,
    val key: String,
    val model: String,
)

/** The most requests in any 60 s, for each key and model, with the worst first. */
private fun List<Sent>.busiestMinutes(): List<Pair<Pair<String, String>, Int>> =
    groupBy { it.key to it.model }
        .map { (keyAndModel, requests) ->
            val times = requests.map(Sent::atMs).sorted()
            keyAndModel to times.indices.maxOf { first -> times.count { it >= times[first] && it < times[first] + MINUTE_MS } }
        }.sortedByDescending { it.second }

private fun assertWithinBudget(sent: List<Sent>) {
    assertTrue("The fake service received no request", sent.isNotEmpty())
    sent.busiestMinutes().forEach { (keyAndModel, requests) ->
        val (key, model) = keyAndModel
        assertTrue(
            "Key …${key.takeLast(4)} was asked $requests times in one minute for $model " +
                "(budget $REQUESTS_PER_MINUTE_PER_KEY): ${sent.filter { it.key == key && it.model == model }.map { it.atMs / 1_000 }} s",
            requests <= REQUESTS_PER_MINUTE_PER_KEY,
        )
    }
}

/** Every sentence of a translation request, answered. */
private fun translationAnswer(user: String): String {
    val sentences = JSONObject(user).getJSONArray("sentences")
    val answers = JSONArray()
    for (index in 0 until sentences.length()) {
        val sentence = sentences.getJSONObject(index)
        val rows = sentence.getJSONArray("rows")
        answers.put(JSONObject().put("id", sentence.getInt("id")).put("rows", JSONArray(List(rows.length()) { "Dòng $it" })))
    }
    return JSONObject().put("sentences", answers).toString()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiRequestBudgetTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val gemini =
        AiAssistantSettings(provider = AiProvider.GEMINI, models = mapOf(AiProvider.GEMINI to "gemini-flash-lite-latest"))

    private val keys = List(3) { "AIzaBudgetTestKey000000000000000000000${'A' + it}" }

    /** The chat endpoint subtitle translation uses. [fails] picks the error for the n-th request (1 first), or null to answer. */
    private class FakeChatService(
        private val clock: () -> Long,
        private val fails: (request: Int, key: String) -> AiErrorKind?,
    ) : AiChatTransport {
        val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

        override suspend fun complete(request: AiChatRequest): AiReply {
            sent += Sent(clock(), request.apiKey, request.model)
            fails(sent.size, request.apiKey)?.let { throw AiChatException(it) }
            return AiReply(translationAnswer(request.messages.last().content))
        }
    }

    private fun TestScope.translation(
        keyCount: Int,
        fails: (request: Int, key: String) -> AiErrorKind? = { _, _ -> null },
    ): Triple<AiSubtitleTranslator, AiTranslationModel, FakeChatService> {
        val clock = { testScheduler.currentTime }
        val store = AiKeyStore(MapStorage(), FakeCipher())
        keys.take(keyCount).forEach { store.add(AiProvider.GEMINI, it) }
        val service = FakeChatService(clock, fails)
        val model = aiTranslationModel(gemini, null, store, service, AiKeyPacer(now = clock))!!
        return Triple(AiSubtitleTranslator(null, now = clock), model, service)
    }

    /**
     * The worst a player can ask for: every 3 s a window of 20 sentences nobody has translated yet,
     * as when someone keeps skipping ahead. Normal playback asks far less (AiBackgroundPrefetchTest).
     */
    private suspend fun TestScope.skipAheadFor(
        minutes: Int,
        translator: AiSubtitleTranslator,
        model: AiTranslationModel,
    ) {
        var next = 0
        val end = currentTime + minutes * MINUTE_MS
        while (currentTime < end) {
            val texts = List(20) { "Sentence ${next + it} is spoken here." }
            next += texts.size
            try {
                translator.translateAll(model, "en", "vi", texts)
            } catch (_: IOException) {
                // Google translates while the AI fails; the player carries on.
            }
            delay(3_000)
        }
    }

    @Test
    fun oneFreeKeyIsAskedAtMostTenTimesAMinute() =
        runTest {
            val (translator, model, service) = translation(keyCount = 1)
            skipAheadFor(minutes = 10, translator, model)
            assertWithinBudget(service.sent)
            // The pacer should not starve the player either: about one request every 6 s.
            assertTrue("Only ${service.sent.size} requests in 10 minutes", service.sent.size >= 90)
        }

    @Test
    fun severalKeysTakeTurnsAndEachStaysWithinItsBudget() =
        runTest {
            val (translator, model, service) = translation(keyCount = 3)
            skipAheadFor(minutes = 10, translator, model)
            assertWithinBudget(service.sent)
            assertEquals(keys.toSet(), service.sent.map(Sent::key).toSet())
        }

    @Test
    fun aBusyServiceIsNotAskedAgainAndAgain() =
        runTest {
            val (translator, model, service) = translation(keyCount = 1) { _, _ -> AiErrorKind.SERVER }
            skipAheadFor(minutes = 10, translator, model)
            assertWithinBudget(service.sent)
        }

    @Test
    fun aKeyAtItsLimitRestsInsteadOfBeingAskedAgain() =
        runTest {
            val (translator, model, service) = translation(keyCount = 1) { _, _ -> AiErrorKind.RATE_LIMITED }
            skipAheadFor(minutes = 30, translator, model)
            assertWithinBudget(service.sent)
            // Rests of 1, 2, 4, 8 and 16 minutes: a handful of requests in half an hour, not hundreds.
            assertTrue("A limited key was asked ${service.sent.size} times in 30 minutes", service.sent.size <= 8)
        }

    @Test
    fun aRefusedJsonSchemaOrThinkingSettingCostsOnlyTheRetry() =
        runTest {
            val (translator, model, service) = translation(keyCount = 1) { request, _ -> AiErrorKind.BAD_REQUEST.takeIf { request <= 2 } }
            skipAheadFor(minutes = 5, translator, model)
            assertWithinBudget(service.sent)
        }

    @Test
    fun aRejectedKeyIsLeftAloneWhileTheOthersTranslate() =
        runTest {
            val (translator, model, service) = translation(keyCount = 2) { _, key -> AiErrorKind.INVALID_KEY.takeIf { key == keys[0] } }
            skipAheadFor(minutes = 10, translator, model)
            assertWithinBudget(service.sent)
            assertEquals(1, service.sent.count { it.key == keys[0] })
        }

    /**
     * Prepare meanings ahead (off by default) asks about every line that starts playing. A line
     * starts every 2 s for 10 minutes, faster than the key may be asked. The fake request takes its
     * turn from the key's pacer like the real one (whose disk and network steps leave virtual time).
     * Only the newest line waits while a request runs, so the key stays within its budget, and no
     * queue is left behind when playing stops.
     */
    @Test
    fun preparingMeaningsAheadWhilePlayingStaysWithinTheBudget() =
        runTest {
            val clock = { testScheduler.currentTime }
            val pacer = AiKeyPacer(now = clock)
            val sent = Collections.synchronizedList(mutableListOf<Sent>())
            val meanings = HashMap<String, String>()
            val lines =
                LineMeanings(
                    scope = this,
                    cached = meanings::get,
                    store = { key, meaning -> meanings[key] = meaning },
                    words = { sentence, _ -> sentence.split(' ') },
                    ask = { _, _, _ ->
                        val key = pacer.take(listOf("gemini/${keys[0]}"))!!
                        sent += Sent(clock(), keys[0], "gemini-flash-lite-latest")
                        delay(1_500)
                        pacer.answered(key)
                        "1. một\n2. hai\n3. ba"
                    },
                )
            val end = currentTime + 10 * MINUTE_MS
            var line = 0
            while (currentTime < end) {
                lines.prepare(MeaningContext("Line ${line++} plays now"), "en", "vi")
                delay(2_000)
            }
            val whilePlaying = sent.size
            testScheduler.advanceUntilIdle()
            assertWithinBudget(sent)
            assertTrue("Only $whilePlaying requests in 10 minutes", whilePlaying >= 90)
            assertTrue("${sent.size - whilePlaying} requests were still queued", sent.size - whilePlaying <= 1)
        }

    /**
     * Words the learner taps go ahead of Prepare meanings ahead (lion737, Discussion #81,
     * 2026-11-16), one every 4 s while a line starts every 2 s for 10 minutes. Together they still
     * stay within the key's budget.
     */
    @Test
    fun tappedWordsWhilePreparingAheadStayWithinTheBudget() =
        runTest {
            val clock = { testScheduler.currentTime }
            val pacer = AiKeyPacer(now = clock)
            val sent = Collections.synchronizedList(mutableListOf<Sent>())
            val key = "gemini/${keys[0]}"
            val meanings = HashMap<String, String>()
            val lines =
                LineMeanings(
                    scope = this,
                    cached = meanings::get,
                    store = { key, meaning -> meanings[key] = meaning },
                    words = { sentence, _ -> sentence.split(' ') },
                    ask = { _, _, urgent ->
                        pacer.take(listOf(key), urgent)!!
                        sent += Sent(clock(), keys[0], "gemini-flash-lite-latest")
                        delay(1_500)
                        "1. một\n2. hai\n3. ba"
                    },
                )
            val end = currentTime + 10 * MINUTE_MS
            var line = 0
            while (currentTime < end) {
                lines.prepare(MeaningContext("Line ${line++} plays now"), "en", "vi")
                if (line % 2 == 0) {
                    launch {
                        pacer.take(listOf(key), urgent = true)
                        sent += Sent(clock(), keys[0], "gemini-flash-lite-latest")
                    }
                }
                delay(2_000)
            }
            testScheduler.advanceUntilIdle()
            assertWithinBudget(sent)
        }

    /** Gemini's generateContent endpoint AI subtitles use; [status] picks the HTTP status of the n-th request (1 first). */
    private class FakeGemini(
        private val clock: () -> Long,
        private val status: (request: Int) -> Int,
    ) : AiHttpPoster {
        val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

        override suspend fun post(request: AiHttpPost): Pair<Int, String> {
            val model =
                request.url.pathSegments
                    .last()
                    .substringBefore(':')
            sent += Sent(clock(), request.headers.getValue("x-goog-api-key"), model)
            val code = status(sent.size)
            return if (code == 200) code to SUBTITLE_ANSWER else code to """{"error":{"code":$code,"message":"Try again later"}}"""
        }
    }

    private class Keys(
        private val keys: List<String>,
    ) : AiSubtitleKeys {
        override suspend fun ids() = keys.indices.map(Int::toString)

        override suspend fun key(id: String) = keys[id.toInt()]
    }

    /** Makes AI subtitles for an hour-long video playing from the start, as the no-captions card does. */
    private fun TestScope.makeSubtitlesForAnHour(
        keyCount: Int,
        status: (request: Int) -> Int,
    ): List<Sent> {
        val clock = { testScheduler.currentTime }
        val gemini = FakeGemini(clock, status)
        val maker = AiVideoSubtitleMaker(Keys(keys.take(keyCount)), gemini, AiKeyPacer(now = clock))
        val runner =
            AiSubtitleRunner(
                scope = this,
                store = AiSubtitleStore(folder.newFolder()),
                askPart = maker::part,
                durationOf = { 12 * AI_SUBTITLE_PART_MS },
                onStatus = {},
                onReady = {},
                io = UnconfinedTestDispatcher(testScheduler),
            )
        runner.start("yjsAuMiLc_A", positionMs = 0)
        testScheduler.advanceUntilIdle()
        return gemini.sent
    }

    @Test
    fun subtitlesForALongVideoStayWithinTheBudget() =
        runTest {
            val sent = makeSubtitlesForAnHour(keyCount = 1) { 200 }
            assertWithinBudget(sent)
            assertEquals(12, sent.size)
        }

    @Test
    fun aBusyGeminiIsNotHammeredWhileSubtitlesAreMade() =
        runTest {
            // The first part arrives, then every model stays overloaded: each part is retried after 30 s, 1 and 2 minutes.
            val sent = makeSubtitlesForAnHour(keyCount = 1) { request -> if (request == 1) 200 else 503 }
            assertWithinBudget(sent)
        }

    @Test
    fun keysAtTheirLimitAreNotHammeredWhileSubtitlesAreMade() =
        runTest {
            val sent = makeSubtitlesForAnHour(keyCount = 2) { request -> if (request == 1) 200 else 429 }
            assertWithinBudget(sent)
        }

    private companion object {
        val SUBTITLE_ANSWER =
            """{"candidates":[{"content":{"parts":[{"text":""" +
                """"{\"language\":\"es\",\"lines\":[{\"start\":\"00:01.000\",\"end\":\"00:02.000\",\"text\":\"Hola\"}]}"}]},""" +
                """"finishReason":"STOP"}]}"""
    }
}
