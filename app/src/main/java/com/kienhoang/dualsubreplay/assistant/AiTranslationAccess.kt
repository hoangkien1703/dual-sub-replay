package com.kienhoang.dualsubreplay.assistant

import android.content.Context
import com.kienhoang.dualsubreplay.translation.AiTranslationModel
import com.kienhoang.dualsubreplay.translation.aiTranslationJsonSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A subtitle request waits this long for the model; a window of sentences takes a few seconds. */
internal const val AI_TRANSLATION_TIMEOUT_MS = 45_000L

/** Settings → Translation → AI model for translation; empty uses [aiTranslationDefaultModel]. */
internal const val AI_TRANSLATION_MODEL_PREFERENCE = "ai_translation_model"

/**
 * Free keys allow about 15 requests a minute each (Gemini's free tier), so one key is asked at most
 * this often (10 a minute, well under the limit). With several keys the requests take turns, so each key is asked less.
 */
internal const val AI_TRANSLATION_KEY_INTERVAL_MS = 6_000L

/**
 * The most requests one key is sent in any minute, whoever asks (AiRequestBudgetTest). A word the
 * learner tapped is not held to [AI_TRANSLATION_KEY_INTERVAL_MS], only to this.
 */
internal const val AI_TRANSLATION_KEY_REQUESTS_PER_MINUTE = 10

/**
 * Of a key's [AI_TRANSLATION_KEY_REQUESTS_PER_MINUTE], this many in any minute are kept for words the
 * learner taps: background requests (subtitle windows, Prepare meanings ahead) never take them. Without
 * this, background requests at [AI_TRANSLATION_KEY_INTERVAL_MS] filled the whole minute, and a tapped
 * word waited for the oldest of them to leave it (lion737, Discussion #81, 2026-11-20: about 22 s with
 * Prepare meanings ahead on).
 */
internal const val AI_TRANSLATION_KEY_URGENT_RESERVE = 4

private const val PACER_MINUTE_MS = 60_000L

/** A key that answered "too many requests" or "quota" rests this long, doubling while it keeps refusing. */
internal const val AI_TRANSLATION_KEY_REST_MS = 60_000L
internal const val AI_TRANSLATION_KEY_MAX_REST_MS = 30 * 60_000L

/** A key the service rejects is not asked again for this long (or until the app restarts). */
internal const val AI_TRANSLATION_BAD_KEY_REST_MS = 6 * 60 * 60_000L

private val REST_PROBLEMS = setOf(AiErrorKind.RATE_LIMITED, AiErrorKind.NO_CREDIT)

/** How a service answers a `response_format` it does not take: 400, sometimes naming the model. */
private val FORMAT_REFUSALS = setOf(AiErrorKind.BAD_REQUEST, AiErrorKind.UNKNOWN_MODEL)
private val BAD_KEY_PROBLEMS = setOf(AiErrorKind.NO_KEY, AiErrorKind.KEY_UNREADABLE, AiErrorKind.INVALID_KEY)

/**
 * The model subtitles are translated with when none is chosen: the AI assistant's own model (owner
 * request 2026-10-28; Gemini's default is already the fast Flash-Lite). Thinking is off for
 * translation unless [AiTranslationThinking] says otherwise, so a thinking model still answers quickly.
 */
internal fun aiTranslationDefaultModel(settings: AiAssistantSettings): String = settings.modelFor(settings.provider)

/** The models offered besides "Same as AI assistant": the service's suggestions. */
internal fun aiTranslationModelChoices(settings: AiAssistantSettings): List<String> =
    settings.provider.suggestedModels
        .filter(String::isNotBlank)
        .distinct()

/** The chosen model when it is still offered by the current service, else the assistant's. */
internal fun aiTranslationModelName(
    settings: AiAssistantSettings,
    chosen: String?,
): String = chosen?.takeIf { it in aiTranslationModelChoices(settings) } ?: aiTranslationDefaultModel(settings)

