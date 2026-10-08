package com.kienhoang.dualsubreplay.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An action the model may ask the app to run, in the OpenAI `tools` format. */
internal data class AiTool(
    val name: String,
    val description: String,
    /** A JSON schema object, as text. */
    val parametersJson: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", JSONObject(parametersJson)),
            )
}

/**
 * The model asking for one action. [rawJson] is the call exactly as the service sent it and is sent
 * back unchanged: Gemini signs its calls (`extra_content`) and refuses a follow-up without the signature.
 */
internal data class AiToolCall(
    val id: String,
    val name: String,
    /** The arguments as JSON text; `{}` when there are none. */
    val arguments: String,
    val rawJson: String,
)

/** What the model answered: text, actions it asks for, or both. [model] is the model that answered, when the service says. */
internal data class AiReply(
    val text: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val model: String = "",
)

/** The calls in a reply's `message.tool_calls`; calls without a name are skipped. */
internal fun parseAiToolCalls(message: JSONObject?): List<AiToolCall> {
    val calls = message?.optJSONArray("tool_calls") ?: return emptyList()
    return (0 until calls.length()).mapNotNull { index ->
        val call = calls.optJSONObject(index) ?: return@mapNotNull null
        val function = call.optJSONObject("function") ?: return@mapNotNull null
        val name = function.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        // Some services send the arguments as an object instead of JSON text.
        val arguments =
            when (val raw = function.opt("arguments")) {
                is JSONObject -> raw.toString()
                is String -> raw.ifBlank { "{}" }
                else -> "{}"
            }
        val id = call.optString("id").ifBlank { "call_$index" }
        // A call without an id gets one, and the follow-up must name the same id.
        AiToolCall(id, name, arguments, JSONObject(call.toString()).put("id", id).toString())
    }
}

/** A tool call's arguments as an object; null when they are not a JSON object. */
internal fun aiToolArguments(call: AiToolCall): JSONObject? =
    try {
        JSONObject(call.arguments)
    } catch (_: JSONException) {
        null
    }

/** The `tools` array for a request. */
internal fun aiToolsJson(tools: List<AiTool>): JSONArray = JSONArray().apply { tools.forEach { put(it.toJson()) } }

/** An assistant message's `tool_calls`, sent back as the service sent them. */
internal fun aiToolCallsJson(calls: List<AiToolCall>): JSONArray = JSONArray().apply { calls.forEach { put(JSONObject(it.rawJson)) } }
