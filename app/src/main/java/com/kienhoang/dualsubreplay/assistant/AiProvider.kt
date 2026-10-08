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
    /** One line under the name in the service picker: what it costs. */
    @StringRes val noteRes: Int,
    /** Empty for [CUSTOM], whose address the user enters. */
    val baseUrl: String,
    val defaultModel: String,
    /** Where to create a key, or null for [CUSTOM]. */
    val keyPageUrl: String?,
    /** The key page offers keys that cost nothing to start with. */
    val freeKeys: Boolean,
    /** The setup steps before "Paste key": open the key page, then create and copy a key. */
    @StringRes val getKeyStepRes: Int,
    @StringRes val createKeyStepRes: Int,
    /** A few models to offer first in the model menu; the full list comes from the service. */
    val suggestedModels: List<String>,
) {
    GEMINI(
        key = "gemini",
        labelRes = R.string.ai_provider_gemini,
        noteRes = R.string.ai_provider_gemini_note,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-flash-latest",
        keyPageUrl = "https://aistudio.google.com/apikey",
        freeKeys = true,
        getKeyStepRes = R.string.ai_setup_step_get_google,
        createKeyStepRes = R.string.ai_setup_step_create,
        // Google's "latest" names follow each new release, so they do not expire.
        suggestedModels = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest"),
    ),
    OPENROUTER(
        key = "openrouter",
        labelRes = R.string.ai_provider_openrouter,
        noteRes = R.string.ai_provider_openrouter_note,
        baseUrl = "https://openrouter.ai/api/v1",
        // Picks one of the free models, so a new free key works without adding credit.
        defaultModel = "openrouter/free",
        keyPageUrl = "https://openrouter.ai/keys",
        freeKeys = true,
        getKeyStepRes = R.string.ai_setup_step_get_openrouter,
        createKeyStepRes = R.string.ai_setup_step_create,
        suggestedModels = listOf("openrouter/free", "openrouter/auto"),
    ),
    OPENAI(
        key = "openai",
        labelRes = R.string.ai_provider_openai,
        noteRes = R.string.ai_provider_openai_note,
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5-mini",
        keyPageUrl = "https://platform.openai.com/api-keys",
        freeKeys = false,
        getKeyStepRes = R.string.ai_setup_step_get_openai,
        createKeyStepRes = R.string.ai_setup_step_create_openai,
        suggestedModels = listOf("gpt-5-mini", "gpt-5-nano", "gpt-5"),
    ),

    /** OpenCode's pay-per-use gateway, with a few free models; only its chat/completions models work here. */
    OPENCODE_ZEN(
        key = "opencode_zen",
        labelRes = R.string.ai_provider_opencode_zen,
        noteRes = R.string.ai_provider_opencode_zen_note,
        baseUrl = "https://opencode.ai/zen/v1",
        defaultModel = "big-pickle",
        keyPageUrl = "https://opencode.ai/auth",
        freeKeys = false,
        getKeyStepRes = R.string.ai_setup_step_get_opencode_zen,
        createKeyStepRes = R.string.ai_setup_step_create_generic,
        suggestedModels = listOf("big-pickle", "deepseek-v4-flash", "kimi-k2.6", "glm-5.3"),
    ),

    /** OpenCode's monthly plan for open models, with the same kind of key as Zen. */
    OPENCODE_GO(
        key = "opencode_go",
        labelRes = R.string.ai_provider_opencode_go,
        noteRes = R.string.ai_provider_opencode_go_note,
        baseUrl = "https://opencode.ai/zen/go/v1",
        defaultModel = "deepseek-v4-flash",
        keyPageUrl = "https://opencode.ai/auth",
        freeKeys = false,
        getKeyStepRes = R.string.ai_setup_step_get_opencode_go,
        createKeyStepRes = R.string.ai_setup_step_create_generic,
        suggestedModels = listOf("deepseek-v4-flash", "kimi-k2.6", "glm-5.3"),
    ),
    CUSTOM(
        key = "custom",
        labelRes = R.string.ai_provider_custom,
        noteRes = R.string.ai_provider_custom_note,
        baseUrl = "",
        defaultModel = "",
        keyPageUrl = null,
        freeKeys = false,
        getKeyStepRes = R.string.ai_setup_step_create_generic,
        createKeyStepRes = R.string.ai_setup_step_create_generic,
        suggestedModels = emptyList(),
    ),
}

internal val DEFAULT_AI_PROVIDER = AiProvider.GEMINI

internal fun storedAiProvider(raw: String?): AiProvider = AiProvider.entries.firstOrNull { it.key == raw } ?: DEFAULT_AI_PROVIDER

/** Shorter than this, a pasted key is almost certainly cut off. */
private const val MIN_AI_KEY_LENGTH = 20

/**
 * Whether [text] can be an API key: long enough, without spaces, and only printable ASCII, which
 * is all an HTTP header can carry. Text copied from a web page can hide invisible characters.
 */
internal fun looksLikeAiKey(text: String): Boolean {
    val key = text.trim()
    return key.length >= MIN_AI_KEY_LENGTH && key.all { it in '!'..'~' }
}

/**
 * The service a pasted key belongs to, from its well-known start, so people who do not know what
 * an API is rarely have to pick one. A plain `sk-` key could be OpenAI's or OpenCode's, so it
 * keeps a service that uses such keys and otherwise means OpenAI. [CUSTOM] keeps every key.
 */
internal fun aiProviderForKey(
    text: String,
    current: AiProvider,
): AiProvider {
    val key = text.trim()
    return when {
        current == AiProvider.CUSTOM -> current
        key.startsWith("AIza") -> AiProvider.GEMINI
        key.startsWith("sk-or-") -> AiProvider.OPENROUTER
        key.startsWith("sk-proj-") || key.startsWith("sk-svcacct-") -> AiProvider.OPENAI
        key.startsWith("sk-") && current in SK_KEY_PROVIDERS -> current
        key.startsWith("sk-") -> AiProvider.OPENAI
        else -> current
    }
}

private val SK_KEY_PROVIDERS = setOf(AiProvider.OPENAI, AiProvider.OPENCODE_ZEN, AiProvider.OPENCODE_GO)