/** Settings → Translation → AI thinking for translation; missing means [AiTranslationThinking.OFF]. */
internal const val AI_TRANSLATION_THINKING_PREFERENCE = "ai_translation_thinking"

/**
 * How long the model thinks before it translates subtitles (owner request 2026-11-02). [OFF] is the
 * default: subtitles need speed and free limits count thinking tokens. High is left out on purpose:
 * a window of up to 60 sentences could then run past [AI_TRANSLATION_TIMEOUT_MS], and Google would
 * translate instead.
 */
enum class AiTranslationThinking(
    val key: String,
) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
}

internal fun storedAiTranslationThinking(raw: String?): AiTranslationThinking =
    AiTranslationThinking.entries.firstOrNull { it.key == raw } ?: AiTranslationThinking.OFF

/**
 * The thinking levels [provider] lets the app choose for translation; empty hides the setting. Only
 * Gemini and OpenAI both turn thinking off and take low and medium through `reasoning_effort`.
 * Claude's effort is not thinking (no effort already means its full effort), and the other services
 * pick their own, so their models translate as they come.
 */
internal fun aiTranslationThinkingLevels(provider: AiProvider): List<AiTranslationThinking> =
    when (provider) {
        AiProvider.GEMINI, AiProvider.OPENAI -> AiTranslationThinking.entries
        else -> emptyList()
    }

/**
 * The `reasoning_effort` a translation request carries. Off turns thinking off where the service
 * allows it; the other levels are sent as they are.
 */
internal fun aiTranslationEffort(
    provider: AiProvider,
    model: String,
    thinking: AiTranslationThinking = AiTranslationThinking.OFF,
): String? =
    when {
        thinking !in aiTranslationThinkingLevels(provider) -> null
        thinking != AiTranslationThinking.OFF -> thinking.key
        provider == AiProvider.GEMINI -> "none"
        else -> openAiNoThinkingEffort(model)
    }

/**
 * The `response_format` a translation request carries: Gemini and OpenAI both take a JSON schema
 * on the chat endpoint, so their replies always parse. Other services get none, since not every
 * model behind OpenRouter or a custom address takes it.
 */
internal fun aiTranslationResponseFormat(provider: AiProvider): String? =
    when (provider) {
        AiProvider.GEMINI, AiProvider.OPENAI -> aiTranslationJsonSchema()
        else -> null
    }

/**
 * The translation cache's name for [model]: the service and model, plus the thinking level when one
 * is on, so a new level translates again and Off keeps the translations saved before levels existed.
 */
internal fun aiTranslationScope(
    provider: AiProvider,
    model: String,
    thinking: AiTranslationThinking,
): String {
    val base = "${provider.key}/$model"
    val level = thinking.takeIf { it != AiTranslationThinking.OFF && it in aiTranslationThinkingLevels(provider) }
    return if (level == null) base else "$base+thinking-${level.key}"
}

/**
 * The model subtitles are translated with: the service and keys chosen in Settings → AI
 * assistant, and the model chosen in Settings → Translation (owner request 2026-10-28). Null when
 * that service has no key, so Google translates.
 */
internal fun aiTranslationModel(
    settings: AiAssistantSettings,
    chosenModel: String?,
    keyStore: AiKeyStore,
    transport: AiChatTransport,
    pacer: AiKeyPacer = AiKeyPacer.Shared,
    thinking: AiTranslationThinking = AiTranslationThinking.OFF,
    urgent: Boolean = false,
): AiTranslationModel? {
    val provider = settings.provider
    val model = aiTranslationModelName(settings, chosenModel)
    if (keyStore.keys(provider).isEmpty() || model.isBlank()) return null
    return KeyedAiTranslationModel(provider, model, thinking, settings.baseUrlFor(provider), keyStore, transport, pacer, urgent)
}

/**
 * Reads the AI assistant's settings and keys from the app's own storage. An [urgent] model's
 * requests go ahead of background ones (see [AiKeyPacer]): only for what the learner just tapped.
 */
