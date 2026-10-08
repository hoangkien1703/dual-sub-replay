package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.data.ResponseLimitExceededException
import com.kienhoang.dualsubreplay.data.readUtf8WithLimit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_AI_RESPONSE_BYTES = 1024 * 1024
private const val AI_CONNECT_TIMEOUT_MS = 15_000L
private const val AI_READ_TIMEOUT_MS = 90_000L
private const val AI_CALL_TIMEOUT_MS = 120_000L
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
    BAD_REPLY,
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
)

internal data class AiChatRequest(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val messages: List<AiWireMessage>,
)

/** Sends one chat request and returns the reply text, or throws [AiChatException]. */
internal fun interface AiChatTransport {
    suspend fun complete(request: AiChatRequest): String
}

/** `{"model": …, "messages": [{"role": …, "content": …}, …]}`; no sampling options, which some models reject. */
internal fun chatRequestBody(
    model: String,
    messages: List<AiWireMessage>,
): String =
    JSONObject()
        .put("model", model)
        .put(
            "messages",
            JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
            },
        ).toString()

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

/** Removes the key from text a service sent back, then shortens it for display. */
internal fun redactApiKey(
    text: String,
    apiKey: String,
): String {
    val redacted = if (apiKey.length >= 4) text.replace(apiKey, "•••") else text
    return if (redacted.length > MAX_AI_ERROR_DETAIL_CHARS) redacted.take(MAX_AI_ERROR_DETAIL_CHARS) + "…" else redacted
}

internal fun aiErrorKindForStatus(
    code: Int,
    message: String?,
): AiErrorKind {
    val lower = message.orEmpty().lowercase()
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

/** The OpenAI-compatible `chat/completions` client shared by every service. */
internal class OpenAiCompatibleTransport(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(AI_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AI_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(AI_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) : AiChatTransport {
    override suspend fun complete(request: AiChatRequest): String {
        val url = chatCompletionsUrl(request.baseUrl) ?: throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val httpRequest =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${request.apiKey}")
                .header("Accept", "application/json")
                .post(chatRequestBody(request.model, request.messages).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val (code, body) = execute(httpRequest)
        if (code !in 200..299) {
            val message = providerErrorMessage(body)?.let { redactApiKey(it, request.apiKey) }
            throw AiChatException(aiErrorKindForStatus(code, message), message ?: "HTTP $code")
        }
        return parseChatReply(body)
    }

    private suspend fun execute(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        error: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(AiChatException(AiErrorKind.NETWORK, error.javaClass.simpleName, error))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result = runCatching { response.use(::readReply) }
                        if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                },
            )
        }

    private fun readReply(response: Response): Pair<Int, String> {
        val body = response.body ?: return response.code to ""
        return try {
            response.code to body.byteStream().use { readUtf8WithLimit(it, MAX_AI_RESPONSE_BYTES) }
        } catch (error: ResponseLimitExceededException) {
            throw AiChatException(AiErrorKind.BAD_REPLY, "The reply was larger than 1 MiB.", error)
        }
    }
}
