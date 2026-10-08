package com.kienhoang.dualsubreplay.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The newest chats kept at most, so the history file stays small. */
internal const val MAX_SAVED_AI_CHATS = 100

/** The chats [retention] keeps at [nowMs], newest first, at most [MAX_SAVED_AI_CHATS]. */
internal fun keptAiChats(
    chats: List<AiChat>,
    retention: ChatHistoryRetention,
    nowMs: Long,
): List<AiChat> {
    if (retention == ChatHistoryRetention.OFF) return emptyList()
    val cutoff = retention.cutoffMs(nowMs)
    return chats
        .filter { it.messages.isNotEmpty() && (cutoff == null || it.updatedMs >= cutoff) }
        .sortedByDescending { it.updatedMs }
        .take(MAX_SAVED_AI_CHATS)
}

internal fun encodeAiChats(chats: List<AiChat>): String =
    JSONObject()
        .put("version", 1)
        .put(
            "chats",
            JSONArray().apply {
                chats.forEach { chat ->
                    put(
                        JSONObject()
                            .put("id", chat.id)
                            .put("created", chat.createdMs)
                            .put("updated", chat.updatedMs)
                            .put("messages", JSONArray().apply { chat.messages.forEach { put(encodeMessage(it)) } }),
                    )
                }
            },
        ).toString()

private fun encodeMessage(message: AiChatMessage): JSONObject =
    JSONObject()
        .put("id", message.id)
        .put("role", message.role.wire)
        .put("text", message.text)
        .put("time", message.timeMs)
        .apply {
            message.context?.let { put("context", it) }
            message.contextLabel?.let { put("contextLabel", it) }
            // Only the names: pictures and files are never written to the phone's storage.
            if (message.attachments.isNotEmpty()) {
                put(
                    "attachments",
                    JSONArray().apply { message.attachments.forEach { put(JSONObject().put("name", it.name).put("kind", it.kind.key)) } },
                )
            }
        }

/** Reads [encodeAiChats] output; anything unreadable is skipped rather than failing the whole file. */
internal fun decodeAiChats(text: String): List<AiChat> {
    val chats =
        try {
            JSONObject(text).optJSONArray("chats") ?: return emptyList()
        } catch (_: JSONException) {
            return emptyList()
        }
    return (0 until chats.length()).mapNotNull { index ->
        val chat = chats.optJSONObject(index) ?: return@mapNotNull null
        val id = chat.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val messages = chat.optJSONArray("messages") ?: JSONArray()
        AiChat(
            id = id,
            createdMs = chat.optLong("created"),
            updatedMs = chat.optLong("updated"),
            messages = (0 until messages.length()).mapNotNull { decodeMessage(messages.optJSONObject(it)) },
        )
    }
}

private fun decodeMessage(json: JSONObject?): AiChatMessage? {
    if (json == null) return null
    val role = AiRole.entries.firstOrNull { it.wire == json.optString("role") && it != AiRole.SYSTEM } ?: return null
    return AiChatMessage(
        id = json.optString("id").ifEmpty { return null },
        role = role,
        text = json.optString("text"),
        timeMs = json.optLong("time"),
        context = json.optString("context").takeIf { json.has("context") },
        contextLabel = json.optString("contextLabel").takeIf { json.has("contextLabel") },
        attachments = decodeAttachmentNames(json.optJSONArray("attachments")),
    )
}

private fun decodeAttachmentNames(json: JSONArray?): List<AiAttachment> {
    if (json == null) return emptyList()
    return (0 until json.length()).mapNotNull { index ->
        val file = json.optJSONObject(index) ?: return@mapNotNull null
        val kind = AiAttachmentKind.entries.firstOrNull { it.key == file.optString("kind") } ?: return@mapNotNull null
        AiAttachment(file.optString("name"), kind, data = "")
    }
}

/**
 * Saved chats in one JSON file in the app's private files (`files/ai-chats/`), which backups
 * leave out. Writes go to a temporary file first, so a crash never leaves half a history.
 */
internal class AiChatHistoryStore(
    private val directory: File,
) {
    private val file get() = File(directory, "chats.json")

    fun load(): List<AiChat> =
        try {
            if (file.isFile) decodeAiChats(file.readText()) else emptyList()
        } catch (_: IOException) {
            emptyList()
        }

    fun save(chats: List<AiChat>) {
        if (chats.isEmpty()) {
            clear()
            return
        }
        try {
            directory.mkdirs()
            val temporary = File(directory, "chats.json.tmp")
            temporary.writeText(encodeAiChats(chats))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
            // History is a convenience: a failed write must never break the chat.
        }
    }

    fun clear() {
        directory.deleteRecursively()
    }
}
