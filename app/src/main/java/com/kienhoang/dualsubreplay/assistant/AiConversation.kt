package com.kienhoang.dualsubreplay.assistant

/** Only this many recent messages, and about this many characters, go into one request. */
internal const val MAX_AI_REQUEST_MESSAGES = 16
internal const val MAX_AI_REQUEST_CHARS = 24_000
private const val CHAT_TITLE_CHARS = 60

/**
 * One message in a chat. [context] is extra text sent with a user message but not shown as its
 * text: a problem's details or the subtitle line being asked about.
 */
internal data class AiChatMessage(
    val id: String,
    val role: AiRole,
    val text: String,
    val timeMs: Long,
    val context: String? = null,
    /** Short label shown above a message that carries [context], for example the problem's title. */
    val contextLabel: String? = null,
)

internal data class AiChat(
    val id: String,
    val createdMs: Long,
    val updatedMs: Long,
    val messages: List<AiChatMessage>,
) {
    /** The first question, shortened, names the chat in the history list. */
    val title: String
        get() =
            messages
                .firstOrNull { it.role == AiRole.USER }
                ?.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.let { if (it.length > CHAT_TITLE_CHARS) it.take(CHAT_TITLE_CHARS).trimEnd() + "…" else it }
                .orEmpty()
}

/** What a user message looks like to the service: its text, then its hidden context. */
internal fun AiChatMessage.wireContent(): String = if (context.isNullOrBlank()) text else "$text\n\n$context"

/**
 * The system message, then as many of the latest messages as fit in [maxMessages] and [maxChars],
 * oldest first. The newest message always goes, and the kept part starts with a user message,
 * because some services reject a conversation that opens with the assistant.
 */
internal fun buildAiRequestMessages(
    systemPrompt: String,
    history: List<AiChatMessage>,
    maxMessages: Int = MAX_AI_REQUEST_MESSAGES,
    maxChars: Int = MAX_AI_REQUEST_CHARS,
): List<AiWireMessage> {
    val kept = ArrayDeque<AiWireMessage>()
    var chars = systemPrompt.length
    for (message in history.asReversed()) {
        if (message.role == AiRole.SYSTEM) continue
        val content = message.wireContent()
        if (kept.isNotEmpty() && (kept.size >= maxMessages || chars + content.length > maxChars)) break
        kept.addFirst(AiWireMessage(message.role, content))
        chars += content.length
    }
    while (kept.size > 1 && kept.first().role != AiRole.USER) kept.removeFirst()
    return listOf(AiWireMessage(AiRole.SYSTEM, systemPrompt)) + kept
}
