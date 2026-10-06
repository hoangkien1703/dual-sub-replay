package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleWebTranslatorTest {
    @Test fun joinsEverySentenceSegment() {
        val reply =
            """[[["Hello. ","こんにちは。",null,null,10],["How are you?","元気ですか？",null,null,10]],null,"ja",null,null,null,null,[]]"""
        assertEquals("Hello. How are you?", parseGoogleTranslation(reply))
    }

    @Test fun ignoresSegmentsWithoutTranslatedText() {
        val reply = """[[["Hi.","やあ。",null,null,1],[null,null,"Yaa."]],null,"ja"]"""
        assertEquals("Hi.", parseGoogleTranslation(reply))
    }

    @Test fun rejectsPagesEmptyAndMalformedReplies() {
        val sorry = "<html><title>Sorry...</title><p>automated queries</p></html>"
        assertThrows(GoogleTranslateException::class.java) { parseGoogleTranslation(sorry) }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleTranslation("[null]") }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleTranslation("""[[["  ","x"]]]""") }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleTranslation("[[[") }
    }

    @Test fun mapsAppLanguageCodesToGoogleCodes() {
        assertEquals("zh-CN", googleLanguageCode("zh"))
        assertEquals("zh-CN", googleLanguageCode("zh-TW"))
        assertEquals("he", googleLanguageCode("iw"))
        assertEquals("ja", googleLanguageCode("ja"))
        assertEquals("auto", googleLanguageCode("auto"))
    }

    @Test fun readsBatchRepliesInEitherShape() {
        assertEquals(listOf("Hello", "Goodbye"), parseGoogleBatchTranslation("""["Hello"," Goodbye "]""", 2))
        // With a detected source language each entry is [translation, language].
        assertEquals(listOf("Hello"), parseGoogleBatchTranslation("""[["Hello","ja"]]""", 1))
    }

    @Test fun rejectsBatchRepliesThatDoNotMatchTheRequest() {
        assertThrows(GoogleTranslateException::class.java) { parseGoogleBatchTranslation("""["Hello"]""", 2) }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleBatchTranslation("""["Hello",""]""", 2) }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleBatchTranslation("""["Hello",null]""", 2) }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleBatchTranslation("<html>Sorry</html>", 1) }
        assertThrows(GoogleTranslateException::class.java) { parseGoogleBatchTranslation("[\"x\"", 1) }
    }

    @Test fun batchesRespectTextAndCharacterLimits() {
        assertEquals(listOf(listOf("a", "b"), listOf("c")), googleBatches(listOf("a", "b", "c"), maxTexts = 2, maxChars = 100))
        assertEquals(listOf(listOf("aaa"), listOf("bbb", "c")), googleBatches(listOf("aaa", "bbb", "c"), maxTexts = 10, maxChars = 4))
        // A text longer than the limit still goes, alone.
        assertEquals(listOf(listOf("long text"), listOf("b")), googleBatches(listOf("long text", "b"), maxTexts = 10, maxChars = 4))
        assertEquals(emptyList<List<String>>(), googleBatches(emptyList()))
    }

    @Test fun onlyForbiddenAndThrottledRepliesTryAnotherEndpoint() {
        assertTrue(isBlockedGoogleStatus(429))
        assertTrue(isBlockedGoogleStatus(403))
        assertFalse(isBlockedGoogleStatus(500))
        assertFalse(isBlockedGoogleStatus(400))
    }

    @Test fun endpointOrderStartsWithTheLastWorkingEndpoint() {
        assertEquals(listOf(0, 1, 2), googleClientOrder(preferred = 0, count = 3))
        assertEquals(listOf(2, 0, 1), googleClientOrder(preferred = 2, count = 3))
        assertEquals(listOf(0, 1, 2), googleClientOrder(preferred = 7, count = 3).sorted())
    }

    @Test fun requestsUseHttpsGoogleHostsAndLanguages() {
        val batch = googleTranslateUrl(GoogleEndpoint.BATCH, "ja", "en")
        assertEquals("https", batch.scheme)
        assertEquals("translate.googleapis.com", batch.host)
        assertEquals("/translate_a/t", batch.encodedPath)
        assertEquals("gtx", batch.queryParameter("client"))
        assertEquals("ja", batch.queryParameter("sl"))
        assertEquals("en", batch.queryParameter("tl"))
        assertEquals(null, batch.queryParameter("dt"))
        assertEquals("clients5.google.com", googleTranslateUrl(GoogleEndpoint.EXTENSION_BATCH, "ja", "en").host)
        val single = googleTranslateUrl(GoogleEndpoint.SINGLE, "ja", "en")
        assertEquals("at", single.queryParameter("client"))
        assertEquals("t", single.queryParameter("dt"))
        GoogleEndpoint.entries.forEach { assertTrue(it.url.startsWith("https://")) }
    }

    @Test fun manyTextsGoInOneRequestAndAreCached() =
        runBlocking {
            val requests = mutableListOf<List<String>>()
            val translator =
                GoogleWebTranslator(
                    client =
                        fakeGoogle { _, texts ->
                            requests += texts
                            200 to batchReply(texts.map { "en:$it" })
                        },
                )
            val texts = listOf("一つ", "二つ", "一つ", "  ", "三つ")
            assertEquals(listOf("en:一つ", "en:二つ", "en:一つ", "  ", "en:三つ"), translator.translateAll("ja", "en", texts))
            assertEquals(listOf(listOf("一つ", "二つ", "三つ")), requests)
            // Everything is cached now; only the new text is sent.
            assertEquals("en:二つ", translator.translate("ja", "en", "二つ"))
            assertEquals(listOf("en:三つ", "en:四つ"), translator.translateAll("ja", "en", listOf("三つ", "四つ")))
            assertEquals(listOf(listOf("一つ", "二つ", "三つ"), listOf("四つ")), requests)
        }

    @Test fun refusedEndpointFallsBackAndIsRemembered() =
        runBlocking {
            val seen = mutableListOf<String>()
            val translator =
                GoogleWebTranslator(
                    retryDelaysMs = emptyList(),
                    client =
                        fakeGoogle { endpoint, texts ->
                            seen += "${endpoint.client}:${texts.joinToString()}"
                            when (endpoint) {
                                GoogleEndpoint.SINGLE -> 200 to """[[["There aren't many.","${texts.single()}"]]]"""
                                else -> 429 to ""
                            }
                        },
                )
            assertEquals("There aren't many.", translator.translate("ja", "en", "あんまりいません。"))
            translator.translate("ja", "en", "二つ目")
            assertEquals(
                listOf("gtx:あんまりいません。", "dict-chrome-ex:あんまりいません。", "at:あんまりいません。", "at:二つ目"),
                seen,
            )
        }

    @Test fun shortOutagesAreRetriedBeforeFailing() =
        runBlocking {
            var calls = 0
            val translator =
                GoogleWebTranslator(
                    retryDelaysMs = listOf(0L),
                    client =
                        fakeGoogle { _, texts ->
                            calls++
                            if (calls == 1) 503 to "" else 200 to batchReply(texts.map { "ok" })
                        },
                )
            assertEquals("ok", translator.translate("ja", "en", "テスト"))
            assertEquals(2, calls)
        }

    @Test fun throttlingThatOutlastsTheRetriesFails() =
        runBlocking {
            var calls = 0
            val refused =
                GoogleWebTranslator(
                    retryDelaysMs = listOf(0L, 0L),
                    client =
                        fakeGoogle { _, _ ->
                            calls++
                            429 to ""
                        },
                )
            val refusal = runCatching { refused.translate("ja", "en", "テスト") }.exceptionOrNull()
            assertTrue(refusal is GoogleTranslateException)
            assertTrue(refusal!!.message!!.contains("429"))
            // Every endpoint, three times (the first try and two retries).
            assertEquals(GoogleEndpoint.entries.size * 3, calls)
        }

    @Test fun clientErrorsAreNotRetried() =
        runBlocking {
            var calls = 0
            val broken =
                GoogleWebTranslator(
                    retryDelaysMs = listOf(0L, 0L),
                    client =
                        fakeGoogle { _, _ ->
                            calls++
                            400 to ""
                        },
                )
            val failure = runCatching { broken.translate("ja", "en", "テスト") }.exceptionOrNull()
            assertTrue(failure is GoogleTranslateException)
            assertTrue(failure!!.message!!.contains("400"))
            assertEquals(1, calls)
        }

    @Test fun sameLanguageAndBlankTextNeverCallGoogle() =
        runBlocking {
            val translator = GoogleWebTranslator(client = fakeGoogle { _, _ -> error("no request expected") })
            assertEquals("hello", translator.translate("en", "en", "hello"))
            assertEquals("  ", translator.translate("ja", "en", "  "))
            assertEquals(listOf("a", "b"), translator.translateAll("ja", "ja", listOf("a", "b")))
        }

    private fun batchReply(translations: List<String>): String = JSONArray(translations).toString()

    /** Answers every request locally, so tests never reach Google. */
    private fun fakeGoogle(reply: (endpoint: GoogleEndpoint, texts: List<String>) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val form = request.body as FormBody
                    val texts = (0 until form.size).filter { form.name(it) == "q" }.map(form::value)
                    val endpoint = GoogleEndpoint.entries.single { request.url.toString().startsWith(it.url) }
                    val (code, body) = reply(endpoint, texts)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).build()
}
