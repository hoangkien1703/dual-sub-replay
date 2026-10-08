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
}

internal data class AiWireMessage(
    val role: AiRole,
    val content: String,
    /** Pictures and PDFs; text files are part of [content]. */
    val attachments: List<AiAttachment> = emptyList(),
)

internal data class AiChatRequest(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val messages: List<AiWireMessage>,
    val timeoutMs: Long = AI_CHAT_TIMEOUT_MS,
    /** `low`, `medium` or `high`; null lets the model decide. */
    val reasoningEffort: String? = null,
)

/** Sends one chat request and returns the reply text, or throws [AiChatException]. */
internal fun interface AiChatTransport {
    suspend fun complete(request: AiChatRequest): String
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
    ): List<AiModelInfo>
}

/**
 * `{"model": …, "messages": [{"role": …, "content": …}, …]}`, plus `reasoning_effort` when a
 * thinking level is chosen. No sampling options, which some models reject.
 */
internal fun chatRequestBody(
    model: String,
    messages: List<AiWireMessage>,
    reasoningEffort: String? = null,
): String =
    JSONObject()
        .put("model", model)
        .put(
            "messages",
            JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role.wire).put("content", wireContent(it))) }
            },
        ).apply { if (reasoningEffort != null) put("reasoning_effort", reasoningEffort) }
        .toString()

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

/** Every model in an OpenAI-style `{"data": [{"id": …}, …]}` list, sorted by name. */
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

/** The first choice's text. Some services send content as a list of text parts. */
internal fun parseChatReply(body: String): String {
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
    if (text.isEmpty()) throw AiChatException(AiErrorKind.BAD_REPLY, "The reply had no text.")
    return text
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

/** A refusal of what came with the question (pictures and files, or a thinking level), or null. */
private fun unsupportedExtra(
    code: Int,
    lower: String,
    sentThinking: Boolean,
    sentAttachments: Boolean,
): AiErrorKind? =
    when {
        sentAttachments && code in 400..499 && code !in setOf(401, 403, 429) && ATTACHMENT_ERROR_WORDS.any { it in lower } ->
            AiErrorKind.UNSUPPORTED_ATTACHMENT
        sentThinking && (code == 400 || code == 422) && ("reasoning" in lower || "thinking" in lower) -> AiErrorKind.UNSUPPORTED_THINKING
        else -> null
    }

internal fun aiErrorKindForStatus(
    code: Int,
    message: String?,
    sentThinking: Boolean = false,
    sentAttachments: Boolean = false,
): AiErrorKind {
    val lower = message.orEmpty().lowercase()
    unsupportedExtra(code, lower, sentThinking, sentAttachments)?.let { return it }
    return when {
        code == 401 || code == 403 -> AiErrorKind.INVALID_KEY
        code == 402 -> AiErrorKind.NO_CREDIT
        code == 404 -> AiErrorKind.UNKNOWN_MODEL
        code == 429 && ("quota" in lower || "credit" in lower || "billing" in lower) -> AiErrorKind.NO_CREDIT
        code == 429 -> AiErrorKind.RATE_LIMITED
        code == 400 && "api key" in lower -> AiErrorKind.INVALID_KEY
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

/** The OpenAI-compatible `chat/completions` client shared by every service. */
internal class OpenAiCompatibleTransport(
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
    AiModelLister {
    override suspend fun complete(request: AiChatRequest): String {
        val url = chatCompletionsUrl(request.baseUrl) ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val body = chatRequestBody(request.model, request.messages, request.reasoningEffort).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = authorized(request.apiKey) { url(url).post(body) }
        val (code, reply) = execute(httpRequest, request.timeoutMs, MAX_AI_RESPONSE_BYTES)
        val status = aiReplyStatus(code, reply)
        if (status !in 200..299) {
            val sentAttachments = request.messages.any { it.attachments.isNotEmpty() }
            throw statusFailure(status, reply, request.apiKey, request.reasoningEffort != null, sentAttachments)
        }
        return parseChatReply(reply)
    }

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String,
    ): List<AiModelInfo> {
        val url = modelsUrl(baseUrl) ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val (code, reply) = execute(authorized(apiKey) { url(url).get() }, AI_TEST_TIMEOUT_MS, MAX_AI_MODEL_LIST_BYTES)
        val status = aiReplyStatus(code, reply)
        if (status !in 200..299) throw statusFailure(status, reply, apiKey, sentThinking = false, sentAttachments = false)
        return parseAiModelList(reply)
    }

    private fun authorized(
        apiKey: String,
        target: Request.Builder.() -> Request.Builder,
    ): Request =
        try {
            Request
                .Builder()
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
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
    ): AiChatException {
        val message = providerErrorMessage(body)?.let { redactApiKey(it, apiKey) }
        return AiChatException(aiErrorKindForStatus(code, message, sentThinking, sentAttachments), message ?: "HTTP $code")
    }

    private suspend fun execute(
        request: Request,
        timeoutMs: Long,
        maxBytes: Int,
    ): Pair<Int, String> =
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
                        val result = runCatching { response.use { readReply(it, maxBytes) } }
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
