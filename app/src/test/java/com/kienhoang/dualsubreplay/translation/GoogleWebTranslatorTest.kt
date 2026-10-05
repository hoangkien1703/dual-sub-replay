package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun onlyForbiddenAndThrottledRepliesTryAnotherClient() {
        assertTrue(isBlockedGoogleStatus(429))
        assertTrue(isBlockedGoogleStatus(403))
        assertFalse(isBlockedGoogleStatus(500))
        assertFalse(isBlockedGoogleStatus(400))
    }

    @Test fun clientOrderStartsWithTheLastWorkingClient() {
        assertEquals(listOf(0, 1), googleClientOrder(preferred = 0, count = 2))
        assertEquals(listOf(1, 0), googleClientOrder(preferred = 1, count = 2))
        assertEquals(listOf(0, 1), googleClientOrder(preferred = 7, count = 2).sorted())
    }

    @Test fun requestUsesHttpsGoogleHostAndLanguages() {
        val url = googleTranslateUrl("at", "ja", "en")
        assertEquals("https", url.scheme)
        assertEquals("translate.googleapis.com", url.host)
        assertEquals("at", url.queryParameter("client"))
        assertEquals("ja", url.queryParameter("sl"))
        assertEquals("en", url.queryParameter("tl"))
        assertEquals("t", url.queryParameter("dt"))
    }

    @Test fun refusedClientFallsBackAndIsRememberedAndResultsAreCached() =
        runBlocking {
            val seen = mutableListOf<String>()
            val translator =
                GoogleWebTranslator(
                    client =
                        fakeGoogle { client, text ->
                            seen += "$client:$text"
                            if (client == "at") 429 to "" else 200 to """[[["There aren't many.","$text"]]]"""
                        },
                )
            assertEquals("There aren't many.", translator.translate("ja", "en", "あんまりいません。"))
            assertEquals("There aren't many.", translator.translate("ja", "en", "あんまりいません。"))
            translator.translate("ja", "en", "二つ目")
            assertEquals(listOf("at:あんまりいません。", "gtx:あんまりいません。", "gtx:二つ目"), seen)
        }

    @Test fun everyClientRefusedOrServerErrorFails() =
        runBlocking {
            val refused = GoogleWebTranslator(client = fakeGoogle { _, _ -> 429 to "" })
            val refusal = runCatching { refused.translate("ja", "en", "テスト") }.exceptionOrNull()
            assertTrue(refusal is GoogleTranslateException)
            assertTrue(refusal!!.message!!.contains("429"))

            val broken = GoogleWebTranslator(client = fakeGoogle { _, _ -> 500 to "" })
            val failure = runCatching { broken.translate("ja", "en", "テスト") }.exceptionOrNull()
            assertTrue(failure is GoogleTranslateException)
            assertTrue(failure!!.message!!.contains("500"))
        }

    @Test fun sameLanguageAndBlankTextNeverCallGoogle() =
        runBlocking {
            val translator = GoogleWebTranslator(client = fakeGoogle { _, _ -> error("no request expected") })
            assertEquals("hello", translator.translate("en", "en", "hello"))
            assertEquals("  ", translator.translate("ja", "en", "  "))
        }

    /** Answers every request locally, so tests never reach Google. */
    private fun fakeGoogle(reply: (client: String, text: String) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val form = request.body as FormBody
                    val text = (0 until form.size).first { form.name(it) == "q" }.let(form::value)
                    val (code, body) = reply(request.url.queryParameter("client").orEmpty(), text)
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
