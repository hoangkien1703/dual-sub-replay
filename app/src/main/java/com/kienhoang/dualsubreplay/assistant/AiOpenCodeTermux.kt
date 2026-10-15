package com.kienhoang.dualsubreplay.assistant

import org.json.JSONException
import org.json.JSONObject

/*
 * OpenCode on this phone (owner decision 2026-10-15, docs/specs/2026-10-15-opencode-on-this-phone.md).
 * OpenCode answers its free models only for its own app: a request to its servers, or straight to
 * `opencode serve`, gets "OpenCode's free tier can only be used from within OpenCode". So each
 * question runs `opencode run` in Termux with OpenCode's own Plan agent. Its tools are set to "ask",
 * which a run nobody watches always refuses, so OpenCode never reads files or runs commands for the app.
 */

/** What Termux sent back for one command. */
internal data class TermuxResult(
    val stdout: String,
    val stderr: String,
    /** Null when Termux could not run the command at all; [error] says why. */
    val exitCode: Int?,
    val error: String? = null,
)

/** Runs [script] with bash in Termux, [args] as `$1`…, and [stdin] on standard input; or throws [AiChatException]. */
internal fun interface TermuxRunner {
    suspend fun run(
        script: String,
        args: List<String>,
        stdin: String?,
        timeoutMs: Long,
    ): TermuxResult
}

/** The folder under Termux's home where the app runs OpenCode, with its own `opencode.json`. */
internal const val OPENCODE_WORK_FOLDER = ".dualsub-replay"

/** OpenCode's tools only ask, and `opencode run` refuses every ask, so none ever runs. */
internal const val OPENCODE_CONFIG = """{"agent":{"plan":{"permission":"ask"}}}"""

/** The scripts' own exit codes: the work folder could not be written, or `opencode` is not installed. */
internal const val OPENCODE_EXIT_NO_FOLDER = 90
internal const val OPENCODE_EXIT_NOT_FOUND = 91

/** The one-time Termux command that lets other apps run commands there. */
internal const val TERMUX_ALLOW_APPS_COMMAND =
    "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"

/**
 * Prepares the work folder, writing [OPENCODE_CONFIG] every time so a changed file cannot turn the
 * tools on. Chat text never reaches the shell: the model name is `$1` and the message comes on stdin.
 */
private val OPENCODE_PREPARE =
    """
    dir="${'$'}HOME/$OPENCODE_WORK_FOLDER"
    mkdir -p "${'$'}dir" && cd "${'$'}dir" && printf '%s\n' '$OPENCODE_CONFIG' > opencode.json || exit $OPENCODE_EXIT_NO_FOLDER
    command -v opencode >/dev/null 2>&1 || exit $OPENCODE_EXIT_NOT_FOUND
    """.trimIndent()

internal val OPENCODE_RUN_SCRIPT = OPENCODE_PREPARE + "\nexec opencode run --agent plan --format json -m \"\$1\""
internal val OPENCODE_MODELS_SCRIPT = OPENCODE_PREPARE + "\nexec opencode models"

private const val MAX_OPENCODE_ERROR_CHARS = 300
private val ACTION_LINE = Regex("""<action>(.*?)</action>""", RegexOption.DOT_MATCHES_ALL)
private val ANSI_CODES = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
private val MODEL_LINE = Regex("""^[A-Za-z0-9._-]+/\S+$""")

/**
 * Everything `opencode run` reads for one request, as one message: OpenCode takes no system
 * prompt from another app, so the app's instructions, its actions and the chat so far come first,
 * each in its own section, and the model answers the last user message.
 */
internal fun openCodePrompt(
    messages: List<AiWireMessage>,
    tools: List<AiTool>,
): String =
    buildString {
        append(
            "You are the AI assistant inside the DualSub Replay Android app, answering in its chat panel. " +
                "This is not a coding task. Do not use any tools: none can run here, and every tool call is refused. " +
                "Answer only from the text in this message.\n\n",
        )
        val instructions = messages.filter { it.role == AiRole.SYSTEM }.joinToString("\n\n") { it.content }
        if (instructions.isNotBlank()) append("<app_instructions>\n").append(instructions.trim()).append("\n</app_instructions>\n\n")
        if (tools.isNotEmpty()) append(openCodeActionsSection(tools)).append("\n\n")
        append("<chat>\n")
        messages.filter { it.role != AiRole.SYSTEM }.forEach { append(openCodeChatEntry(it)).append("\n\n") }
        append("</chat>\n\n")
        append("Write your reply to the last [User] message, using any [Action result] after it. Write only the reply.")
    }

