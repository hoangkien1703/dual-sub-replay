package com.kienhoang.dualsubreplay.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolsTest {
    /** A call as Gemini sends it, with its signature in `extra_content`. */
    private val signedCall =
        """{"id":"call_1","type":"function","function":{"name":"change_setting","arguments":"{\"setting\":\"text_size\",""" +
            """\"value\":\"150\"}"},"extra_content":{"google":{"thought_signature":"c2lnbmF0dXJl"}}}"""

    @Test
    fun toolsAreSentAsFunctionsOnlyWhenOffered() {
        val plain = JSONObject(chatRequestBody("m", listOf(AiWireMessage(AiRole.USER, "Hi"))))
        assertTrue(!plain.has("tools"))
        val body = JSONObject(chatRequestBody("m", listOf(AiWireMessage(AiRole.USER, "Hi")), tools = AI_TOOLS))
        val tools = body.getJSONArray("tools")
        assertEquals(AI_TOOLS.size, tools.length())
        val first = tools.getJSONObject(0)
        assertEquals("function", first.getString("type"))
        assertEquals(AI_LOOK_TOOL, first.getJSONObject("function").getString("name"))
    }

    @Test
    fun everyToolHasAnObjectSchemaWithProperties() {
        // Gemini refuses an object schema without properties.
        for (tool in AI_TOOLS) {
            assertTrue(tool.name, tool.name.matches(Regex("[a-z_]{1,64}")))
            val schema = JSONObject(tool.parametersJson)
            assertEquals(tool.name, "object", schema.getString("type"))
            assertTrue(tool.name, schema.getJSONObject("properties").length() > 0)
        }
        val settings = AI_TOOLS.first { it.name == AI_SETTING_TOOL }
        AiSetting.entries.forEach { assertTrue(it.key, it.key in settings.description) }
    }

    @Test
    fun aSignedCallIsSentBackUnchangedWithItsResult() {
        val calls = parseAiToolCalls(JSONObject("""{"tool_calls":[$signedCall]}"""))
        val body =
            JSONObject(
                chatRequestBody(
                    "gemini-flash-lite-latest",
                    listOf(
                        AiWireMessage(AiRole.USER, "Bigger please"),
                        AiWireMessage(AiRole.ASSISTANT, "", toolCalls = calls),
                        AiWireMessage(AiRole.TOOL, "Done: text_size changed from 100 to 150.", toolCallId = "call_1"),
                    ),
                    tools = AI_TOOLS,
                ),
            )
        val messages = body.getJSONArray("messages")
        val assistant = messages.getJSONObject(1)
        assertTrue(assistant.isNull("content"))
        assertEquals(JSONObject(signedCall).toString(), assistant.getJSONArray("tool_calls").getJSONObject(0).toString())
        val result = messages.getJSONObject(2)
        assertEquals("tool", result.getString("role"))
        assertEquals("call_1", result.getString("tool_call_id"))
    }

    @Test
    fun repliesCarryTextCallsOrBoth() {
        val onlyCall = parseAiReply("""{"model":"g","choices":[{"message":{"content":null,"tool_calls":[$signedCall]}}]}""")
        assertEquals("", onlyCall.text)
        assertEquals("change_setting", onlyCall.toolCalls.single().name)
        assertEquals("""{"setting":"text_size","value":"150"}""", onlyCall.toolCalls.single().arguments)
        assertEquals("g", onlyCall.model)
        val both = parseAiReply("""{"choices":[{"message":{"content":"Sure.","tool_calls":[$signedCall]}}]}""")
        assertEquals("Sure.", both.text)
        assertEquals(1, both.toolCalls.size)
    }

    @Test
    fun callsWithoutAnIdOrWithObjectArgumentsAreRepaired() {
        val calls =
            parseAiToolCalls(
                JSONObject(
                    """{"tool_calls":[{"function":{"name":"look_at_video","arguments":{"lines_around":1}}},""" +
                        """{"function":{"name":"","arguments":"{}"}},{"id":"b","function":{"name":"control_playback"}}]}""",
                ),
            )
        assertEquals(2, calls.size)
        assertEquals("call_0", calls[0].id)
        assertEquals("call_0", JSONObject(calls[0].rawJson).getString("id"))
        assertEquals(1, JSONObject(calls[0].arguments).getInt("lines_around"))
        assertEquals("{}", calls[1].arguments)
    }

    @Test
    fun aModelWithoutToolsIsToldApartFromAnUnknownModel() {
        val message = "No endpoints found that support tool use."
        assertEquals(AiErrorKind.UNSUPPORTED_TOOLS, aiErrorKindForStatus(404, message, sentTools = true))
        assertEquals(AiErrorKind.UNKNOWN_MODEL, aiErrorKindForStatus(404, "No endpoints found for this model."))
        assertEquals(AiErrorKind.RATE_LIMITED, aiErrorKindForStatus(429, "Too many tool calls", sentTools = true))
    }
}
