package com.kienhoang.dualsubreplay.assistant

import com.kienhoang.dualsubreplay.data.AiSubtitlePart
import com.kienhoang.dualsubreplay.data.AiSubtitleReplyException
import com.kienhoang.dualsubreplay.data.aiSubtitleRequestBody
import com.kienhoang.dualsubreplay.data.parseAiSubtitleReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/**
 * The models asked, in turn: Flash-Lite answered a 3-minute part in 6–9 s with timing as good as
 * Flash in the 2026-11-08 test, while gemini-flash-latest took over 30 s. The next model is asked
 * when one is busy, unknown or out of its free limit (each model has its own).
 */
internal val AI_SUBTITLE_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-flash-latest")

/** One 5-minute part; Flash took 36 s for a whole 9-minute video. */
internal const val AI_SUBTITLE_TIMEOUT_MS = 120_000L

private const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

/** Why subtitles could not be made; each has its own message under the button. */
enum class AiSubtitleProblem {
    NO_KEY,

    /** Private, age-restricted, removed, or otherwise not open to Gemini. */
    VIDEO_UNAVAILABLE,

    /** Every key ran into its free limit with every model. */
    LIMIT,
    BUSY,
    NETWORK,

    /** Gemini would not write them (for example a song's lyrics). */
    REFUSED,

    /** Every part came back without a line. */
    NO_SPEECH,
    FAILED,
}

internal class AiSubtitleException(
    val problem: AiSubtitleProblem,
    detail: String? = null,
    cause: Throwable? = null,
) : IOException(detail ?: problem.name, cause)

/** What asking for one part gave. */
internal sealed interface AiPartAnswer {
    data class Written(
        val part: AiSubtitlePart,
    ) : AiPartAnswer

    /** The part starts after the video's end: Gemini answers 400 for it. */
    data object PastTheEnd : AiPartAnswer
}

/** The AI assistant's saved Gemini keys. */
internal interface AiSubtitleKeys {
    suspend fun ids(): List<String>

    suspend fun key(id: String): String?
}

/** Gemini's keys from Settings → AI assistant, whichever service the assistant uses now. */
internal class StoredGeminiKeys(
    private val store: AiKeyStore,
) : AiSubtitleKeys {
    override suspend fun ids(): List<String> = withContext(Dispatchers.IO) { store.keys(AiProvider.GEMINI).map { it.id } }

    override suspend fun key(id: String): String? =
        withContext(Dispatchers.IO) { (store.load(AiProvider.GEMINI, id) as? StoredAiKey.Found)?.key }
}

/**
 * Asks Gemini for the subtitles of one part of a YouTube video, giving it the video's address
 * (Gemini listens to public videos itself). Keys take turns through [pacer], shared with subtitle
 * translation; a key that is refused or out of its limit hands the part to the next key, and a
 * model that is busy, unknown or out of its limit hands it to the next model.
 */