private fun openCodeActionsSection(tools: List<AiTool>): String =
    buildString {
        append("<app_actions>\n")
        append(
            "The app can do these things for the user. To ask the app for one, write a line exactly like " +
                "<action>{\"name\": \"NAME\", \"arguments\": {…}}</action> in your reply, one line per action, with arguments " +
                "that follow the schema. The app runs it and sends you the result. Only ask for what the user wants.\n",
        )
        tools.forEach { tool -> append("- ${tool.name}: ${tool.description}\n  arguments: ${tool.parametersJson}\n") }
        append("</app_actions>")
    }

private fun openCodeChatEntry(message: AiWireMessage): String =
    when (message.role) {
        AiRole.USER -> "[User]\n" + message.content
        AiRole.TOOL -> "[Action result]\n" + message.content
        else ->
            "[Assistant]\n" +
                (listOf(message.content.trim()) + message.toolCalls.map { "<action>" + openCodeActionJson(it) + "</action>" })
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
    }

private fun openCodeActionJson(call: AiToolCall): String {
    val arguments = aiToolArguments(call) ?: JSONObject()
    return JSONObject().put("name", call.name).put("arguments", arguments).toString()
}

/** Pictures and PDFs cannot go through the command line, so such a question fails before Termux is asked. */
internal fun openCodeRefusesAttachments(messages: List<AiWireMessage>): Boolean =
    messages.any { message -> message.attachments.any { it.kind != AiAttachmentKind.TEXT && it.available } }

/** The answer of one `opencode run`, or the reason it has none. */
internal fun openCodeReply(result: TermuxResult): AiReply {
    val exitCode = result.exitCode ?: throw termuxFailure(result.error)
    when (exitCode) {
        OPENCODE_EXIT_NOT_FOUND -> throw AiChatException(AiErrorKind.OPENCODE_MISSING)
        OPENCODE_EXIT_NO_FOLDER -> throw AiChatException(AiErrorKind.TERMUX_FAILED, "Could not write ~/$OPENCODE_WORK_FOLDER in Termux.")
    }
    val texts = mutableListOf<String>()
    var failure: AiChatException? = null
    result.stdout.lineSequence().mapNotNull(::jsonLine).forEach { event ->
        when (event.optString("type")) {
            "text" ->
                event
                    .optJSONObject("part")
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(texts::add)
            "error" -> failure = openCodeFailure(event.optJSONObject("error"))
        }
    }
    // A later step can fail after the model already answered; the answer still counts.
    if (texts.isEmpty()) {
        failure?.let { throw it }
        if (exitCode !=
            0
        ) {
            throw AiChatException(AiErrorKind.TERMUX_FAILED, openCodeDetail(result.stderr) ?: "OpenCode stopped with code $exitCode.")
        }
    }
    return openCodeAnswer(texts.joinToString("\n\n"))
}

