package com.kienhoang.dualsubreplay.assistant

import okhttp3.Dns
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AiChatClientTest {
    @Test
    fun theRequestCarriesTheModelAndMessagesOnly() {
        val body =
            JSONObject(
                chatRequestBody(
                    "gemini-flash-latest",
                    listOf(AiWireMessage(AiRole.SYSTEM, "guide"), AiWireMessage(AiRole.USER, "What is に?")),
                ),
            )
        assertEquals("gemini-flash-latest", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("What is に?", messages.getJSONObject(1).getString("content"))
        assertEquals(setOf("model", "messages"), body.keys().asSequence().toSet())
    }

    @Test
    fun repliesAreReadAsTextOrTextParts() {
        assertEquals("Hello", parseChatReply("""{"choices":[{"message":{"role":"assistant","content":" Hello "}}]}"""))
        assertEquals(
            "Hello world",
            parseChatReply("""{"choices":[{"message":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}]}}]}"""),
        )
    }

    @Test
    fun anEmptyOrUnreadableReplyIsABadReply() {
        for (body in listOf("""{"choices":[]}""", """{"choices":[{"message":{"content":""}}]}""", "<html>", "")) {
            try {
                parseChatReply(body)
                fail(body)
            } catch (error: AiChatException) {
                assertEquals(body, AiErrorKind.BAD_REPLY, error.kind)
            }
        }
    }

    @Test
    fun serviceErrorMessagesAreFoundInEveryShape() {
        assertEquals(
            "Incorrect API key provided",
            providerErrorMessage("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )
        assertEquals(
            "API key not valid.",
            providerErrorMessage("""[{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}]"""),
        )
        assertEquals("No credits", providerErrorMessage("""{"error":"No credits"}"""))
        assertNull(providerErrorMessage("<html>Bad gateway</html>"))
        assertNull(providerErrorMessage(""))
    }

    @Test
    fun theKeyIsRemovedFromErrorText() {
        val key = "sk-proj-abcdefghijklmnop1234"
        val redacted = redactApiKey("Incorrect API key provided: $key. Find yours at …", key)
        assertFalse(key in redacted)
        assertTrue("•••" in redacted)
        assertTrue(redactApiKey("x".repeat(1000), key).length <= 301)
    }

    @Test
    fun httpStatusesMapToTheirOwnMessages() {
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(401, null))
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(403, null))
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(403, "Method doesn't allow unregistered callers."))
        // OpenRouter keeps some free models for coding apps; that is not a key problem.
        assertEquals(
            AiErrorKind.BAD_REQUEST,
            aiErrorKindForStatus(403, "thinkingmachines/inkling:free is only available on agentic harnesses."),
        )
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(400, "API key not valid. Please pass a valid API key."))
        assertEquals(AiErrorKind.NO_CREDIT, aiErrorKindForStatus(402, null))
        assertEquals(AiErrorKind.NO_CREDIT, aiErrorKindForStatus(429, "You exceeded your current quota"))
        assertEquals(AiErrorKind.RATE_LIMITED, aiErrorKindForStatus(429, "Rate limit reached"))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(404, null))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(400, "models/foo is not found"))
        assertEquals(AiErrorKind.BAD_REQUEST, aiErrorKindForStatus(400, "Bad input"))
        assertEquals(AiErrorKind.SERVER, aiErrorKindForStatus(503, null))
    }

    @Test
    fun timeoutsAreToldApartFromOtherNetworkErrors() {
        // OkHttp's whole-call deadline throws InterruptedIOException("timeout"); a socket timeout is a subclass.
        assertEquals(AiErrorKind.TIMEOUT, aiConnectionFailure(InterruptedIOException("timeout")).kind)
        assertEquals(AiErrorKind.TIMEOUT, aiConnectionFailure(SocketTimeoutException("Read timed out")).kind)
        val offline = aiConnectionFailure(UnknownHostException("Unable to resolve host \"api.openai.com\""))
        assertEquals(AiErrorKind.NETWORK, offline.kind)
        assertEquals("UnknownHostException: Unable to resolve host \"api.openai.com\"", offline.detail)
        assertEquals("IOException", aiConnectionFailure(IOException()).detail)
        val known = AiChatException(AiErrorKind.BAD_REPLY)
        assertTrue(known === aiConnectionFailure(known))
    }

    @Test
    fun ipv4AddressesAreTriedFirst() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.0.2.1")
        val dns =
            PreferIpv4Dns(
                object : Dns {
                    override fun lookup(hostname: String) = listOf(ipv6, ipv4)
                },
            )
        assertEquals(listOf(ipv4, ipv6), dns.lookup("generativelanguage.googleapis.com"))
    }

    @Test
    fun aThinkingLevelIsSentOnlyWhenChosen() {
        val body = JSONObject(chatRequestBody("gpt-5-mini", listOf(AiWireMessage(AiRole.USER, "Hi")), "high"))
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse(JSONObject(chatRequestBody("gpt-5-mini", listOf(AiWireMessage(AiRole.USER, "Hi")))).has("reasoning_effort"))
        assertEquals(
            AiErrorKind.UNSUPPORTED_THINKING,
            aiErrorKindForStatus(400, "Unsupported parameter: 'reasoning_effort' is not supported with this model.", sentThinking = true),
        )
        assertEquals(AiErrorKind.BAD_REQUEST, aiErrorKindForStatus(400, "Thinking is not enabled.", sentThinking = false))
    }

    @Test
    fun modelListsAreReadSortedWithFreeAndPictureModelsMarked() {
        val body =
            """
            {"data": [
              {"id": "openai/gpt-5-mini", "pricing": {"prompt": "0.00000025", "completion": "0.000002"},
               "architecture": {"input_modalities": ["text", "image"]}},
              {"id": "google/gemma-4-31b-it:free"},
              {"id": "openrouter/free", "pricing": {"prompt": "0", "completion": "0"}},
              {"id": "models/gemini-flash-latest"},
              {"id": ""},
              {"id": "google/gemma-4-31b-it:free"}
            ]}
            """.trimIndent()
        assertEquals(
            listOf(
                AiModelInfo("gemini-flash-latest"),
                AiModelInfo("google/gemma-4-31b-it:free", free = true),
                AiModelInfo("openai/gpt-5-mini", pictures = true),
                AiModelInfo("openrouter/free", free = true),
            ),
            parseAiModelList(body),
        )
        try {
            parseAiModelList("<html>")
            fail()
        } catch (error: AiChatException) {
            assertEquals(AiErrorKind.BAD_REPLY, error.kind)
        }
    }

    @Test
    fun onlyModelsThatCanChatHereAreOffered() {
        assertTrue(aiModelCanChat(AiProvider.OPENAI, "gpt-5-mini"))
        assertFalse(aiModelCanChat(AiProvider.OPENAI, "text-embedding-3-small"))
        assertFalse(aiModelCanChat(AiProvider.OPENAI, "gpt-4o-mini-tts"))
        assertFalse(aiModelCanChat(AiProvider.GEMINI, "gemini-2.5-flash-image"))
        assertTrue(aiModelCanChat(AiProvider.GEMINI, "gemini-flash-latest"))
        // OpenCode serves these through other APIs, so this client cannot use them.
        assertFalse(aiModelCanChat(AiProvider.OPENCODE_ZEN, "claude-sonnet-5"))
        assertFalse(aiModelCanChat(AiProvider.OPENCODE_GO, "gpt-6-luna"))
        assertTrue(aiModelCanChat(AiProvider.OPENCODE_ZEN, "big-pickle"))
        assertTrue(aiModelCanChat(AiProvider.OPENROUTER, "anthropic/claude-haiku-5.5"))
        // Safety checkers only label text as safe or unsafe.
        assertFalse(aiModelCanChat(AiProvider.OPENROUTER, "nvidia/nemotron-3.5-content-safety:free"))
        assertFalse(aiModelCanChat(AiProvider.OPENROUTER, "meta-llama/llama-guard-4-12b"))
        assertFalse(aiModelCanChat(AiProvider.OPENROUTER, "openai/gpt-oss-safeguard-20b"))
    }

    @Test
    fun aSafetyCheckerAnswerIsNotShownAsTheReply() {
        // What openrouter/free sent back when it picked a safety checker for a question with a picture.
        val checker =
            """{"model":"nvidia/nemotron-3.5-content-safety:free","choices":[{"message":{"content":"User Safety: safe"}}]}"""
        val error = assertThrows(AiChatException::class.java) { parseChatReply(checker) }
        assertEquals(AiErrorKind.NOT_A_CHAT_MODEL, error.kind)
        // Without the model's name, the labels alone give it away.
        assertTrue(aiSafetyCheckerAnswered("", "User Safety: safe\nResponse Safety: safe"))
        assertTrue(aiSafetyCheckerAnswered("", "user safety: unsafe"))
        assertFalse(aiSafetyCheckerAnswered("nvidia/nemotron-3-super-120b-a12b:free", "Red"))
        assertFalse(aiSafetyCheckerAnswered("", "User Safety: safe is a label some checkers print. Here is the answer."))
        assertEquals(
            "Red",
            parseChatReply("""{"model":"nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free","choices":[{"message":{"content":"Red"}}]}"""),
        )
    }

    @Test
    fun picturesAndPdfsGoAsPartsAfterTheText() {
        val files =
            listOf(
                AiAttachment("page.jpg", AiAttachmentKind.PICTURE, "data:image/jpeg;base64,AAAA"),
                AiAttachment("lesson.pdf", AiAttachmentKind.PDF, "data:application/pdf;base64,BBBB"),
            )
        val body = JSONObject(chatRequestBody("gemini-flash-latest", listOf(AiWireMessage(AiRole.USER, "What is this?", files))))
        val parts = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("What is this?", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AAAA", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val pdf = parts.getJSONObject(2).getJSONObject("file")
        assertEquals("lesson.pdf", pdf.getString("filename"))
        assertEquals("data:application/pdf;base64,BBBB", pdf.getString("file_data"))
        // A message without files stays plain text, which every service accepts.
        val plain = JSONObject(chatRequestBody("m", listOf(AiWireMessage(AiRole.USER, "Hi"))))
        assertEquals("Hi", plain.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun aModelThatCannotReadPicturesSaysSo() {
        val refusal = "No endpoints found that support image input"
        assertEquals(AiErrorKind.UNSUPPORTED_ATTACHMENT, aiErrorKindForStatus(404, refusal, sentAttachments = true))
        assertEquals(
            AiErrorKind.UNSUPPORTED_ATTACHMENT,
            aiErrorKindForStatus(400, "Invalid content type: image_url", sentAttachments = true),
        )
        // Without files, or for a key or limit problem, the usual message stays.
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(404, refusal))
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(401, "image", sentAttachments = true))
        assertEquals(AiErrorKind.RATE_LIMITED, aiErrorKindForStatus(429, "image", sentAttachments = true))
    }

    @Test
    fun aSuccessfulStatusWithOnlyAnErrorIsThatError() {
        assertEquals(429, aiReplyStatus(200, """{"error":{"code":429,"message":"Rate limit exceeded"}}"""))
        assertEquals(502, aiReplyStatus(200, """{"error":{"message":"Provider returned error"}}"""))
        assertEquals(200, aiReplyStatus(200, """{"choices":[{"message":{"content":"Hi"}}]}"""))
        assertEquals(200, aiReplyStatus(200, "not json"))
        assertEquals(404, aiReplyStatus(404, """{"error":{"code":429}}"""))
    }
}