internal class AiVideoSubtitleMaker(
    private val keys: AiSubtitleKeys,
    private val poster: AiHttpPoster,
    private val pacer: AiKeyPacer = AiKeyPacer.Shared,
    private val models: List<String> = AI_SUBTITLE_MODELS,
    private val busyWaitsMs: List<Long> = listOf(2_000L, 5_000L),
) {
    suspend fun part(
        videoId: String,
        startMs: Long,
        endMs: Long,
    ): AiPartAnswer {
        val ids = keys.ids()
        if (ids.isEmpty()) throw AiSubtitleException(AiSubtitleProblem.NO_KEY)
        val body = aiSubtitleRequestBody(videoId, startMs, endMs)
        var failure = AiSubtitleException(AiSubtitleProblem.LIMIT, "Every Gemini key is resting after its limit.")
        for (model in models) {
            val answer =
                try {
                    withEachKey(ids, model, body, startMs, endMs)
                } catch (problem: AiSubtitleException) {
                    if (problem.problem !in NEXT_MODEL_PROBLEMS) throw problem
                    failure = problem
                    null
                }
            if (answer != null) return answer
        }
        throw failure
    }

    /** The answer of the first key that gives one, or null when none could (the next model is asked). */
    private suspend fun withEachKey(
        ids: List<String>,
        model: String,
        body: String,
        startMs: Long,
        endMs: Long,
    ): AiPartAnswer? {
        val pacedIds = ids.map { "${AiProvider.GEMINI.key}/$it" }
        var lastKeyProblem: AiSubtitleException? = null
        repeat(ids.size) {
            val paced = pacer.take(pacedIds) ?: throw lastKeyProblem ?: AiSubtitleException(AiSubtitleProblem.LIMIT)
            val id = ids[pacedIds.indexOf(paced)]
            val key = keys.key(id)
            if (key == null) {
                pacer.rejected(paced)
                return@repeat
            }
            when (val result = askWhenBusy(model, key, body, startMs, endMs)) {
                is KeyResult.Answer -> {
                    pacer.answered(paced)
                    return result.answer
                }
                is KeyResult.KeyRefused -> {
                    pacer.rejected(paced)
                    lastKeyProblem = AiSubtitleException(AiSubtitleProblem.NO_KEY, result.detail)
                }
                is KeyResult.Limited -> lastKeyProblem = AiSubtitleException(AiSubtitleProblem.LIMIT, result.detail)
            }
        }
        throw lastKeyProblem ?: AiSubtitleException(AiSubtitleProblem.LIMIT)
    }

    private sealed interface KeyResult {
        data class Answer(
            val answer: AiPartAnswer,
        ) : KeyResult

        data class KeyRefused(
            val detail: String?,
        ) : KeyResult

        data class Limited(
            val detail: String?,
        ) : KeyResult
    }

    private suspend fun askWhenBusy(
        model: String,
        key: String,
        body: String,
        startMs: Long,
        endMs: Long,
    ): KeyResult {
        val waits = busyWaitsMs.iterator()
        while (true) {
            val request =
                AiHttpPost(
                    url = "$GEMINI_MODELS_URL$model:generateContent".toHttpUrl(),
                    body = body,
                    headers = mapOf("x-goog-api-key" to key),
                    timeoutMs = AI_SUBTITLE_TIMEOUT_MS,
                )
            val (code, reply) =
                try {
                    poster.post(request)
                } catch (error: AiChatException) {
                    when (error.kind) {
                        // A slow model: the next one may be quicker.
                        AiErrorKind.TIMEOUT -> throw AiSubtitleException(AiSubtitleProblem.BUSY, error.detail, error)
                        AiErrorKind.INVALID_KEY -> return KeyResult.KeyRefused(error.detail)
                        else -> throw AiSubtitleException(AiSubtitleProblem.NETWORK, error.detail, error)
                    }
                }
            val detail = providerErrorMessage(reply)?.let { redactApiKey(it, key) }
            when (aiSubtitleStatus(code, detail, startMs)) {
                StatusMeaning.OK -> return KeyResult.Answer(written(reply, startMs, endMs))
                StatusMeaning.PAST_THE_END -> return KeyResult.Answer(AiPartAnswer.PastTheEnd)
                StatusMeaning.BAD_KEY -> return KeyResult.KeyRefused(detail)
                StatusMeaning.LIMITED -> return KeyResult.Limited(detail)
                StatusMeaning.VIDEO_UNAVAILABLE -> throw AiSubtitleException(AiSubtitleProblem.VIDEO_UNAVAILABLE, detail)
                StatusMeaning.UNKNOWN_MODEL -> throw AiSubtitleException(AiSubtitleProblem.FAILED, detail ?: "HTTP $code")
                StatusMeaning.BUSY ->
                    if (waits.hasNext()) {
                        delay(waits.next())
                    } else {
                        throw AiSubtitleException(AiSubtitleProblem.BUSY, detail ?: "HTTP $code")
                    }
            }
        }
    }

    private fun written(
        reply: String,
        startMs: Long,
        endMs: Long,
    ): AiPartAnswer =
        try {
            AiPartAnswer.Written(parseAiSubtitleReply(reply, startMs, endMs))
        } catch (error: AiSubtitleReplyException) {
            throw AiSubtitleException(if (error.refused) AiSubtitleProblem.REFUSED else AiSubtitleProblem.FAILED, error.message, error)
        }
}

/** Problems another model may not have: its own limit, its own load, or it does not exist (any more). */
private val NEXT_MODEL_PROBLEMS =
    setOf(AiSubtitleProblem.LIMIT, AiSubtitleProblem.BUSY, AiSubtitleProblem.FAILED, AiSubtitleProblem.REFUSED)

internal enum class StatusMeaning {
    OK,
    PAST_THE_END,
    BAD_KEY,
    LIMITED,
    VIDEO_UNAVAILABLE,
    UNKNOWN_MODEL,
    BUSY,
}

/**
 * What Gemini's HTTP status means for a part starting at [startMs] (2026-11-08 test): 400 for a
 * part after the video's end, 403 "The caller does not have permission" for a video it cannot open,
 * and 400 or 403 naming the API key for a bad key.
 */
internal fun aiSubtitleStatus(
    code: Int,
    message: String?,
    startMs: Long,
): StatusMeaning {
    val lower = message.orEmpty().lowercase()
    val namesKey = "api key" in lower || "api_key" in lower
    return when {
        code in 200..299 -> StatusMeaning.OK
        code == 401 || (code in setOf(400, 403) && namesKey) -> StatusMeaning.BAD_KEY
        code == 403 -> StatusMeaning.VIDEO_UNAVAILABLE
        code == 400 && startMs > 0 -> StatusMeaning.PAST_THE_END
        code == 400 && ("mime type" in lower || "video" in lower || "file" in lower) -> StatusMeaning.VIDEO_UNAVAILABLE
        code == 429 -> StatusMeaning.LIMITED
        code == 404 || code == 400 -> StatusMeaning.UNKNOWN_MODEL
        code >= 500 -> StatusMeaning.BUSY
        else -> StatusMeaning.UNKNOWN_MODEL
    }
}
