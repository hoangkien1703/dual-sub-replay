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

    @Test fun otherLanguagesAndCantoneseStayWithThePhone() {
        assertNull(googleSpeechLanguage("ja"))
        assertNull(googleSpeechLanguage("en"))
        assertNull(googleSpeechLanguage("zh-HK"))
        assertNull(googleSpeechLanguage("yue"))
        assertNull(googleSpeechLanguage("auto"))
    }

    @Test fun onlyChineseWhileGoogleTranslatesUsesGoogle() {
        assertTrue(speaksWithGoogle("银行", "zh-Hans", TranslationEngine.GOOGLE_WEB))
        assertFalse(speaksWithGoogle("银行", "zh-Hans", TranslationEngine.ON_DEVICE))
        assertFalse(speaksWithGoogle("今日", "ja", TranslationEngine.GOOGLE_WEB))
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

    @Test fun recordsGooglesMp3AndRejectsAnythingElse() =
        runBlocking {
            val mp3 = byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x64, 0x00)
            val file = File.createTempFile("speech", ".mp3").apply { deleteOnExit() }
            val asked = mutableListOf<String?>()
            assertTrue(GoogleWebSpeech(fakeGoogle(asked) { 200 to mp3 }).record("银行", "zh-Hans", file))
            assertArrayEquals(mp3, file.readBytes())
            assertEquals(listOf<String?>("银行"), asked)

            assertFalse(GoogleWebSpeech(fakeGoogle(asked) { 200 to "<html>".toByteArray() }).record("银行", "zh", file))
            assertFalse(GoogleWebSpeech(fakeGoogle(asked) { 429 to mp3 }).record("银行", "zh", file))
            assertFalse(GoogleWebSpeech(fakeGoogle(asked) { 200 to mp3 }).record("今日", "ja", file))
            assertEquals(3, asked.size)
        }

    /** Answers every request locally, so tests never reach Google. */
    private fun fakeGoogle(
        asked: MutableList<String?>,
        reply: () -> Pair<Int, ByteArray>,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    asked += request.url.queryParameter("q")
                    val (code, body) = reply()
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
}
