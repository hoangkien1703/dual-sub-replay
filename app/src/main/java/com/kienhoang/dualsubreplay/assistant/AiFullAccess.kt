package com.kienhoang.dualsubreplay.assistant

import org.json.JSONObject
import java.util.Locale

/** The last characters of a key that the app shows and the model may name. */
internal const val AI_KEY_ENDING_LENGTH = 4

/** At most this many saved words are read back to the model at once. */
internal const val MAX_AI_FOUND_WORDS = 30

private const val MAX_MODEL_CHARS = 100
private const val MAX_WORD_CHARS = 80
private const val MAX_MEANING_CHARS = 300
private const val MAX_QUERY_CHARS = 80
private const val MAX_KEY_CHARS = 300

/** The assistant's own settings that full access lets it change. */
internal enum class AiOwnSetting(
    val key: String,
) {
    SERVICE("ai_service"),
    MODEL("ai_model"),
    THINKING("ai_thinking"),
    MEMORY("ai_memory"),
    CHAT_HISTORY("ai_chat_history"),
    YOUTUBE_SEARCH("ai_youtube_search"),
    WEB_SEARCH("ai_web_search"),
    SCREEN("ai_screen"),
}

internal enum class AiResetTarget(
    val key: String,
) {
    /** Like More settings → Reset all settings: the AI settings, keys and saved words stay. */
    ALL_SETTINGS("all_settings"),

    /** Like Layout → Reset subtitle positions: where the overlay and the CC button were dragged. */
    SUBTITLE_POSITIONS("subtitle_positions"),
}

internal const val AI_CHANGE_AI_SETTING_TOOL = "change_ai_setting"
internal const val AI_ADD_KEY_TOOL = "add_api_key"
internal const val AI_REMOVE_KEY_TOOL = "remove_api_key"
internal const val AI_FIND_WORDS_TOOL = "find_saved_words"
internal const val AI_DELETE_WORD_TOOL = "delete_saved_word"
internal const val AI_EDIT_WORD_TOOL = "edit_saved_word"
internal const val AI_RESET_TOOL = "reset"

private val SERVICE_ALIASES =
    mapOf(
        "google" to AiProvider.GEMINI,
        "google_gemini" to AiProvider.GEMINI,
        "anthropic" to AiProvider.CLAUDE,
        "chatgpt" to AiProvider.OPENAI,
        "zen" to AiProvider.OPENCODE_ZEN,
        "go" to AiProvider.OPENCODE_GO,
        "other" to AiProvider.CUSTOM,
        "openai_compatible" to AiProvider.CUSTOM,
    )

private val HISTORY_ALIASES =
    mapOf(
        "7" to "7_days",
        "week" to "7_days",
        "1_week" to "7_days",
        "30" to "30_days",
        "month" to "30_days",
        "1_month" to "30_days",
        "never" to "off",
        "always" to "forever",
        "keep" to "forever",
    )

private fun choiceKey(raw: String): String =
    raw
        .trim()
        .lowercase(Locale.ROOT)
        .replace(' ', '_')
        .replace('-', '_')

/** A service from its key ("gemini") or name ("Google Gemini", "OpenCode Zen"), or null. */
internal fun aiServiceFromText(raw: String): AiProvider? {
    val key = choiceKey(raw)
    return AiProvider.entries.firstOrNull { it.key == key || it.key.replace("_", "") == key.replace("_", "") }
        ?: SERVICE_ALIASES[key]
        ?: SERVICE_ALIASES[key.removePrefix("opencode_")]
}

/** [raw] as a value [setting] takes, or null. A model is any name without spaces. */
internal fun aiOwnSettingValue(
    setting: AiOwnSetting,
    raw: String,
): String? {
    val key = choiceKey(raw)
    return when (setting) {
        AiOwnSetting.SERVICE -> aiServiceFromText(raw)?.key
        AiOwnSetting.MODEL -> raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_CHARS && it.none(Char::isWhitespace) }
        AiOwnSetting.THINKING -> AiThinking.entries.firstOrNull { it.key == key }?.key
        AiOwnSetting.MEMORY, AiOwnSetting.YOUTUBE_SEARCH, AiOwnSetting.SCREEN -> aiSwitch(raw)
        AiOwnSetting.WEB_SEARCH -> AiWebSearchMode.entries.firstOrNull { it.key == key }?.key
        AiOwnSetting.CHAT_HISTORY ->
            listOfNotNull(key, HISTORY_ALIASES[key], HISTORY_ALIASES[key.removeSuffix("_days").removeSuffix("days").trim('_')])
                .firstOrNull { candidate -> ChatHistoryRetention.entries.any { it.key == candidate } }
    }
}

