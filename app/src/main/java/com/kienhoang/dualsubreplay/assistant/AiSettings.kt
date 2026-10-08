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
private const val AI_MODEL_PREFERENCE_PREFIX = "ai_model_"

private const val DAY_MS = 24L * 60 * 60 * 1000

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
) {
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
internal fun chatCompletionsUrl(baseUrl: String): HttpUrl? {
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null
    val segments = url.pathSegments.filter { it.isNotEmpty() }
    return url
        .newBuilder()
        .encodedPath("/")
        .apply { (segments + listOf("chat", "completions")).forEach(::addPathSegment) }
        .build()
}