internal fun Context.aiTranslationModel(
    chosenModel: String?,
    thinking: AiTranslationThinking,
    transport: AiChatTransport,
    urgent: Boolean = false,
): AiTranslationModel? =
    aiTranslationModel(
        readAiAssistantSettings(getSharedPreferences(AI_SETTINGS_PREFERENCES, 0)),
        chosenModel,
        AiKeyStore(SharedPreferencesSecretStorage(getSharedPreferences(AI_KEYS_PREFERENCES, 0)), KeystoreSecretCipher()),
        transport,
        thinking = thinking,
        urgent = urgent,
    )

/** The thinking levels the AI assistant's current service offers for translation. */
internal fun Context.aiTranslationThinkingLevels(): List<AiTranslationThinking> =
    aiTranslationThinkingLevels(readAiAssistantSettings(getSharedPreferences(AI_SETTINGS_PREFERENCES, 0)).provider)

/** The AI assistant's model, then the other translation models its current service offers (without it again). */
internal fun Context.aiTranslationModelChoices(): Pair<String, List<String>> {
    val settings = readAiAssistantSettings(getSharedPreferences(AI_SETTINGS_PREFERENCES, 0))
    val assistant = aiTranslationDefaultModel(settings)
    return assistant to aiTranslationModelChoices(settings).filter { it != assistant }
}

/**
 * When each key was last asked and how long it rests, kept for the whole app run so a new video does
 * not start by asking a key that just ran out. Requests take turns over the keys: the key asked
 * longest ago goes first.
 *
 * Background requests (subtitle windows, the other words of a line, Prepare meanings ahead) keep
 * [intervalMs] apart. An urgent request, a word the learner just tapped, goes at once and ahead of
 * every background request still waiting (lion737, Discussion #81, 2026-11-16: the meaning waited up
 * to 6 s behind the line's own request). Both stay within [perMinute] requests in any minute, and
 * background requests leave [urgentReserve] of them for urgent ones.
 */
