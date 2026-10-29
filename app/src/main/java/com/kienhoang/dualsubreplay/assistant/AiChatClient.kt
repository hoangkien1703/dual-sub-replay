package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.data.ResponseLimitExceededException
import com.kienhoang.dualsubreplay.data.preferIpv4Addresses
import com.kienhoang.dualsubreplay.data.readUtf8WithLimit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_AI_RESPONSE_BYTES = 1024 * 1024

/** OpenRouter lists hundreds of models with descriptions: about 0.8 MiB in 2026. */
internal const val MAX_AI_MODEL_LIST_BYTES = 4 * 1024 * 1024
private const val AI_CONNECT_TIMEOUT_MS = 10_000L
private const val AI_WRITE_TIMEOUT_MS = 15_000L
private const val AI_READ_TIMEOUT_MS = 90_000L

/** A whole question, including a slow model thinking before it answers. */
internal const val AI_CHAT_TIMEOUT_MS = 100_000L

/** The key check asks for one word, so a longer wait means the connection is not working. */
internal const val AI_TEST_TIMEOUT_MS = 30_000L
private const val MAX_AI_ERROR_DETAIL_CHARS = 300
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Why a request failed; each has its own translated message. */
internal enum class AiErrorKind {
    NO_KEY,
    KEY_UNREADABLE,
    BAD_ADDRESS,
    INVALID_KEY,
    NO_CREDIT,
    UNKNOWN_MODEL,
    RATE_LIMITED,
    BAD_REQUEST,
    SERVER,
    NETWORK,
    TIMEOUT,
    BAD_REPLY,

    /** The model refused the chosen thinking level. */
    UNSUPPORTED_THINKING,

    /** The model cannot read pictures or files. */
    UNSUPPORTED_ATTACHMENT,

    /** A model that only labels text as safe or unsafe answered, not a chat model. */
    NOT_A_CHAT_MODEL,

    /** The model cannot take the app's actions; the question is asked again without them. */
    UNSUPPORTED_TOOLS,

    /** The service refused its own web search (off for the account, or not for this model); asked again without it. */
    UNSUPPORTED_WEB_SEARCH,
}

/** [detail] is the service's own English message, with the key removed, for reports. */
internal class AiChatException(
    val kind: AiErrorKind,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(detail ?: kind.name, cause)

internal enum class AiRole(
    val wire: String,
) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),

    /** The result of an action the model asked for; only sent within one answer, never saved. */
    TOOL("tool"),
}

internal data class AiWireMessage(
    val role: AiRole,
    val content: String,
    /** Pictures and PDFs; text files are part of [content]. */
    val attachments: List<AiAttachment> = emptyList(),
    /** The actions an assistant message asked for. */
    val toolCalls: List<AiToolCall> = emptyList(),
    /** For a [AiRole.TOOL] message, the call it answers. */
    val toolCallId: String? = null,
    /** Claude's answer exactly as it came ([AiReply.rawContent]); sent instead of the fields above. */
    val rawContent: String? = null,
)

internal data class AiChatRequest(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val messages: List<AiWireMessage>,
    val timeoutMs: Long = AI_CHAT_TIMEOUT_MS,
    /** `low`, `medium` or `high`; null lets the model decide. */
    val reasoningEffort: String? = null,
    /** The actions the model may ask for; empty sends none. */
    val tools: List<AiTool> = emptyList(),
    val format: AiWireFormat = AiWireFormat.OPENAI_CHAT,
    /** The service's own web search goes with the request: Claude's `web_search` or OpenRouter's server tool. */
    val webSearch: Boolean = false,
)

/** Sends one chat request and returns the reply, or throws [AiChatException]. */
internal fun interface AiChatTransport {
    suspend fun complete(request: AiChatRequest): AiReply

    /**
     * Like [complete], handing [onText] the answer piece by piece while the service writes it; a
     * transport that cannot stream hands it over whole at the end.
     */
    suspend fun stream(
        request: AiChatRequest,
        onText: (String) -> Unit,
    ): AiReply = complete(request).also { onText(it.text) }
}

/** A model a service offers; [free] and [pictures] are only known when the service says so. */
internal data class AiModelInfo(
    val id: String,
    val free: Boolean = false,
    val pictures: Boolean = false,
)

/** Reads the service's model list, or throws [AiChatException]. */
internal fun interface AiModelLister {
    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        format: AiWireFormat,
    ): List<AiModelInfo>
}

