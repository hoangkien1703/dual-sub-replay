package com.kienhoang.dualsubreplay.assistant

import androidx.annotation.StringRes
import com.kienhoang.dualsubreplay.R

/**
 * The AI services the assistant can use with the user's own key. Every one of them speaks the
 * OpenAI "chat completions" format; Gemini through Google's OpenAI-compatible endpoint.
 * Model names expire, so the defaults live here and the model field in settings overrides them.
 */
internal enum class AiProvider(
    val key: String,
    @StringRes val labelRes: Int,
    /** Empty for [CUSTOM], whose address the user enters. */
    val baseUrl: String,
    val defaultModel: String,
    /** Where to create a key, or null for [CUSTOM]. */
    val keyPageUrl: String?,
    /** The key page offers keys that cost nothing to start with. */
    val freeKeys: Boolean,
) {
    GEMINI(
        key = "gemini",
        labelRes = R.string.ai_provider_gemini,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-flash-latest",
        keyPageUrl = "https://aistudio.google.com/apikey",
        freeKeys = true,
    ),
    OPENAI(
        key = "openai",
        labelRes = R.string.ai_provider_openai,
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5-mini",
        keyPageUrl = "https://platform.openai.com/api-keys",
        freeKeys = false,
    ),
    OPENROUTER(
        key = "openrouter",
        labelRes = R.string.ai_provider_openrouter,
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openrouter/auto",
        keyPageUrl = "https://openrouter.ai/keys",
        freeKeys = true,
    ),
    CUSTOM(
        key = "custom",
        labelRes = R.string.ai_provider_custom,
        baseUrl = "",
        defaultModel = "",
        keyPageUrl = null,
        freeKeys = false,
    ),
}

internal val DEFAULT_AI_PROVIDER = AiProvider.GEMINI

internal fun storedAiProvider(raw: String?): AiProvider = AiProvider.entries.firstOrNull { it.key == raw } ?: DEFAULT_AI_PROVIDER

/** Shorter than this, a pasted key is almost certainly cut off. */
private const val MIN_AI_KEY_LENGTH = 20

/** Whether [text] can be an API key: long enough and without spaces or line breaks. */
internal fun looksLikeAiKey(text: String): Boolean {
    val key = text.trim()
    return key.length >= MIN_AI_KEY_LENGTH && key.none(Char::isWhitespace)
}

/**
 * The service a pasted key belongs to, from its well-known start, so people who do not know what
 * an API is never have to pick one. Null when the key could belong to any compatible service.
 */
internal fun aiProviderForKey(text: String): AiProvider? {
    val key = text.trim()
    return when {
        key.startsWith("AIza") -> AiProvider.GEMINI
        key.startsWith("sk-or-") -> AiProvider.OPENROUTER
        key.startsWith("sk-") -> AiProvider.OPENAI
        else -> null
    }
}
