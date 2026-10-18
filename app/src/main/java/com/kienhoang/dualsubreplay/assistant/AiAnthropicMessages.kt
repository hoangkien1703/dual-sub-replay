package com.kienhoang.dualsubreplay.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Sent with every Anthropic request; the Messages API needs a version header. */
internal const val ANTHROPIC_API_VERSION = "2023-06-01"

/**
 * The longest answer asked for. Anthropic needs a limit; this one leaves room for thinking and
 * stays far below the 1 MiB reply cap.
 */
internal const val ANTHROPIC_MAX_TOKENS = 16_000

/** Claude's own web searches for one request at most; each costs the user about one cent. */
internal const val ANTHROPIC_MAX_WEB_SEARCHES = 3

/**
 * The Messages API body: the system messages joined as `system`, then the conversation, with
 * `output_config.effort` when a thinking level is chosen and `tools` when actions are offered.
 * [webSearch] adds Claude's own `web_search` tool, which Anthropic runs. Messages of the same role
 * in a row become one message, and the results of one round of actions go back together in one
 * user message, as Anthropic expects.
 */
internal fun anthropicRequestBody(
    model: String,
    messages: List<AiWireMessage>,
    reasoningEffort: String? = null,
    tools: List<AiTool> = emptyList(),
    webSearch: Boolean = false,
): String {
    val system = messages.filter { it.role == AiRole.SYSTEM }.joinToString("\n\n") { it.content }
    val turns = mutableListOf<Pair<String, JSONArray>>()
    messages.filter { it.role != AiRole.SYSTEM }.forEach { message ->
        val role = if (message.role == AiRole.ASSISTANT) "assistant" else "user"
        val blocks = anthropicBlocks(message)
        if (blocks.length() == 0) return@forEach
        val last = turns.lastOrNull()
        if (last != null && last.first == role) {
            for (index in 0 until blocks.length()) last.second.put(blocks.get(index))
        } else {
            turns += role to blocks
        }
    }
    return JSONObject()
        .put("model", model)
        .put("max_tokens", ANTHROPIC_MAX_TOKENS)
        .apply {
            if (system.isNotBlank()) put("system", system)
            put(
                "messages",
                JSONArray().apply {
                    turns.forEach { (role, blocks) ->
                        put(JSONObject().put("role", role).put("content", blocks))
                    }
                },
            )
            if (reasoningEffort != null) put("output_config", JSONObject().put("effort", reasoningEffort))
            if (tools.isNotEmpty() || webSearch) {
                put(
                    "tools",
                    anthropicToolsJson(tools).apply {
                        if (webSearch) {
                            put(
                                JSONObject()
                                    .put("type", "web_search_20250305")
                                    .put("name", "web_search")
                                    .put("max_uses", ANTHROPIC_MAX_WEB_SEARCHES),
                            )
                        }
                    },
                )
            }
        }.toString()
}

/**
 * One message as content blocks. An assistant message that asked for actions goes back exactly as
 * Claude sent it, with its thinking, so the following tool results stay tied to it.
 */
private fun anthropicBlocks(message: AiWireMessage): JSONArray {
    message.rawContent?.let { return JSONArray(it) }
    return JSONArray().apply {
        when {
            message.role == AiRole.TOOL ->
                put(
                    JSONObject()
                        .put(
                            "type",
                            "tool_result",
                        ).put("tool_use_id", message.toolCallId.orEmpty())
                        .put("content", message.content),
                )
            else -> {
                // Pictures and PDFs go before the question about them.
                message.attachments.forEach { file -> anthropicFileBlock(file)?.let(::put) }
                if (message.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.content))
                message.toolCalls.forEach { call -> put(JSONObject(call.rawJson)) }
            }
        }
    }
}

/** A picture or PDF as a base64 `image` or `document` block; null for a text file or a broken `data:` URL. */
private fun anthropicFileBlock(file: AiAttachment): JSONObject? {
    val type =
        when (file.kind) {
            AiAttachmentKind.PICTURE -> "image"
            AiAttachmentKind.PDF -> "document"
            AiAttachmentKind.TEXT -> return null
        }
    val header = file.data.substringBefore(";base64,", "")
    if (!header.startsWith("data:")) return null
    val source =
        JSONObject()
            .put("type", "base64")
            .put("media_type", header.removePrefix("data:"))
            .put("data", file.data.substringAfter(";base64,"))
    return JSONObject().put("type", type).put("source", source)
}

private fun anthropicToolsJson(tools: List<AiTool>): JSONArray =
    JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put(
                        "name",
                        tool.name,
                    ).put("description", tool.description)
                    .put("input_schema", JSONObject(tool.parametersJson)),
            )
        }
    }

/**
 * The text and the actions in a Messages API reply. Thinking and web search blocks are kept only in
 * [AiReply.rawContent], which goes back unchanged with the results of the actions or, after a
 * `pause_turn`, on its own. The pages Claude's web search cited and its queries come along.
 */
internal fun parseAnthropicReply(body: String): AiReply {
    val root =
        try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw AiChatException(AiErrorKind.BAD_REPLY, "The reply was not JSON.", error)
        }
    val content = root.optJSONArray("content") ?: JSONArray()
    val blocks = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
    val text = blocks.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }.trim()
    val calls =
        blocks.filter { it.optString("type") == "tool_use" }.mapIndexedNotNull { index, block ->
            val name = block.optString("name").trim()
            if (name.isEmpty()) return@mapIndexedNotNull null
            val id = block.optString("id").ifBlank { "toolu_$index" }
            AiToolCall(id, name, block.optJSONObject("input")?.toString() ?: "{}", JSONObject(block.toString()).put("id", id).toString())
        }
    val paused = root.optString("stop_reason") == "pause_turn"
    if (text.isEmpty() && calls.isEmpty() && !paused) {
        val detail =
            if (root.optString("stop_reason") == "refusal") "Claude declined to answer this question." else "The reply had no text."
        throw AiChatException(AiErrorKind.BAD_REPLY, detail)
    }
    return AiReply(
        text,
        calls,
        root.optString("model"),
        rawContent = if (calls.isEmpty() && !paused) null else content.toString(),
        sources = anthropicCitations(blocks),
        webQueries =
            blocks
                .filter { it.optString("type") == "server_tool_use" && it.optString("name") == "web_search" }
                .mapNotNull {
                    it
                        .optJSONObject("input")
                        ?.optString("query")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                },
        paused = paused,
    )
}

/** The pages cited in the text blocks, each once. */
private fun anthropicCitations(blocks: List<JSONObject>): List<AiWebSource> =
    blocks
        .filter { it.optString("type") == "text" }
        .flatMap { block ->
            val citations = block.optJSONArray("citations") ?: return@flatMap emptyList()
            (0 until citations.length()).mapNotNull { index ->
                val citation = citations.optJSONObject(index) ?: return@mapNotNull null
                if (citation.optString("type") != "web_search_result_location") return@mapNotNull null
                aiWebSource(citation.optString("title"), citation.optString("url"))
            }
        }.distinctBy { it.url }