/** OpenRouter's results per web search; each search costs the same up to 10. */
private const val OPENROUTER_WEB_RESULTS = 5

/**
 * `{"model": …, "messages": [{"role": …, "content": …}, …]}`, plus `reasoning_effort` when a
 * thinking level is chosen and `tools` when actions are offered. [webSearch] adds OpenRouter's web
 * search server tool, which the model calls when it needs to. No sampling options, which some
 * models reject.
 */
internal fun chatRequestBody(
    model: String,
    messages: List<AiWireMessage>,
    reasoningEffort: String? = null,
    tools: List<AiTool> = emptyList(),
    webSearch: Boolean = false,
): String =
    JSONObject()
        .put("model", model)
        .put("messages", JSONArray().apply { messages.forEach { put(wireMessage(it)) } })
        .apply {
            if (reasoningEffort != null) put("reasoning_effort", reasoningEffort)
            if (tools.isNotEmpty() || webSearch) {
                put(
                    "tools",
                    aiToolsJson(tools).apply {
                        if (webSearch) {
                            put(
                                JSONObject()
                                    .put("type", "openrouter:web_search")
                                    .put("parameters", JSONObject().put("max_results", OPENROUTER_WEB_RESULTS)),
                            )
                        }
                    },
                )
            }
        }.toString()

private fun wireMessage(message: AiWireMessage): JSONObject =
    JSONObject().put("role", message.role.wire).apply {
        if (message.toolCalls.isNotEmpty()) {
            // An assistant message that only asks for actions has no text.
            put("content", message.content.ifEmpty { null } ?: JSONObject.NULL)
            put("tool_calls", aiToolCallsJson(message.toolCalls))
        } else {
            put("content", wireContent(message))
        }
        message.toolCallId?.let { put("tool_call_id", it) }
    }

/**
 * Plain text, or with pictures and PDFs the OpenAI list of parts: the text, then an `image_url`
 * part per picture and a `file` part per PDF, each carrying a base64 `data:` URL.
 */
private fun wireContent(message: AiWireMessage): Any {
    if (message.attachments.isEmpty()) return message.content
    return JSONArray().apply {
        put(JSONObject().put("type", "text").put("text", message.content))
        message.attachments.forEach { file ->
            when (file.kind) {
                AiAttachmentKind.PICTURE -> put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", file.data)))
                AiAttachmentKind.PDF ->
                    put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", file.name).put("file_data", file.data)))
                AiAttachmentKind.TEXT -> Unit
            }
        }
    }
}

/** Every model in an OpenAI-style `{"data": [{"id": …}, …]}` list (Anthropic's has the same shape), sorted by name. */
internal fun parseAiModelList(body: String): List<AiModelInfo> {
    val data =
        try {
            JSONObject(body).optJSONArray("data")
        } catch (error: JSONException) {
            throw AiChatException(AiErrorKind.BAD_REPLY, "The model list was not JSON.", error)
        } ?: throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no model list.")
    return (0 until data.length())
        .mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            // Gemini names its models "models/gemini-…"; the chat endpoint takes the short name.
            val id = model.optString("id").removePrefix("models/").trim()
            if (id.isEmpty()) return@mapNotNull null
            val pricing = model.optJSONObject("pricing")
            val inputs = model.optJSONObject("architecture")?.optJSONArray("input_modalities")
            AiModelInfo(
                id = id,
                free =
                    id.endsWith(":free") || id.endsWith("-free") ||
                        (pricing?.optString("prompt") == "0" && pricing.optString("completion") == "0"),
                pictures = inputs != null && (0 until inputs.length()).any { inputs.optString(it) == "image" },
            )
        }.distinctBy { it.id }
        .sortedBy { it.id.lowercase() }
}

/** Parts of model names that mean the model makes embeddings, speech, pictures or moderation, not chat. */
private val NON_CHAT_MODEL_WORDS =
    listOf(
        "embed",
        "tts",
        "whisper",
        "transcribe",
        "dall-e",
        "imagen",
        "veo-",
        "sora",
        "moderation",
        "realtime",
        "-image",
        "aqa",
        "davinci",
        "babbage",
        "computer-use",
        "audio",
        "safety",
        "guard",
    )

/** OpenCode serves these model families through its other APIs, not `/chat/completions`. */
private val OPENCODE_OTHER_API_PREFIXES = listOf("gpt-", "claude-", "gemini-", "grok-", "muse-", "jev-", "qwen")

