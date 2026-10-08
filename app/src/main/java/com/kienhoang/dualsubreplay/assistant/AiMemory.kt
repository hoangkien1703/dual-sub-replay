package com.kienhoang.dualsubreplay.assistant

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/** Directory under the app's files for memories and instructions; backups leave it out. */
internal const val AI_MEMORY_DIRECTORY = "ai-memory"

/** Saved memories kept at most; when full, the assistant asks which one to replace. */
internal const val MAX_AI_MEMORIES = 30
internal const val MAX_AI_MEMORY_CHARS = 200
internal const val MAX_AI_INSTRUCTIONS_CHARS = 1_500

private val SPACES = Regex("\\s+")

/** A note the assistant saved about the user, in the reply language, for example "Studies for JLPT N3". */
internal data class AiMemory(
    val id: String,
    val text: String,
    val createdMs: Long,
)

/** What the user wrote for the assistant, and what it saved; kept on the phone in [AiMemoryStore]. */
internal data class AiMemoryData(
    val instructions: String = "",
    /** Oldest first. */
    val memories: List<AiMemory> = emptyList(),
)

/** A memory's text as saved: one line, at most [MAX_AI_MEMORY_CHARS]; null when empty or too long. */
internal fun aiMemoryText(text: String): String? =
    text.replace(SPACES, " ").trim().takeIf {
        it.isNotEmpty() &&
            it.length <= MAX_AI_MEMORY_CHARS
    }

/** Two memories that say the same thing, ignoring case and spaces. */
private fun sameMemory(
    first: String,
    second: String,
): Boolean = first.replace(SPACES, " ").trim().lowercase(Locale.ROOT) == second.replace(SPACES, " ").trim().lowercase(Locale.ROOT)

/** Whether memory is read and saved in a chat, and if not, why. */
internal enum class AiMemoryUse {
    ON,
    OFF_IN_SETTINGS,
    OFF_IN_CHAT,
}

internal fun aiMemoryUse(
    memoryEnabled: Boolean,
    chatUsesMemory: Boolean,
): AiMemoryUse =
    when {
        !memoryEnabled -> AiMemoryUse.OFF_IN_SETTINGS
        !chatUsesMemory -> AiMemoryUse.OFF_IN_CHAT
        else -> AiMemoryUse.ON
    }

/**
 * The end of the system prompt: the user's instructions, then the saved memories, numbered for
 * `save_memory`'s `replaces` and for `forget_memory`. [memories] is empty unless [use] is on.
 */
internal fun aiMemoryPrompt(
    instructions: String,
    memories: List<AiMemory>,
    use: AiMemoryUse,
): String =
    buildString {
        instructions.trim().takeIf { it.isNotEmpty() }?.let {
            append("\n\nThe user's own instructions for you, from AI settings (follow them unless they break the rules above):\n")
            append(it)
        }
        append("\n\n")
        when (use) {
            AiMemoryUse.OFF_IN_SETTINGS ->
                append(
                    "Memory is off in AI settings: you cannot see or save memories. If the user asks you to remember " +
                        "something, say that Use memory can be turned on in AI settings, Memory.",
                )
            AiMemoryUse.OFF_IN_CHAT -> append("Memory is off for this chat: you cannot see or save memories here.")
            AiMemoryUse.ON ->
                if (memories.isEmpty()) {
                    append("Saved memories: none yet.")
                } else {
                    append("Saved memories (notes from what the user told you earlier; ")
                    append(memories.size).append(" of ").append(MAX_AI_MEMORIES).append(" used):")
                    memories.forEachIndexed { index, memory -> append('\n').append(index + 1).append(". ").append(memory.text) }
                }
        }
    }

/** Why [action] cannot change memory now, in English for the model, or null. Undo is never refused. */
internal fun aiMemoryRefusal(
    data: AiMemoryData,
    action: AiAction,
    use: AiMemoryUse,
): String? {
    if (action !is AiAction.SaveMemory && action !is AiAction.ForgetMemory) return null
    if (use == AiMemoryUse.OFF_IN_SETTINGS) return "Memory is off in AI settings. Tell the user it can be turned on there."
    if (use == AiMemoryUse.OFF_IN_CHAT) return "Memory is off for this chat."
    val ids = data.memories.map { it.id }.toSet()
    return when (action) {
        is AiAction.SaveMemory ->
            when {
                action.replaces != null && action.replaces.id !in ids -> "That memory was already changed or forgotten."
                action.replaces == null && data.memories.size >= MAX_AI_MEMORIES &&
                    data.memories.none {
                        sameMemory(
                            it.text,
                            action.text,
                        )
                    }
                ->
                    "Memory is full ($MAX_AI_MEMORIES saved). Ask the user which saved memory to replace, then save again " +
                        "with replaces set to its number."
                else -> null
            }
        is AiAction.ForgetMemory -> if (action.memory.id !in ids) "That memory was already forgotten." else null
        else -> null
    }
}

/** What a memory chip says happened. */
internal enum class AiMemoryEvent {
    SAVED,
    UPDATED,
    FORGOT,
    ALREADY_SAVED,