/** The answer's text without its action lines, and the actions those lines ask for. */
internal fun openCodeAnswer(text: String): AiReply {
    val calls =
        ACTION_LINE
            .findAll(text)
            .mapIndexedNotNull { index, match ->
                val json = jsonObject(match.groupValues[1].trim()) ?: return@mapIndexedNotNull null
                val name = json.optString("name").trim()
                if (name.isEmpty()) return@mapIndexedNotNull null
                val arguments = json.optJSONObject("arguments") ?: JSONObject()
                val id = "call_$index"
                val raw =
                    JSONObject()
                        .put("id", id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", arguments.toString()))
                AiToolCall(id, name, arguments.toString(), raw.toString())
            }.toList()
    val shown = ACTION_LINE.replace(text, "").replace(Regex("""\n{3,}"""), "\n\n").trim()
    if (shown.isEmpty() && calls.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no text.")
    return AiReply(shown, calls)
}

/** OpenCode's error event, for example `{"type": "provider.quota", "message": …, "status": 429}`. */
internal fun openCodeFailure(error: JSONObject?): AiChatException {
    val type = error?.optString("type").orEmpty()
    val message =
        error
            ?.optString("message")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::openCodeDetail)
    val status = error?.optInt("status") ?: 0
    val kind =
        when {
            type == "provider.no-route" -> AiErrorKind.UNKNOWN_MODEL
            type == "provider.quota" -> AiErrorKind.RATE_LIMITED
            // There is no key to fix: a refusal is OpenCode's own rule, shown with its message.
            status in 400..599 ->
                aiErrorKindForStatus(status, message).let {
                    if (it ==
                        AiErrorKind.INVALID_KEY
                    ) {
                        AiErrorKind.BAD_REQUEST
                    } else {
                        it
                    }
                }
            else -> AiErrorKind.SERVER
        }
    return AiChatException(kind, message ?: type.ifEmpty { null })
}

/** Termux could not run the command; its message names `allow-external-apps` when other apps are not allowed yet. */
internal fun termuxFailure(error: String?): AiChatException {
    val detail = error?.let(::openCodeDetail)
    val kind = if (detail != null && "allow-external-apps" in detail) AiErrorKind.TERMUX_APPS_NOT_ALLOWED else AiErrorKind.TERMUX_FAILED
    return AiChatException(kind, detail)
}

/** The models `opencode models` lists, one `provider/model` per line; OpenCode's own free ones are marked. */
internal fun parseOpenCodeModels(stdout: String): List<AiModelInfo> =
    stdout
        .lineSequence()
        .map { it.trim() }
        .filter { MODEL_LINE.matches(it) }
        .distinct()
        .map { AiModelInfo(it, free = it.startsWith("opencode/") && (it.endsWith("-free") || it == "opencode/big-pickle")) }
        .sortedBy { it.id.lowercase() }
        .toList()

/** The last lines of [text] without terminal colors, short enough to show; null when empty. */
private fun openCodeDetail(text: String): String? {
    val clean = ANSI_CODES.replace(text, "").trim()
    if (clean.isEmpty()) return null
    return if (clean.length > MAX_OPENCODE_ERROR_CHARS) "…" + clean.takeLast(MAX_OPENCODE_ERROR_CHARS) else clean
}

private fun jsonLine(line: String): JSONObject? = line.trim().takeIf { it.startsWith("{") }?.let(::jsonObject)

private fun jsonObject(text: String): JSONObject? =
    try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

/** Chat and the model list through `opencode run` and `opencode models` in Termux. */
internal class AiOpenCodeTransport(
    private val termux: TermuxRunner,
) : AiChatTransport {
    override suspend fun complete(request: AiChatRequest): AiReply {
        if (openCodeRefusesAttachments(request.messages)) {
            throw AiChatException(AiErrorKind.UNSUPPORTED_ATTACHMENT, "OpenCode on this phone reads text only.")
        }
        val prompt = openCodePrompt(request.messages, request.tools)
        return openCodeReply(termux.run(OPENCODE_RUN_SCRIPT, listOf(request.model), prompt, request.timeoutMs))
    }

    suspend fun listModels(): List<AiModelInfo> {
        val result = termux.run(OPENCODE_MODELS_SCRIPT, emptyList(), null, AI_TEST_TIMEOUT_MS)
        val exitCode = result.exitCode ?: throw termuxFailure(result.error)
        if (exitCode == OPENCODE_EXIT_NOT_FOUND) throw AiChatException(AiErrorKind.OPENCODE_MISSING)
        val models = parseOpenCodeModels(result.stdout)
        if (models.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, openCodeDetail(result.stderr) ?: "OpenCode listed no models.")
        return models
    }
}

/** OpenCode on this phone goes to Termux; every other service to [http]. */
internal class AiRoutingTransport(
    private val http: AiHttpTransport,
    private val openCode: AiOpenCodeTransport,
) : AiChatTransport,
    AiModelLister {
    override suspend fun complete(request: AiChatRequest): AiReply =
        if (request.format == AiWireFormat.OPENCODE_TERMUX) openCode.complete(request) else http.complete(request)

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        format: AiWireFormat,
    ): List<AiModelInfo> = if (format == AiWireFormat.OPENCODE_TERMUX) openCode.listModels() else http.listModels(baseUrl, apiKey, format)
}