/** Whether [id] can answer a chat through this app's `/chat/completions` client. */
internal fun aiModelCanChat(
    provider: AiProvider,
    id: String,
): Boolean {
    val lower = id.lowercase()
    if (NON_CHAT_MODEL_WORDS.any { it in lower }) return false
    val opencode = provider == AiProvider.OPENCODE_ZEN || provider == AiProvider.OPENCODE_GO
    return !(opencode && OPENCODE_OTHER_API_PREFIXES.any { lower.startsWith(it) })
}

/** The first choice's text, actions and cited pages. Some services send content as a list of text parts. */
internal fun parseAiReply(body: String): AiReply {
    val root =
        try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw AiChatException(AiErrorKind.BAD_REPLY, "The reply was not JSON.", error)
        }
    val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
    val text =
        when (val content = message?.opt("content")) {
            is String -> content
            is JSONArray ->
                (0 until content.length()).joinToString("") { index ->
                    content.optJSONObject(index)?.optString("text").orEmpty()
                }
            else -> ""
        }.trim()
    val calls = parseAiToolCalls(message)
    if (text.isEmpty() && calls.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no text.")
    val model = root.optString("model")
    if (aiSafetyCheckerAnswered(model, text)) {
        throw AiChatException(AiErrorKind.NOT_A_CHAT_MODEL, "A safety checker answered instead of a chat model.")
    }
    return AiReply(text, calls, model, sources = urlCitations(message))
}

/**
 * The answer text one streamed event adds: OpenAI's `choices[0].delta.content` (also Gemini's,
 * OpenRouter's and the other OpenAI-style services') or Claude's `text_delta`. Thinking and other
 * events add nothing.
 */
internal fun streamedTextPiece(event: JSONObject): String {
    val delta =
        event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
            ?: event.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }
            ?: return ""
    return when (val content = delta.opt("content") ?: delta.opt("text")) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
        else -> ""
    }
}

/** A safety checker's whole answer, for example "User Safety: safe" and "Response Safety: unsafe". */
private val SAFETY_LABELS = Regex("""^(\s*(User|Response) Safety:\s*\S+)+\s*$""", RegexOption.IGNORE_CASE)

/**
 * Whether a model that only labels text as safe or unsafe answered. OpenRouter's `openrouter/free`
 * picks a free model at random, and for questions with pictures it often picks one of these.
 */
internal fun aiSafetyCheckerAnswered(
    model: String,
    text: String,
): Boolean {
    val lower = model.lowercase()
    return "safety" in lower || "guard" in lower || SAFETY_LABELS.matches(text)
}

/** The service's error message from `{"error": {"message": …}}`, Gemini's `[{"error": …}]`, or null. */
internal fun providerErrorMessage(body: String): String? {
    val parsed = runCatching { JSONTokener(body).nextValue() }.getOrNull()
    val root = (parsed as? JSONArray)?.optJSONObject(0) ?: parsed as? JSONObject ?: return null
    val error = root.opt("error")
    val message =
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> root.optString("message")
        }
    return message.trim().takeIf { it.isNotEmpty() }
}

/**
 * The HTTP status a reply stands for: [code] itself, or, for a 2xx reply that carries only an error
 * object (OpenRouter sends these when a model fails after the request was accepted), the error's
 * own code, or 502 when it has none.
 */
internal fun aiReplyStatus(
    code: Int,
    body: String,
): Int {
    if (code !in 200..299) return code
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return code
    val error = root.optJSONObject("error")
    if (error == null || root.has("choices") || root.has("data")) return code
    return error.optInt("code").takeIf { it in 400..599 } ?: 502
}

/** Words in a 403 that mean the key itself was refused. */
private val KEY_ERROR_WORDS = listOf("key", "auth", "credential", "permission", "unregistered", "forbidden")

/** Words in a refusal that mean the model cannot take the pictures or files that were sent. */
private val ATTACHMENT_ERROR_WORDS = listOf("image", "vision", "multimodal", "modalit", "file", "pdf", "content type")

/** Removes the key from text a service sent back, then shortens it for display. */
internal fun redactApiKey(
    text: String,
    apiKey: String,
): String {
    val redacted = if (apiKey.length >= 4) text.replace(apiKey, "•••") else text
    return if (redacted.length > MAX_AI_ERROR_DETAIL_CHARS) redacted.take(MAX_AI_ERROR_DETAIL_CHARS) + "…" else redacted
}

/**
 * Later GPTs answer 400 "Function tools with reasoning_effort are not supported": the thinking level is
 * the problem, so the actions are not dropped.
 */