/** What [setting] takes, in English, for the model. */
internal fun aiOwnSettingValues(setting: AiOwnSetting): String =
    when (setting) {
        AiOwnSetting.SERVICE -> choiceList(AiProvider.entries.map { it.key })
        AiOwnSetting.MODEL -> "a model name the service offers, for example ${AiProvider.GEMINI.defaultModel}"
        AiOwnSetting.THINKING -> choiceList(AiThinking.entries.map { it.key })
        AiOwnSetting.MEMORY, AiOwnSetting.YOUTUBE_SEARCH, AiOwnSetting.SCREEN -> "on or off"
        AiOwnSetting.WEB_SEARCH ->
            choiceList(AiWebSearchMode.entries.map { it.key }) + " (service is the AI service's own paid search; tavily needs a Tavily key)"
        AiOwnSetting.CHAT_HISTORY -> choiceList(ChatHistoryRetention.entries.map { it.key })
    }

/** Parses the full-access tools; null when [name] is none of them. */
internal fun parseFullAccessAction(
    name: String,
    arguments: JSONObject,
): AiActionParse? =
    when (name) {
        AI_CHANGE_AI_SETTING_TOOL -> parseOwnSetting(arguments)
        AI_ADD_KEY_TOOL ->
            text(arguments, "key", MAX_KEY_CHARS)?.takeIf(::looksLikeAiKey)?.let { AiActionParse.Valid(AiAction.AddApiKey(it)) }
                ?: AiActionParse.Invalid("Give the whole key exactly as the user typed it.")
        AI_REMOVE_KEY_TOOL -> parseRemoveKey(arguments)
        AI_FIND_WORDS_TOOL -> AiActionParse.Valid(AiAction.FindSavedWords(text(arguments, "query", MAX_QUERY_CHARS)))
        AI_DELETE_WORD_TOOL ->
            text(arguments, "word", MAX_WORD_CHARS)?.let { AiActionParse.Valid(AiAction.DeleteSavedWord(it)) }
                ?: AiActionParse.Invalid("Give the saved word, at most $MAX_WORD_CHARS characters.")
        AI_EDIT_WORD_TOOL -> parseEditWord(arguments)
        AI_RESET_TOOL -> {
            val what = text(arguments, "what", MAX_WORD_CHARS)?.let(::choiceKey)
            AiResetTarget.entries
                .firstOrNull { it.key == what }
                ?.let { AiActionParse.Valid(AiAction.Reset(it)) }
                ?: AiActionParse.Invalid("what must be ${choiceList(AiResetTarget.entries.map { it.key })}.")
        }
        else -> null
    }

private fun parseOwnSetting(arguments: JSONObject): AiActionParse {
    val name = text(arguments, "setting", MAX_WORD_CHARS)?.let(::choiceKey)
    val setting =
        AiOwnSetting.entries.firstOrNull { it.key == name || it.key.removePrefix("ai_") == name }
            ?: return AiActionParse.Invalid("setting must be ${choiceList(AiOwnSetting.entries.map { it.key })}.")
    val value =
        aiOwnSettingValue(setting, argument(arguments, "value")?.toString().orEmpty())
            ?: return AiActionParse.Invalid("${setting.key} takes ${aiOwnSettingValues(setting)}.")
    return AiActionParse.Valid(AiAction.ChangeAiSetting(setting, value))
}

private fun parseRemoveKey(arguments: JSONObject): AiActionParse {
    val provider =
        text(arguments, "service", MAX_WORD_CHARS)?.let(::aiServiceFromText)
            ?: return AiActionParse.Invalid("service must be ${choiceList(AiProvider.entries.map { it.key })}.")
    val ending =
        text(arguments, "ending", MAX_WORD_CHARS)
            ?.takeLast(AI_KEY_ENDING_LENGTH)
            ?.takeIf { it.length == AI_KEY_ENDING_LENGTH }
            ?: return AiActionParse.Invalid("Give the last $AI_KEY_ENDING_LENGTH characters of the key, as listed in the system prompt.")
    return AiActionParse.Valid(AiAction.RemoveApiKey(provider, ending))
}

private fun parseEditWord(arguments: JSONObject): AiActionParse {
    val word =
        text(arguments, "word", MAX_WORD_CHARS) ?: return AiActionParse.Invalid("Give the saved word, at most $MAX_WORD_CHARS characters.")
    val meaning = text(arguments, "meaning", MAX_MEANING_CHARS)
    val reading = text(arguments, "reading", MAX_WORD_CHARS)
    if (meaning == null && reading == null) return AiActionParse.Invalid("Give a new meaning, a new reading, or both.")
    return AiActionParse.Valid(AiAction.EditSavedWord(word, meaning, reading))
}

/** The full-access actions in English, for the model. A key is only ever named by its ending. */
internal fun aiFullAccessActionNote(action: AiAction): String =
    when (action) {
        is AiAction.ChangeAiSetting -> "Set ${action.setting.key} to ${action.value}"
        is AiAction.AddApiKey -> "Add an API key ending in ${action.ending}"
        is AiAction.RemoveApiKey -> "Remove the ${action.provider.key} key ending in ${action.ending}"
        is AiAction.FindSavedWords -> action.query?.let { "Look for saved words with \"$it\"" } ?: "Read the saved words"
        is AiAction.DeleteSavedWord -> "Delete the saved word \"${action.word}\""
        is AiAction.EditSavedWord ->
            "Change the saved word \"${action.word}\"" +
                listOfNotNull(
                    action.meaning?.let { " meaning to \"$it\"" },
                    action.reading?.let { " reading to \"$it\"" },
                ).joinToString(",")
        is AiAction.RestoreSavedWords -> "Put back ${action.words.size} saved word entries"
        is AiAction.Reset ->
            when (action.target) {
                AiResetTarget.ALL_SETTINGS -> "Reset all settings to their defaults"
                AiResetTarget.SUBTITLE_POSITIONS -> "Reset the dragged subtitle positions"
            }
        else -> action.toString()
    }