internal class AiKeyPacer(
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = AI_TRANSLATION_KEY_INTERVAL_MS,
    private val perMinute: Int = AI_TRANSLATION_KEY_REQUESTS_PER_MINUTE,
    private val urgentReserve: Int = AI_TRANSLATION_KEY_URGENT_RESERVE,
) {
    private class KeyState {
        /** The newest send times (an urgent request's may be just ahead), oldest first, at most perMinute of them. */
        val sent = ArrayDeque<Long>()
        var turn = 0L
        var restUntil = Long.MIN_VALUE / 2
        var nextRestMs = AI_TRANSLATION_KEY_REST_MS
    }

    private val keys = HashMap<String, KeyState>()
    private var turns = 0L
    private val lock = Mutex()

    /**
     * Picks the key to ask now among [ids], waiting for its turn; null when every key rests. An
     * [urgent] request takes the next free moment at once; a background one that waited checks again,
     * so an urgent request that went meanwhile pushes it back.
     */
    suspend fun take(
        ids: List<String>,
        urgent: Boolean = false,
    ): String? {
        while (true) {
            val (id, waitMs) =
                lock.withLock {
                    val time = now()
                    val ready = ids.filter { state(it).restUntil <= time }
                    if (ready.isEmpty()) return null
                    val id =
                        if (urgent) {
                            ready.minWith(compareBy<String> { earliest(state(it), time, urgent = true) }.thenBy { state(it).turn })
                        } else {
                            ready.minBy { state(it).turn }
                        }
                    val state = state(id)
                    val at = earliest(state, time, urgent)
                    // An urgent request keeps its moment; a background one only takes a moment that has come.
                    if (urgent || at <= time) {
                        state.sent.addLast(at)
                        while (state.sent.size > perMinute) state.sent.removeFirst()
                        state.turn = ++turns
                    }
                    id to (at - time)
                }
            if (waitMs > 0) delay(waitMs)
            if (urgent || waitMs <= 0) return id
        }
    }

    /** The first moment [state]'s key may be sent another request. */
    private fun earliest(
        state: KeyState,
        time: Long,
        urgent: Boolean,
    ): Long {
        val last = state.sent.lastOrNull() ?: return time
        // Urgent requests may use the whole minute; background ones leave the reserve free.
        val cap = if (urgent) perMinute else (perMinute - urgentReserve).coerceAtLeast(1)
        val minuteFree = if (state.sent.size < cap) time else state.sent[state.sent.size - cap] + PACER_MINUTE_MS
        return maxOf(time, minuteFree, if (urgent) last else last + intervalMs)
    }

    /** The key answered: it may rest only briefly next time. */
    suspend fun answered(id: String) = lock.withLock { state(id).nextRestMs = AI_TRANSLATION_KEY_REST_MS }

    /** The key ran into its limit: it rests, longer each time in a row. */
    suspend fun limited(id: String) =
        lock.withLock {
            val state = state(id)
            state.restUntil = now() + state.nextRestMs
            state.nextRestMs = (state.nextRestMs * 2).coerceAtMost(AI_TRANSLATION_KEY_MAX_REST_MS)
        }

    /** The service rejected the key. */
    suspend fun rejected(id: String) = lock.withLock { state(id).restUntil = now() + AI_TRANSLATION_BAD_KEY_REST_MS }

    /**
     * "Try AI again": every resting key may be asked at once. Without this the button only cleared the
     * translator's own pause, and with every key still resting (up to 30 minutes) it failed again at once.
     */
    suspend fun wakeAll() =
        lock.withLock {
            keys.values.forEach { state ->
                state.restUntil = Long.MIN_VALUE / 2
                state.nextRestMs = AI_TRANSLATION_KEY_REST_MS
            }
        }

    private fun state(id: String) = keys.getOrPut(id) { KeyState() }

    companion object {
        val Shared = AiKeyPacer()
    }
}

/**
 * The thinking settings and JSON schemas a service's model refused, for the whole app run. Each
 * tapped word gets a new model, and each used to send the refused setting first, fail, then wait for
 * the key's next turn before asking again (lion737, Discussion #81, 2026-11-20).
 */
internal object AiRefusedSettings {
    private val thinking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val format: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun refusedThinking(key: String): Boolean = key in thinking

    fun refusedFormat(key: String): Boolean = key in format

    fun thinkingRefused(key: String) {
        thinking += key
    }

    fun formatRefused(key: String) {
        format += key
    }

    /** For tests: forget every refusal. */
    fun clear() {
        thinking.clear()
        format.clear()
    }
}

