package com.kienhoang.dualsubreplay.assistant

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.kienhoang.dualsubreplay.R
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Non-secret assistant settings; the keys themselves live in [AiKeyStore]. */
internal const val AI_SETTINGS_PREFERENCES = "ai_assistant"
internal const val AI_ENABLED_PREFERENCE = "ai_enabled"
internal const val AI_PROVIDER_PREFERENCE = "ai_provider"
internal const val AI_CUSTOM_BASE_URL_PREFERENCE = "ai_custom_base_url"
internal const val AI_HISTORY_RETENTION_PREFERENCE = "ai_history_retention"
internal const val AI_THINKING_PREFERENCE = "ai_thinking"
internal const val AI_INTRO_SEEN_PREFERENCE = "ai_intro_seen"
internal const val AI_MEMORY_ENABLED_PREFERENCE = "ai_memory_enabled"
internal const val AI_NEWS_SEEN_PREFERENCE = "ai_news_seen"
private const val AI_MODEL_PREFERENCE_PREFIX = "ai_model_"

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The "what's new" note people who already answered the intro see once. Raise it to show a new
 * note; the intro marks the current one as seen, so new users never get it.
 */
internal const val AI_NEWS_VERSION = 1

/** How long saved chats are kept on the phone. [OFF] keeps the open chat in memory only. */
internal enum class ChatHistoryRetention(
    val key: String,
    @StringRes val labelRes: Int,
    /** Null keeps chats until the user deletes them. */
    val days: Int?,
) {
    OFF("off", R.string.ai_history_off, 0),
    WEEK("7_days", R.string.ai_history_week, 7),
    MONTH("30_days", R.string.ai_history_month, 30),
    FOREVER("forever", R.string.ai_history_forever, null),
    ;

    /** Chats last changed before this time are deleted; null keeps every chat. */
    fun cutoffMs(nowMs: Long): Long? = days?.let { nowMs - it * DAY_MS }
}

internal val DEFAULT_CHAT_HISTORY_RETENTION = ChatHistoryRetention.WEEK

/**
 * How long the model thinks before it answers, sent as `reasoning_effort`. [AUTO] sends nothing, so
 * the model uses its own default; the other levels are the ones Gemini, OpenAI and OpenRouter share.
 */
internal enum class AiThinking(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int,
    val effort: String?,
) {
    AUTO("auto", R.string.ai_effort_auto, R.string.ai_effort_auto_note, null),
    LOW("low", R.string.ai_effort_low, R.string.ai_effort_low_note, "low"),
    MEDIUM("medium", R.string.ai_effort_medium, R.string.ai_effort_medium_note, "medium"),
    HIGH("high", R.string.ai_effort_high, R.string.ai_effort_high_note, "high"),
}

internal fun storedAiThinking(raw: String?): AiThinking = AiThinking.entries.firstOrNull { it.key == raw } ?: AiThinking.AUTO

internal fun storedChatHistoryRetention(raw: String?): ChatHistoryRetention =
    ChatHistoryRetention.entries.firstOrNull { it.key == raw } ?: DEFAULT_CHAT_HISTORY_RETENTION

internal data class AiAssistantSettings(
    /** On by default; off hides the button and sends nothing anywhere. */
    val enabled: Boolean = true,
    val provider: AiProvider = DEFAULT_AI_PROVIDER,
    /** The model typed for each service; a missing or blank entry uses its default. */
    val models: Map<AiProvider, String> = emptyMap(),
    val customBaseUrl: String = "",
    val historyRetention: ChatHistoryRetention = DEFAULT_CHAT_HISTORY_RETENTION,
    val thinking: AiThinking = AiThinking.AUTO,
    /** The panel's first page, which introduces the assistant, was answered with Let's start or Don't use AI. */
    val introSeen: Boolean = false,
    /** Use memory: off, the assistant neither reads nor saves memories; the list stays. */
    val memoryEnabled: Boolean = true,
    /** The last "what's new" note seen, up to [AI_NEWS_VERSION]. */
    val newsSeen: Int = 0,
) {
    /** People who answered the intro before the current note see it once at the top of the chat. */
    val showsNews: Boolean get() = enabled && introSeen && newsSeen < AI_NEWS_VERSION

    fun modelFor(provider: AiProvider): String = models[provider]?.trim()?.takeIf { it.isNotEmpty() } ?: provider.defaultModel

    fun baseUrlFor(provider: AiProvider): String = if (provider == AiProvider.CUSTOM) customBaseUrl.trim() else provider.baseUrl
}