/** Offered with [AI_TOOLS] while full access is on. */
internal val AI_FULL_ACCESS_TOOLS: List<AiTool> by lazy {
    listOf(
        AiTool(
            AI_CHANGE_AI_SETTING_TOOL,
            "Change one of the AI assistant's own settings the user asked for. Runs at once and the user sees Undo. A new " +
                "model is tried with one tiny request first. Values: " +
                AiOwnSetting.entries.joinToString("; ") { "${it.key}: ${aiOwnSettingValues(it)}" } + ".",
            schema(
                JSONObject()
                    .put("setting", stringProperty("The setting.", AiOwnSetting.entries.map { it.key }))
                    .put("value", stringProperty("The new value, as listed for the setting.")),
                "setting",
                "value",
            ),
        ),
        AiTool(
            AI_ADD_KEY_TOOL,
            "Save an API key that the user typed in their own message in this chat, so the assistant can switch to it " +
                "when another key fails. Never invent a key. Runs at once and the user sees Undo.",
            schema(JSONObject().put("key", stringProperty("The whole key exactly as the user typed it.")), "key"),
        ),
        AiTool(
            AI_REMOVE_KEY_TOOL,
            "Remove a saved API key the user asked to remove, named by its service and its last 4 characters as listed in " +
                "the system prompt. The user sees a Remove button and nothing happens until they tap it.",
            schema(
                JSONObject()
                    .put("service", stringProperty("The key's service.", AiProvider.entries.map { it.key }))
                    .put("ending", stringProperty("The key's last 4 characters.")),
                "service",
                "ending",
            ),
        ),
        AiTool(
            AI_FIND_WORDS_TOOL,
            "Read the user's saved vocabulary: words whose word, reading or meaning contains the query, or the newest " +
                "$MAX_AI_FOUND_WORDS without one. Changes nothing.",
            schema(JSONObject().put("query", stringProperty("Text to look for. Optional."))),
        ),
        AiTool(
            AI_DELETE_WORD_TOOL,
            "Delete a word the user asked to delete from their saved vocabulary, with every example saved for it. Runs " +
                "at once and the user sees Undo.",
            schema(JSONObject().put("word", stringProperty("The saved word exactly as saved.")), "word"),
        ),
        AiTool(
            AI_EDIT_WORD_TOOL,
            "Change the meaning or reading of a saved word the user asked to change. Runs at once and the user sees Undo.",
            schema(
                JSONObject()
                    .put("word", stringProperty("The saved word exactly as saved."))
                    .put("meaning", stringProperty("The new meaning. Optional."))
                    .put("reading", stringProperty("The new reading. Optional.")),
                "word",
            ),
        ),
        AiTool(
            AI_RESET_TOOL,
            "Reset all app settings to their defaults (AI settings, keys and saved words stay), or reset where the " +
                "subtitles and CC button were dragged. The user sees a Reset button and nothing happens until they tap it.",
            schema(JSONObject().put("what", stringProperty("What to reset.", AiResetTarget.entries.map { it.key })), "what"),
        ),
    )
}

/**
 * What the model reads about the assistant itself while full access is on: its settings and the
 * saved keys by their endings. Keys themselves are never sent.
 */
internal fun aiFullAccessPrompt(state: AiAssistantUiState): String =
    buildString {
        val settings = state.settings
        append("\n\nFull access is on: you may also change the assistant's own settings, its API keys and the saved words, ")
        append("and reset settings, when the user asks.")
        append("\nAI assistant settings:")
        append("\n- ai_service: ").append(settings.provider.key)
        append("\n- ai_model: ").append(settings.modelFor(settings.provider).ifEmpty { "none" })
        append("\n- ai_thinking: ").append(settings.thinking.key)
        append("\n- ai_memory: ").append(if (settings.memoryEnabled) "on" else "off")
        append("\n- ai_chat_history: ").append(settings.historyRetention.key)
        append("\n- ai_youtube_search: ").append(if (settings.youtubeSearch) "on" else "off")
        append("\n- ai_web_search: ").append(settings.webSearch.key)
        append("\n- ai_screen: ").append(if (settings.screenLook) "on" else "off")
        append("\nSaved API keys (last 4 characters):")
        if (state.keys.values.all { it.isEmpty() }) append(" none")
        state.keys.forEach { (provider, keys) ->
            if (keys.isEmpty()) return@forEach
            val inUse = state.keyInUse(provider)
            append("\n- ").append(provider.key).append(": ")
            append(keys.joinToString(", ") { it.hint + if (it.id == inUse) " (in use)" else "" })
        }
    }
