package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiVideoSubtitlesTest {
    private val answer =
        """{"candidates":[{"content":{"parts":[{"text":""" +
            """"{\"language\":\"es\",\"lines\":[{\"start\":\"00:01.000\",\"end\":\"00:02.000\",\"text\":\"Hola\"}]}"}]},""" +
            """"finishReason":"STOP"}]}"""

    private class Keys(
        private val keys: Map<String, String>,
    ) : AiSubtitleKeys {
        override suspend fun ids() = keys.keys.toList()

        override suspend fun key(id: String) = keys[id]
    }

    /** Answers each request with the next reply for its model, and records which model and key were asked. */
    private class Script(
        private val replies: Map<String, MutableList<Pair<Int, String>>>,
    ) : AiHttpPoster {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun post(request: AiHttpPost): Pair<Int, String> {
            val model =
                request.url.pathSegments
                    .last()
                    .substringBefore(':')
            asked += model to request.headers.getValue("x-goog-api-key")
            return replies.getValue(model).removeAt(0)
        }
    }

    private fun maker(
        poster: AiHttpPoster,
        keys: Map<String, String> = mapOf("a" to "KEY-A", "b" to "KEY-B"),
    ) = AiVideoSubtitleMaker(
        Keys(keys),
        poster,
        AiKeyPacer(intervalMs = 0),
        models = listOf("lite", "flash"),
        busyWaitsMs = listOf(0L),
    )

    private fun error(
        code: Int,
        message: String,
    ) = code to """{"error":{"code":$code,"message":"$message"}}"""

    @Test
    fun aKeyAtItsLimitHandsThePartToTheNextKey() =
        runBlocking {
            val poster = Script(mapOf("lite" to mutableListOf(error(429, "Resource exhausted"), 200 to answer)))
            val result = maker(poster).part("yjsAuMiLc_A", 0, 300_000)
            assertTrue(result is AiPartAnswer.Written)
            assertEquals(listOf("lite" to "KEY-A", "lite" to "KEY-B"), poster.asked)
        }

    @Test
    fun aBusyOrMissingModelHandsThePartToTheNextModel() =
        runBlocking {
            val poster =
                Script(
                    mapOf(
                        "lite" to mutableListOf(error(503, "The model is overloaded"), error(503, "The model is overloaded")),
                        "flash" to mutableListOf(200 to answer),
                    ),
                )
            val result = maker(poster, keys = mapOf("a" to "KEY-A")).part("yjsAuMiLc_A", 0, 300_000)
            assertEquals(
                "Hola",
                (result as AiPartAnswer.Written)
                    .part.cues
                    .single()
                    .text,
            )
            assertEquals(listOf("lite", "lite", "flash"), poster.asked.map { it.first })

            val missing =
                Script(
                    mapOf(
                        "lite" to mutableListOf(error(404, "models/lite is not found")),
                        "flash" to mutableListOf(200 to answer),
                    ),
                )
            assertTrue(maker(missing, keys = mapOf("a" to "KEY-A")).part("yjsAuMiLc_A", 0, 300_000) is AiPartAnswer.Written)
        }

    @Test
    fun aPartAfterTheEndIsNotAnError() =
        runBlocking {
            val poster = Script(mapOf("lite" to mutableListOf(error(400, "Request contains an invalid argument."))))
            assertEquals(AiPartAnswer.PastTheEnd, maker(poster).part("yjsAuMiLc_A", 300_000, 600_000))
        }

    @Test
    fun aVideoGeminiCannotOpenIsNotABadKey() =
        runBlocking {
            val poster = Script(mapOf("lite" to mutableListOf(error(403, "The caller does not have permission"))))
            try {
                maker(poster).part("aaaaaaaaaaa", 0, 300_000)
                fail("expected a problem")
            } catch (problem: AiSubtitleException) {
                assertEquals(AiSubtitleProblem.VIDEO_UNAVAILABLE, problem.problem)
            }
            assertEquals(1, poster.asked.size)
        }

    @Test
    fun noKeyOrOnlyRefusedKeysSayToAddOne() =
        runBlocking {
            try {
                maker(Script(emptyMap()), keys = emptyMap()).part("yjsAuMiLc_A", 0, 300_000)
                fail("expected a problem")
            } catch (problem: AiSubtitleException) {
                assertEquals(AiSubtitleProblem.NO_KEY, problem.problem)
            }
            val refused = Script(mapOf("lite" to mutableListOf(error(400, "API key not valid. Please pass a valid API key."))))
            try {
                maker(refused, keys = mapOf("a" to "KEY-A")).part("yjsAuMiLc_A", 0, 300_000)
                fail("expected a problem")
            } catch (problem: AiSubtitleException) {
                assertEquals(AiSubtitleProblem.NO_KEY, problem.problem)
            }
        }

    @Test
    fun everyModelAtItsLimitSaysSo() =
        runBlocking {
            val poster =
                Script(
                    mapOf(
                        "lite" to mutableListOf(error(429, "quota"), error(429, "quota")),
                        "flash" to mutableListOf(error(429, "quota"), error(429, "quota")),
                    ),
                )
            try {
                maker(poster).part("yjsAuMiLc_A", 0, 300_000)
                fail("expected a problem")
            } catch (problem: AiSubtitleException) {
                assertEquals(AiSubtitleProblem.LIMIT, problem.problem)
            }
            assertEquals(4, poster.asked.size)
        }

    @Test
    fun statusMeanings() {
        assertEquals(StatusMeaning.OK, aiSubtitleStatus(200, null, 0))
        assertEquals(
            StatusMeaning.BAD_KEY,
            aiSubtitleStatus(
                403,
                "Method doesn't allow unregistered callers (callers without established identity). Please use API Key",
                0,
            ),
        )
        assertEquals(StatusMeaning.VIDEO_UNAVAILABLE, aiSubtitleStatus(400, "Unsupported MIME type: text/html; charset=utf-8", 0))
        assertEquals(StatusMeaning.UNKNOWN_MODEL, aiSubtitleStatus(400, "Request contains an invalid argument.", 0))
        assertEquals(StatusMeaning.BUSY, aiSubtitleStatus(503, "This model is currently experiencing high demand.", 0))
    }
}