private fun thinkingRefusedWithTools(
    code: Int,
    lower: String,
    sentThinking: Boolean,
    sentTools: Boolean,
): AiErrorKind? =
    AiErrorKind.UNSUPPORTED_THINKING.takeIf {
        sentThinking && sentTools && code == 400 && ("reasoning_effort" in lower || "reasoning effort" in lower)
    }

/** A refusal of what came with the question (web search, actions, pictures and files, or a thinking level), or null. */
private fun unsupportedExtra(
    code: Int,
    lower: String,
    sentThinking: Boolean,
    sentAttachments: Boolean,
    sentTools: Boolean,
    sentWebSearch: Boolean,
): AiErrorKind? =
    when {
        // Anthropic answers 400 "web search is not enabled" when an admin turned it off.
        sentWebSearch && code in 400..499 && code !in setOf(401, 402, 429) &&
            ("web search" in lower || "web_search" in lower || "server tool" in lower || "server_tool" in lower) ->
            AiErrorKind.UNSUPPORTED_WEB_SEARCH
        // OpenRouter answers 404 "No endpoints found that support tool use" for such a model.
        sentTools && code in 400..499 && code !in setOf(401, 402, 403, 429) && ("tool" in lower || "function" in lower) ->
            AiErrorKind.UNSUPPORTED_TOOLS
        sentAttachments && code in 400..499 && code !in setOf(401, 403, 429) && ATTACHMENT_ERROR_WORDS.any { it in lower } ->
            AiErrorKind.UNSUPPORTED_ATTACHMENT
        sentThinking && (code == 400 || code == 422) && ("reasoning" in lower || "thinking" in lower || "effort" in lower) ->
            AiErrorKind.UNSUPPORTED_THINKING
        else -> null
    }

internal fun aiErrorKindForStatus(
    code: Int,
    message: String?,
    sentThinking: Boolean = false,
    sentAttachments: Boolean = false,
    sentTools: Boolean = false,
    sentWebSearch: Boolean = false,
): AiErrorKind {
    val lower = message.orEmpty().lowercase()
    (
        thinkingRefusedWithTools(code, lower, sentThinking, sentTools)
            ?: unsupportedExtra(code, lower, sentThinking, sentAttachments, sentTools, sentWebSearch)
    )?.let { return it }
    return when {
        code == 401 -> AiErrorKind.INVALID_KEY
        // OpenRouter also answers 403 for a model it keeps for coding apps; only a key problem names the key.
        code == 403 && (message.isNullOrBlank() || KEY_ERROR_WORDS.any { it in lower }) -> AiErrorKind.INVALID_KEY
        code == 403 -> AiErrorKind.BAD_REQUEST
        code == 402 -> AiErrorKind.NO_CREDIT
        code == 404 -> AiErrorKind.UNKNOWN_MODEL
        code == 429 && ("quota" in lower || "credit" in lower || "billing" in lower) -> AiErrorKind.NO_CREDIT
        code == 429 -> AiErrorKind.RATE_LIMITED
        code == 400 && "api key" in lower -> AiErrorKind.INVALID_KEY
        // Anthropic answers 400 "Your credit balance is too low" for an account without credit.
        code == 400 && ("credit balance" in lower || "billing" in lower) -> AiErrorKind.NO_CREDIT
        code == 400 && "model" in lower -> AiErrorKind.UNKNOWN_MODEL
        code in 400..499 -> AiErrorKind.BAD_REQUEST
        else -> AiErrorKind.SERVER
    }
}

/**
 * A connection that failed or never answered. Every timeout is an [InterruptedIOException]
 * (a socket timeout and the whole-call deadline alike); anything else is a network error.
 */
internal fun aiConnectionFailure(error: IOException): AiChatException {
    if (error is AiChatException) return error
    val detail = listOfNotNull(error.javaClass.simpleName, error.message?.takeIf { it.isNotBlank() }).joinToString(": ")
    val kind = if (error is InterruptedIOException) AiErrorKind.TIMEOUT else AiErrorKind.NETWORK
    return AiChatException(kind, redactApiKey(detail, ""), error)
}

/**
 * Tries IPv4 addresses first, as the caption client does. OkHttp 4 tries addresses one after
 * another, so on a network whose IPv6 route is broken every request waited until it timed out,
 * while the browser and WebView, which race both, worked. IPv6 stays as the fallback.
 */