private class KeyedAiTranslationModel(
    private val provider: AiProvider,
    private val model: String,
    thinking: AiTranslationThinking,
    private val baseUrl: String,
    private val keyStore: AiKeyStore,
    private val transport: AiChatTransport,
    private val pacer: AiKeyPacer,
    private val urgent: Boolean,
) : AiTranslationModel {
    override val scope: String = aiTranslationScope(provider, model, thinking)

    /** Where [AiRefusedSettings] remembers what this service's model refused. */
    private val refusalKey = "${provider.key} $baseUrl $model"

    private val wantedEffort = aiTranslationEffort(provider, model, thinking)
    private val effortKey = "$refusalKey $wantedEffort"

    /**
     * Cleared once the model refuses the thinking setting, so later requests do not fail first. A new
     * model is made for every tapped word, so the refusal is remembered for the app run, not here only.
     */
    @Volatile private var effort = wantedEffort.takeUnless { AiRefusedSettings.refusedThinking(effortKey) }

    /** Cleared once the model refuses the JSON schema, so later requests do not fail first. */
    @Volatile private var responseFormat = aiTranslationResponseFormat(provider).takeUnless { AiRefusedSettings.refusedFormat(refusalKey) }

    override suspend fun ask(
        system: String,
        user: String,
        onText: (String) -> Unit,
    ): String {
        val messages = listOf(AiWireMessage(AiRole.SYSTEM, system), AiWireMessage(AiRole.USER, user))
        val text = StreamedText(onText)
        var failure = AiChatException(AiErrorKind.RATE_LIMITED, "Every key is resting after its limit.")
        // Read for every request, so a key added while a video plays is used at once (2026-11-04: a new
        // key was left out until the video was opened again, and translation kept saying every key rests).
        val keyIds = withContext(Dispatchers.IO) { keyStore.keys(provider).map { it.id } }
        if (keyIds.isEmpty()) throw AiChatException(AiErrorKind.NO_KEY)
        val pacedIds = keyIds.map { "${provider.key}/$it" }
        repeat(keyIds.size) {
            val paced = pacer.take(pacedIds, urgent) ?: throw failure
            val id = keyIds[pacedIds.indexOf(paced)]
            try {
                val key = withContext(Dispatchers.IO) { keyStore.load(provider, id) }
                if (key !is StoredAiKey.Found) throw AiChatException(AiErrorKind.KEY_UNREADABLE)
                val reply = completeWhenBusy(key.key, messages, text) { pacer.take(listOf(paced), urgent) != null }
                pacer.answered(paced)
                return reply.text
            } catch (error: AiChatException) {
                // Part of the answer already arrived: asking again would hand it over twice.
                if (text.started) throw error
                when (error.kind) {
                    in REST_PROBLEMS -> pacer.limited(paced)
                    in BAD_KEY_PROBLEMS -> pacer.rejected(paced)
                    else -> throw error
                }
                failure = error
            }
        }
        throw failure
    }

    /**
     * A busy model is asked again after the assistant's waits; a refused JSON schema, then a refused
     * thinking setting, is dropped and the request is sent again on the key's next turn ([nextTurn],
     * false when the key now rests). Sent at once, those two retries put a free key over 10 requests
     * a minute (AiRequestBudgetTest, 2026-11-15).
     */
    private suspend fun completeWhenBusy(
        apiKey: String,
        messages: List<AiWireMessage>,
        text: StreamedText,
        nextTurn: suspend () -> Boolean,
    ): AiReply {
        val waits = AI_BUSY_RETRY_DELAYS_MS.iterator()
        while (true) {
            val request =
                AiChatRequest(
                    baseUrl,
                    model,
                    apiKey,
                    messages,
                    AI_TRANSLATION_TIMEOUT_MS,
                    effort,
                    format = provider.format,
                    responseFormat = responseFormat,
                )
            try {
                return withContext(Dispatchers.IO) { send(request, text) }
            } catch (error: AiChatException) {
                when {
                    text.started -> throw error
                    responseFormat != null && error.kind in FORMAT_REFUSALS -> {
                        responseFormat = null
                        AiRefusedSettings.formatRefused(refusalKey)
                        if (!nextTurn()) throw error
                    }
                    effort != null && error.kind in setOf(AiErrorKind.UNSUPPORTED_THINKING, AiErrorKind.BAD_REQUEST) -> {
                        effort = null
                        AiRefusedSettings.thinkingRefused(effortKey)
                        if (!nextTurn()) throw error
                    }
                    error.kind == AiErrorKind.SERVER && waits.hasNext() -> delay(waits.next())
                    else -> throw error
                }
            }
        }
    }

    private suspend fun send(
        request: AiChatRequest,
        text: StreamedText,
    ): AiReply =
        try {
            transport.stream(request, text)
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw aiConnectionFailure(error)
        }
}

/** Hands the reply's pieces on and remembers that some arrived, after which a request is not sent again. */
private class StreamedText(
    private val onText: (String) -> Unit,
) : (String) -> Unit {
    @Volatile var started = false
        private set

    override fun invoke(piece: String) {
        if (piece.isEmpty()) return
        started = true
        onText(piece)
    }
}