internal fun readAiAssistantSettings(preferences: SharedPreferences): AiAssistantSettings =
    AiAssistantSettings(
        enabled = preferences.getBoolean(AI_ENABLED_PREFERENCE, true),
        provider = storedAiProvider(preferences.getString(AI_PROVIDER_PREFERENCE, null)),
        models =
            AiProvider.entries
                .mapNotNull { provider ->
                    preferences.getString(AI_MODEL_PREFERENCE_PREFIX + provider.key, null)?.let { provider to it }
                }.toMap(),
        customBaseUrl = preferences.getString(AI_CUSTOM_BASE_URL_PREFERENCE, null).orEmpty(),
        historyRetention = storedChatHistoryRetention(preferences.getString(AI_HISTORY_RETENTION_PREFERENCE, null)),
        thinking = storedAiThinking(preferences.getString(AI_THINKING_PREFERENCE, null)),
        introSeen = preferences.getBoolean(AI_INTRO_SEEN_PREFERENCE, false),
        memoryEnabled = preferences.getBoolean(AI_MEMORY_ENABLED_PREFERENCE, true),
        newsSeen = preferences.getInt(AI_NEWS_SEEN_PREFERENCE, 0),
    )

internal fun writeAiAssistantSettings(
    preferences: SharedPreferences,
    settings: AiAssistantSettings,
) {
    preferences.edit {
        putBoolean(AI_ENABLED_PREFERENCE, settings.enabled)
        putString(AI_PROVIDER_PREFERENCE, settings.provider.key)
        putString(AI_CUSTOM_BASE_URL_PREFERENCE, settings.customBaseUrl)
        putString(AI_HISTORY_RETENTION_PREFERENCE, settings.historyRetention.key)
        putString(AI_THINKING_PREFERENCE, settings.thinking.key)
        putBoolean(AI_INTRO_SEEN_PREFERENCE, settings.introSeen)
        putBoolean(AI_MEMORY_ENABLED_PREFERENCE, settings.memoryEnabled)
        putInt(AI_NEWS_SEEN_PREFERENCE, settings.newsSeen)
        AiProvider.entries.forEach { provider ->
            val model = settings.models[provider]
            if (model == null) {
                remove(AI_MODEL_PREFERENCE_PREFIX + provider.key)
            } else {
                putString(AI_MODEL_PREFERENCE_PREFIX + provider.key, model)
            }
        }
    }
}

/**
 * The `chat/completions` address under [baseUrl], or null unless it is a plain `https://`
 * address: no user name or password in it, no query and no fragment. The key travels in a header,
 * so it must never go over cleartext or to an address that hides another host.
 */
internal fun chatCompletionsUrl(baseUrl: String): HttpUrl? = aiServiceUrl(baseUrl, "chat", "completions")

/** The `models` list under [baseUrl], with the same rules as [chatCompletionsUrl]. */
internal fun modelsUrl(baseUrl: String): HttpUrl? = aiServiceUrl(baseUrl, "models")

private fun aiServiceUrl(
    baseUrl: String,
    vararg path: String,
): HttpUrl? {
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    val segments = url.pathSegments.filter { it.isNotEmpty() }
    return url
        .newBuilder()
        .encodedPath("/")
        .apply { (segments + path).forEach(::addPathSegment) }
        .build()
}

/**
 * What a key check covers: the address and the model it answered through. A key counts as checked
 * only while both stay the same, so a new model or address is checked again before chatting.
 */
internal fun aiCheckedSetup(
    settings: AiAssistantSettings,
    provider: AiProvider,
): String = settings.baseUrlFor(provider) + "\n" + settings.modelFor(provider)
