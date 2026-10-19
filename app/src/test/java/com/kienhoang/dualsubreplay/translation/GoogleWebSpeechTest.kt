package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.UnknownHostException

class GoogleWebSpeechTest {
    @Test fun mandarinGetsGooglesVoiceForItsScript() {
        assertEquals("zh-CN", googleSpeechLanguage("zh"))
        assertEquals("zh-CN", googleSpeechLanguage("zh-Hans"))
        assertEquals("zh-CN", googleSpeechLanguage("zh-CN"))
        assertEquals("zh-CN", googleSpeechLanguage("zh_SG"))
        assertEquals("zh-CN", googleSpeechLanguage("cmn-Hans-CN"))
        assertEquals("zh-TW", googleSpeechLanguage("zh-Hant"))
        assertEquals("zh-TW", googleSpeechLanguage("zh-TW"))
    }

    @Test fun otherLanguagesGetGooglesVoiceOfTheirLanguage() {
        assertEquals("ja", googleSpeechLanguage("ja"))
        assertEquals("en", googleSpeechLanguage("en-US"))
        assertEquals("pt", googleSpeechLanguage("pt-BR"))
    }

    @Test fun cantoneseAndUnknownLanguagesStayWithThePhone() {
        assertNull(googleSpeechLanguage("zh-HK"))
        assertNull(googleSpeechLanguage("yue"))
        assertNull(googleSpeechLanguage("auto"))
        assertNull(googleSpeechLanguage(""))
    }

    @Test fun everyLanguageUsesGoogleOnlyWhileGoogleTranslates() {
        assertTrue(speaksWithGoogle("银行", "zh-Hans", TranslationEngine.GOOGLE_WEB))
        assertTrue(speaksWithGoogle("きょう", "ja", TranslationEngine.GOOGLE_WEB))
        assertFalse(speaksWithGoogle("银行", "zh-Hans", TranslationEngine.ON_DEVICE))
        assertFalse(speaksWithGoogle("今日", "ja", TranslationEngine.ON_DEVICE))
        assertFalse(speaksWithGoogle(" ", "zh", TranslationEngine.GOOGLE_WEB))
        assertFalse(speaksWithGoogle("好".repeat(MAX_GOOGLE_SPEECH_CHARS + 1), "zh", TranslationEngine.GOOGLE_WEB))
    }

    @Test fun theUrlAsksForTheWordInTheGivenVoice() {
        val url = googleSpeechUrl(" 银行 ", "zh-CN")
        assertEquals("translate.googleapis.com", url.host)
        assertEquals("/translate_tts", url.encodedPath)
        assertEquals("gtx", url.queryParameter("client"))
        assertEquals("zh-CN", url.queryParameter("tl"))
        assertEquals("银行", url.queryParameter("q"))
        val web = googleSpeechUrl("银行", "zh-CN", GoogleSpeechEndpoint.WEB)
        assertEquals("translate.google.com", web.host)
        assertEquals("tw-ob", web.queryParameter("client"))
        GoogleSpeechEndpoint.entries.forEach { assertTrue(it.url.startsWith("https://")) }
    }

    @Test fun onlyMp3RepliesCount() {
        assertTrue(looksLikeMp3(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4)))
        assertTrue(looksLikeMp3(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64)))
        assertFalse(looksLikeMp3("<html>".toByteArray()))
        assertFalse(looksLikeMp3(ByteArray(0)))
    }

    @Test fun oversizedRepliesAreDropped() {
        val bytes = ByteArray(10) { it.toByte() }
        assertArrayEquals(bytes, readBytesWithLimit(ByteArrayInputStream(bytes), maxBytes = 10))
        assertNull(readBytesWithLimit(ByteArrayInputStream(bytes), maxBytes = 9))
    }

    private val mp3 = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64, 0x00)

    private fun speech(
        health: GoogleSpeechHealth = GoogleSpeechHealth(),
        asked: MutableList<String> = mutableListOf(),
        reply: (host: String) -> Pair<Int, ByteArray>,
    ) = GoogleWebSpeech(fakeGoogle(asked, reply), health)

    @Test fun recordsGooglesMp3AndRejectsAnythingElse() =
        runBlocking {
            val file = File.createTempFile("speech", ".mp3").apply { deleteOnExit() }
            val asked = mutableListOf<String>()
            assertTrue(speech(asked = asked) { 200 to mp3 }.record("银行", "zh-Hans", file))
            assertArrayEquals(mp3, file.readBytes())
            assertEquals(listOf("translate.googleapis.com:银行"), asked)
            assertTrue(speech { 200 to mp3 }.record("きょう", "ja", file))

            asked.clear()
            // A page instead of audio: every address is asked, then the phone speaks.
            assertFalse(speech(asked = asked) { 200 to "<html>".toByteArray() }.record("银行", "zh", file))
            assertEquals(GoogleSpeechEndpoint.entries.size, asked.size)
            assertFalse(speech { 200 to mp3 }.record("你好", "zh-HK", file))
        }

    @Test fun anAddressThatIsDownHandsOverToTheNextAndIsRememberedLast() =
        runBlocking {
            val file = File.createTempFile("speech", ".mp3").apply { deleteOnExit() }
            val health = GoogleSpeechHealth()
            val asked = mutableListOf<String>()
            val speech = speech(health, asked) { host -> if (host == "translate.google.com") 200 to mp3 else 429 to ByteArray(0) }
            assertTrue(speech.record("银行", "zh", file))
            assertTrue(speech.record("你好", "zh", file))
            assertEquals(
                listOf("translate.googleapis.com:银行", "translate.google.com:银行", "translate.google.com:你好"),
                asked,
            )
        }

    @Test fun whenNoAddressAnswersThePhoneSpeaksAtOnceForTwoMinutes() =
        runBlocking {
            val file = File.createTempFile("speech", ".mp3").apply { deleteOnExit() }
            var clock = 0L
            val health = GoogleSpeechHealth(now = { clock })
            val asked = mutableListOf<String>()
            var down = true
            val speech = speech(health, asked) { if (down) UNREACHABLE to ByteArray(0) else 200 to mp3 }
            assertFalse(speech.record("银行", "zh", file))
            assertEquals(GoogleSpeechEndpoint.entries.size, asked.size)
            down = false
            clock = GOOGLE_RECHECK_INTERVAL_MS - 1
            assertFalse(speech.record("你好", "zh", file))
            assertEquals(GoogleSpeechEndpoint.entries.size, asked.size)
            clock = GOOGLE_RECHECK_INTERVAL_MS
            assertTrue(speech.record("你好", "zh", file))
        }

    /** Answers every request locally, so tests never reach Google; [UNREACHABLE] fails to connect. */
    private fun fakeGoogle(
        asked: MutableList<String>,
        reply: (host: String) -> Pair<Int, ByteArray>,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    asked += "${request.url.host}:${request.url.queryParameter("q")}"
                    val (code, body) = reply(request.url.host)
                    if (code == UNREACHABLE) throw UnknownHostException(request.url.host)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("audio/mpeg".toMediaType()))
                        .build()
                },
            ).build()

    private companion object {
        const val UNREACHABLE = -1
    }
}
