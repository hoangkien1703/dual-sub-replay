package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Translate with AI streams the answer (owner request 2026-10-28), so lines show while the model writes. */
class AiStreamingTest {
    private val sent = mutableListOf<JSONObject>()

    /** A client whose every request gets [body] back without any network. */
    private fun transport(
        body: String,
        contentType: String = "text/event-stream",
        code: Int = 200,
    ) = AiHttpTransport(
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                sent += JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("reply")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }.build(),
    )

    private fun request(format: AiWireFormat = AiWireFormat.OPENAI_CHAT) =
        AiChatRequest(
            baseUrl = if (format == AiWireFormat.ANTHROPIC_MESSAGES) "https://api.anthropic.com/v1" else "https://api.openai.com/v1",
            model = "fast-model",
            apiKey = "sk-test-key-0000",
            messages = listOf(AiWireMessage(AiRole.USER, "Translate")),
            format = format,
        )

    @Test fun openAiStyleEventsAreHandedOverPieceByPiece() =
        runBlocking {
            val events =
                """
                : OPENROUTER PROCESSING

                data: {"model":"fast-model","choices":[{"delta":{"role":"assistant","content":"{\"sentences\":["}}]}

                data: {"choices":[{"delta":{"content":null,"reasoning":"hmm"}}]}

                data: {"choices":[{"delta":{"content":"{\"id\":1}]}"}}]}

                data: [DONE]

                """.trimIndent()
            val pieces = mutableListOf<String>()
            val reply = transport(events).stream(request()) { pieces += it }
            assertEquals(listOf("{\"sentences\":[", "{\"id\":1}]}"), pieces)
            assertEquals("{\"sentences\":[{\"id\":1}]}", reply.text)
            assertEquals(true, sent.single().getBoolean("stream"))
        }

    @Test fun claudesTextDeltasAreHandedOverAndItsThinkingIsNot() =
        runBlocking {
            val events =
                """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-haiku","content":[]}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me see"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Xin "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"chào"}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent()
            val pieces = mutableListOf<String>()
            val reply = transport(events).stream(request(AiWireFormat.ANTHROPIC_MESSAGES)) { pieces += it }
            assertEquals(listOf("Xin ", "chào"), pieces)
            assertEquals("Xin chào", reply.text)
            assertEquals("claude-haiku", reply.model)
        }

    @Test fun aServiceThatDoesNotStreamIsReadWhole() =
        runBlocking {
            val body = """{"choices":[{"message":{"role":"assistant","content":"Một."}}]}"""
            val pieces = mutableListOf<String>()
            val reply = transport(body, contentType = "application/json").stream(request()) { pieces += it }
            assertEquals(listOf("Một."), pieces)
            assertEquals("Một.", reply.text)
        }

    @Test fun aRefusedRequestKeepsItsKind() =
        runBlocking {
            val body = """{"error":{"message":"Resource has been exhausted (e.g. check quota)."}}"""
            try {
                transport(body, contentType = "application/json", code = 429).stream(request()) { fail("no text") }
                fail("expected a failure")
            } catch (error: AiChatException) {
                assertEquals(AiErrorKind.NO_CREDIT, error.kind)
            }
        }

    @Test fun anErrorEventPartWayIsAFailure() =
        runBlocking {
            val events =
                """
                data: {"choices":[{"delta":{"content":"{\"sentences\":["}}]}

                data: {"error":{"code":502,"message":"Provider returned error"}}

                """.trimIndent()
            val pieces = mutableListOf<String>()
            try {
                transport(events).stream(request()) { pieces += it }
                fail("expected a failure")
            } catch (error: AiChatException) {
                assertEquals(AiErrorKind.SERVER, error.kind)
            }
            assertTrue(pieces.isNotEmpty())
        }
}