    /** Undo put the list back. */
    PUT_BACK,
}

internal sealed interface AiMemoryChange {
    /** [data] is the memory afterwards; [note] is English, for the model; [undo] puts the list back. */
    data class Done(
        val data: AiMemoryData,
        val event: AiMemoryEvent,
        val text: String,
        val note: String,
        val undo: List<AiAction> = emptyList(),
    ) : AiMemoryChange

    data class Refused(
        val reason: String,
    ) : AiMemoryChange
}

/** [data] after [action]; a check with [aiMemoryRefusal] comes first. */
internal fun changeAiMemory(
    data: AiMemoryData,
    action: AiAction,
    newId: () -> String,
    nowMs: Long,
): AiMemoryChange {
    val memories = data.memories
    return when (action) {
        is AiAction.SaveMemory -> {
            val replaced = action.replaces?.let { old -> memories.firstOrNull { it.id == old.id } }
            memories.firstOrNull { it.id != replaced?.id && sameMemory(it.text, action.text) }?.let {
                return AiMemoryChange.Done(data, AiMemoryEvent.ALREADY_SAVED, it.text, "\"${it.text}\" was already saved")
            }
            // A memory that replaces another takes its place in the list.
            val memory = AiMemory(newId(), action.text, replaced?.createdMs ?: nowMs)
            val updated = if (replaced == null) memories + memory else memories.map { if (it.id == replaced.id) memory else it }
            AiMemoryChange.Done(
                data = data.copy(memories = updated),
                event = if (replaced == null) AiMemoryEvent.SAVED else AiMemoryEvent.UPDATED,
                text = memory.text,
                note = "Saved the memory \"${memory.text}\"" + (replaced?.let { " in place of \"${it.text}\"" } ?: ""),
                undo = listOfNotNull(AiAction.RemoveMemory(memory.id), replaced?.let { AiAction.RestoreMemory(it) }),
            )
        }
        is AiAction.ForgetMemory -> {
            val memory =
                memories.firstOrNull { it.id == action.memory.id } ?: return AiMemoryChange.Refused("That memory was already forgotten.")
            AiMemoryChange.Done(
                data = data.copy(memories = memories.filterNot { it.id == memory.id }),
                event = AiMemoryEvent.FORGOT,
                text = memory.text,
                note = "Forgot the memory \"${memory.text}\"",
                undo = listOf(AiAction.RestoreMemory(memory)),
            )
        }
        is AiAction.RemoveMemory ->
            AiMemoryChange.Done(data.copy(memories = memories.filterNot { it.id == action.id }), AiMemoryEvent.PUT_BACK, "", "Undone")
        is AiAction.RestoreMemory -> {
            val restored =
                if (memories.any {
                        it.id == action.memory.id
                    }
                ) {
                    memories
                } else {
                    (memories + action.memory).sortedBy { it.createdMs }
                }
            AiMemoryChange.Done(data.copy(memories = restored), AiMemoryEvent.PUT_BACK, action.memory.text, "Undone")
        }
        else -> AiMemoryChange.Refused("Not a memory action.")
    }
}

internal fun encodeAiMemory(data: AiMemoryData): String =
    JSONObject()
        .put("version", 1)
        .put("instructions", data.instructions)
        .put(
            "memories",
            JSONArray().apply {
                data.memories.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.createdMs)) }
            },
        ).toString()

/** Reads [encodeAiMemory] output; an unreadable file reads as empty, an unreadable entry is skipped. */
internal fun decodeAiMemory(text: String): AiMemoryData {
    val root =
        try {
            JSONObject(text)
        } catch (_: JSONException) {
            return AiMemoryData()
        }
    val memories = root.optJSONArray("memories") ?: JSONArray()
    return AiMemoryData(
        instructions = root.optString("instructions").take(MAX_AI_INSTRUCTIONS_CHARS),
        memories =
            (0 until memories.length())
                .mapNotNull { index ->
                    val memory = memories.optJSONObject(index) ?: return@mapNotNull null
                    AiMemory(
                        id = memory.optString("id").ifEmpty { return@mapNotNull null },
                        text = aiMemoryText(memory.optString("text")) ?: return@mapNotNull null,
                        createdMs = memory.optLong("created"),
                    )
                }.take(MAX_AI_MEMORIES),
    )
}

/**
 * Memories and instructions in one JSON file in the app's private files (`files/ai-memory/`),
 * which backups leave out. Writes go to a temporary file first, so a crash never leaves half a file.
 */
internal class AiMemoryStore(
    private val directory: File,
) {
    private val file get() = File(directory, "memory.json")

    fun load(): AiMemoryData =
        try {
            if (file.isFile) decodeAiMemory(file.readText()) else AiMemoryData()
        } catch (_: IOException) {
            AiMemoryData()
        }

    fun save(data: AiMemoryData) {
        try {
            directory.mkdirs()
            val temporary = File(directory, "memory.json.tmp")
            temporary.writeText(encodeAiMemory(data))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        } catch (_: IOException) {
            // Memory is a convenience: a failed write must never break the chat.
        }
    }
}
