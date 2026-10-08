package com.kienhoang.dualsubreplay.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
        assertEquals(AiErrorKind.INVALID_KEY, aiErrorKindForStatus(400, "API key not valid. Please pass a valid API key."))
        assertEquals(AiErrorKind.NO_CREDIT, aiErrorKindForStatus(402, null))
        assertEquals(AiErrorKind.NO_CREDIT, aiErrorKindForStatus(429, "You exceeded your current quota"))
        assertEquals(AiErrorKind.RATE_LIMITED, aiErrorKindForStatus(429, "Rate limit reached"))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(404, null))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(400, "models/foo is not found"))
        assertEquals(AiErrorKind.BAD_REQUEST, aiErrorKindForStatus(400, "Bad input"))
        assertEquals(AiErrorKind.SERVER, aiErrorKindForStatus(503, null))
    }
}