internal class PreferIpv4Dns(
    private val system: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = preferIpv4Addresses(system.lookup(hostname))
}

/** The client shared by every service: OpenAI-compatible `chat/completions`, or Anthropic's `messages`. */
internal class AiHttpTransport(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(PreferIpv4Dns())
            .connectTimeout(AI_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AI_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AI_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(AI_CHAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) : AiChatTransport,
    AiModelLister,
    AiHttpPoster {
    override suspend fun complete(request: AiChatRequest): AiReply {
        val anthropic = request.format == AiWireFormat.ANTHROPIC_MESSAGES
        val url =
            (if (anthropic) anthropicMessagesUrl(request.baseUrl) else chatCompletionsUrl(request.baseUrl))
                ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val json =
            if (anthropic) {
                anthropicRequestBody(request.model, request.messages, request.reasoningEffort, request.tools, request.webSearch)
            } else {
                chatRequestBody(request.model, request.messages, request.reasoningEffort, request.tools, request.webSearch)
            }
        val httpRequest = authorized(request.apiKey, request.format) { url(url).post(json.toRequestBody(JSON_MEDIA_TYPE)) }
        val (code, reply) = execute(httpRequest, request.timeoutMs, MAX_AI_RESPONSE_BYTES)
        val status = aiReplyStatus(code, reply)
        if (status !in 200..299) {
            val sentAttachments = request.messages.any { it.attachments.isNotEmpty() }
            throw statusFailure(
                status,
                reply,
                request.apiKey,
                request.reasoningEffort != null,
                sentAttachments,
                request.tools.isNotEmpty(),
                request.webSearch,
            )
        }
        return if (anthropic) parseAnthropicReply(reply) else parseAiReply(reply)
    }

    /**
     * Asks with `"stream": true` and reads the server-sent events as they come, so subtitle lines
     * show while the model still writes the rest. A service that answers in one piece anyway is read
     * whole. Used without actions or web search (subtitle translation).
     */
    override suspend fun stream(
        request: AiChatRequest,
        onText: (String) -> Unit,
    ): AiReply {
        val anthropic = request.format == AiWireFormat.ANTHROPIC_MESSAGES
        val url =
            (if (anthropic) anthropicMessagesUrl(request.baseUrl) else chatCompletionsUrl(request.baseUrl))
                ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val body =
            if (anthropic) {
                anthropicRequestBody(request.model, request.messages, request.reasoningEffort, request.tools, request.webSearch)
            } else {
                chatRequestBody(request.model, request.messages, request.reasoningEffort, request.tools, request.webSearch)
            }
        val json = JSONObject(body).put("stream", true).toString()
        val httpRequest =
            authorized(request.apiKey, request.format) {
                url(url).header("Accept", "text/event-stream").post(json.toRequestBody(JSON_MEDIA_TYPE))
            }
        return call(httpRequest, request.timeoutMs) { response ->
            val events = response.body?.contentType()?.subtype == "event-stream"
            if (response.code in 200..299 && events) return@call readEvents(response, request.apiKey, onText)
            val (code, reply) = readReply(response, MAX_AI_RESPONSE_BYTES)
            val status = aiReplyStatus(code, reply)
            if (status !in 200..299) {
                throw statusFailure(status, reply, request.apiKey, request.reasoningEffort != null, sentAttachments = false)
            }
            (if (anthropic) parseAnthropicReply(reply) else parseAiReply(reply)).also { onText(it.text) }
        }
    }

    /** Reads `data: {…}` events until `[DONE]` or the end, handing each piece of text to [onText]. */
    private fun readEvents(
        response: Response,
        apiKey: String,
        onText: (String) -> Unit,
    ): AiReply {
        val source = checkNotNull(response.body).source()
        val text = StringBuilder()
        var model = ""
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val event =
                try {
                    JSONObject(data)
                } catch (_: JSONException) {
                    continue
                }
            // OpenRouter and Claude report a failure after the start as an event of its own.
            val status = aiReplyStatus(200, data)
            if (status !in 200..299 || event.optString("type") == "error") {
                throw statusFailure(status.takeIf { it !in 200..299 } ?: 502, data, apiKey, sentThinking = false, sentAttachments = false)
            }
            model = event.optString("model").ifEmpty { event.optJSONObject("message")?.optString("model").orEmpty() }.ifEmpty { model }
            val piece = streamedTextPiece(event)
            if (piece.isEmpty()) continue
            text.append(piece)
            if (text.length > MAX_AI_RESPONSE_BYTES) {
                throw AiChatException(AiErrorKind.BAD_REPLY, "The reply was larger than ${MAX_AI_RESPONSE_BYTES / (1024 * 1024)} MiB.")
            }
            onText(piece)
        }
        val reply = text.toString().trim()
        if (reply.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no text.")
        if (aiSafetyCheckerAnswered(model, reply)) {
            throw AiChatException(AiErrorKind.NOT_A_CHAT_MODEL, "A safety checker answered instead of a chat model.")
        }
        return AiReply(reply, model = model)
    }

    /** A search request (YouTube, Tavily, OpenAI's search model): the status and body, whatever the status. */
    override suspend fun post(request: AiHttpPost): Pair<Int, String> {
        val httpRequest =
            try {
                Request
                    .Builder()
                    .url(request.url)
                    .header("Accept", "application/json")
                    .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                    .post(request.body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            } catch (error: IllegalArgumentException) {
                throw AiChatException(AiErrorKind.INVALID_KEY, "The key has characters an API key cannot have.", error)
            }
        return execute(httpRequest, request.timeoutMs, request.maxBytes)
    }

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        format: AiWireFormat,
    ): List<AiModelInfo> {
        val listUrl = modelsUrl(baseUrl) ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        // Anthropic sends 20 models a page unless asked for more.
        val url =
            if (format ==
                AiWireFormat.ANTHROPIC_MESSAGES
            ) {
                listUrl.newBuilder().addQueryParameter("limit", "1000").build()
            } else {
                listUrl
            }
        val (code, reply) = execute(authorized(apiKey, format) { url(url).get() }, AI_TEST_TIMEOUT_MS, MAX_AI_MODEL_LIST_BYTES)
        val status = aiReplyStatus(code, reply)
        if (status !in 200..299) throw statusFailure(status, reply, apiKey, sentThinking = false, sentAttachments = false)
        return parseAiModelList(reply)
    }

    private fun authorized(
        apiKey: String,
        format: AiWireFormat,
        target: Request.Builder.() -> Request.Builder,
    ): Request =
        try {
            Request
                .Builder()
                .apply {
                    if (format == AiWireFormat.ANTHROPIC_MESSAGES) {
                        header("x-api-key", apiKey)
                        header("anthropic-version", ANTHROPIC_API_VERSION)
                    } else {
                        header("Authorization", "Bearer $apiKey")
                    }
                }.header("Accept", "application/json")
                .target()
                .build()
        } catch (error: IllegalArgumentException) {
            // OkHttp refuses header characters outside printable ASCII; such a key cannot be valid.
            throw AiChatException(AiErrorKind.INVALID_KEY, "The key has characters an API key cannot have.", error)
        }

    private fun statusFailure(
        code: Int,
        body: String,
        apiKey: String,
        sentThinking: Boolean,
        sentAttachments: Boolean,
        sentTools: Boolean = false,
        sentWebSearch: Boolean = false,
    ): AiChatException {
        val message = providerErrorMessage(body)?.let { redactApiKey(it, apiKey) }
        return AiChatException(
            aiErrorKindForStatus(code, message, sentThinking, sentAttachments, sentTools, sentWebSearch),
            message ?: "HTTP $code",
        )
    }

    private suspend fun execute(
        request: Request,
        timeoutMs: Long,
        maxBytes: Int,
    ): Pair<Int, String> = call(request, timeoutMs) { readReply(it, maxBytes) }

    /** Sends [request] and reads its response with [read] on OkHttp's thread; cancelling the caller cancels the call. */
    private suspend fun <T> call(
        request: Request,
        timeoutMs: Long,
        read: (Response) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(aiConnectionFailure(error))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // Reading the body can still time out or lose the connection.
                        val result = runCatching { response.use(read) }
                        if (continuation.isActive) {
                            result.fold(
                                { continuation.resume(it) },
                                { continuation.resumeWithException(if (it is IOException) aiConnectionFailure(it) else it) },
                            )
                        }
                    }
                },
            )
        }

    private fun readReply(
        response: Response,
        maxBytes: Int,
    ): Pair<Int, String> {
        val body = response.body ?: return response.code to ""
        return try {
            response.code to body.byteStream().use { readUtf8WithLimit(it, maxBytes) }
        } catch (error: ResponseLimitExceededException) {
            throw AiChatException(AiErrorKind.BAD_REPLY, "The reply was larger than ${maxBytes / (1024 * 1024)} MiB.", error)
        }
    }
}
