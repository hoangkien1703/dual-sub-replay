package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where the assistant keeps its non-secret settings; SharedPreferences in the app. */
internal interface AiSettingsStorage {
    fun load(): AiAssistantSettings

    fun save(settings: AiAssistantSettings)
}

/** Where saved chats live; [AiChatHistoryStore] in the app. */
internal interface AiHistoryStorage {
    fun load(): List<AiChat>

    fun save(chats: List<AiChat>)

    fun clear()
}

internal data class AiFailure(
    val kind: AiErrorKind,
    val detail: String? = null,
)

internal sealed interface AiConnectionTest {
    data object Idle : AiConnectionTest

    data object Testing : AiConnectionTest

    data object Passed : AiConnectionTest

    data class Failed(
        val failure: AiFailure,
    ) : AiConnectionTest
}

internal data class AiAssistantUiState(
    val settings: AiAssistantSettings = AiAssistantSettings(),
    /** The last characters of each saved key; a service without an entry has no key. */
    val keyHints: Map<AiProvider, String> = emptyMap(),
    val panelOpen: Boolean = false,
    val showingHistory: Boolean = false,
    /** Saved chats, newest first. Empty when history is off. */
    val savedChats: List<AiChat> = emptyList(),
    val chat: AiChat? = null,
    val sending: Boolean = false,
    /** Why the last question got no answer; cleared by the next one. */
    val failure: AiFailure? = null,
    val connectionTest: AiConnectionTest = AiConnectionTest.Idle,
) {
    val hasKey: Boolean get() = settings.provider in keyHints
    val messages: List<AiChatMessage> get() = chat?.messages.orEmpty()

    /** The assistant can be asked: on, with a key for the chosen service and, for Other, a valid address. */
    val ready: Boolean
        get() = settings.enabled && hasKey && chatCompletionsUrl(settings.baseUrlFor(settings.provider)) != null
}

/**
 * The assistant's state and actions. Requests, Keystore work and file writes run on [io]; state
 * changes are published through [state]. Nothing is sent anywhere until the user asks something.
 */
internal class AiAssistantController(
    private val scope: CoroutineScope,
    private val transport: AiChatTransport,
    private val keyStore: AiKeyStore,
    private val settingsStorage: AiSettingsStorage,
    private val historyStorage: AiHistoryStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(AiAssistantUiState())
    val state: StateFlow<AiAssistantUiState> = _state.asStateFlow()
    private var sendJob: Job? = null
    private var testJob: Job? = null

    init {
        scope.launch {
            val loaded =
                withContext(io) {
                    val settings = settingsStorage.load()
                    val hints = AiProvider.entries.mapNotNull { provider -> keyStore.hint(provider)?.let { provider to it } }.toMap()
                    val stored = historyStorage.load()
                    val kept = keptAiChats(stored, settings.historyRetention, clock())
                    if (kept != stored) historyStorage.save(kept)
                    Triple(settings, hints, kept)
                }
            _state.update { it.copy(settings = loaded.first, keyHints = loaded.second, savedChats = loaded.third) }
        }
    }

    fun openPanel() = _state.update { it.copy(panelOpen = true) }

    fun closePanel() = _state.update { it.copy(panelOpen = false, showingHistory = false) }

    fun showHistory(show: Boolean) = _state.update { it.copy(showingHistory = show) }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelRequests()
        updateSettings { it.copy(enabled = enabled) }
        if (!enabled) _state.update { it.copy(panelOpen = false, showingHistory = false) }
    }

    fun selectProvider(provider: AiProvider) {
        updateSettings { it.copy(provider = provider) }
        _state.update { it.copy(connectionTest = AiConnectionTest.Idle, failure = null) }
    }

    fun setModel(
        provider: AiProvider,
        model: String,
    ) = updateSettings { it.copy(models = it.models + (provider to model)) }

    fun setCustomBaseUrl(url: String) = updateSettings { it.copy(customBaseUrl = url) }

    /**
     * Saves a pasted or typed key, encrypted. A key whose start names its service switches to that
     * service, so nobody has to know which one they have. Returns the service, or null when the
     * text cannot be a key.
     */
    fun saveKey(text: String): AiProvider? {
        if (!looksLikeAiKey(text)) return null
        val key = text.trim()
        val current = _state.value.settings.provider
        val provider = aiProviderForKey(key)?.takeIf { current != AiProvider.CUSTOM } ?: current
        scope.launch {
            withContext(io) { keyStore.save(provider, key) }
            _state.update { it.copy(keyHints = it.keyHints + (provider to key.takeLast(4)), failure = null) }
            if (provider != current) selectProvider(provider)
            testConnection()
        }
        return provider
    }

    fun removeKey(provider: AiProvider) {
        scope.launch {
            withContext(io) { keyStore.remove(provider) }
            _state.update { it.copy(keyHints = it.keyHints - provider, connectionTest = AiConnectionTest.Idle) }
        }
    }

    /** Sends a tiny request with the chosen service, key and model. */
    fun testConnection() {
        testJob?.cancel()
        _state.update { it.copy(connectionTest = AiConnectionTest.Testing) }
        testJob =
            scope.launch {
                val result =
                    try {
                        request(listOf(AiWireMessage(AiRole.SYSTEM, "Reply with the single word OK."), AiWireMessage(AiRole.USER, "OK?")))
                        AiConnectionTest.Passed
                    } catch (error: AiChatException) {
                        AiConnectionTest.Failed(AiFailure(error.kind, error.detail))
                    }
                _state.update { it.copy(connectionTest = result) }
            }
    }

    fun setHistoryRetention(retention: ChatHistoryRetention) {
        updateSettings { it.copy(historyRetention = retention) }
        persistChats()
    }

    fun deleteHistory() {
        _state.update { it.copy(savedChats = emptyList()) }
        scope.launch { withContext(io) { historyStorage.clear() } }
    }

    fun newChat() {
        cancelRequests()
        _state.update { it.copy(chat = null, failure = null, showingHistory = false) }
    }

    fun openChat(id: String) {
        val chat = _state.value.savedChats.firstOrNull { it.id == id } ?: return
        cancelRequests()
        _state.update { it.copy(chat = chat, failure = null, showingHistory = false) }
    }

    fun deleteChat(id: String) {
        _state.update { current ->
            current.copy(
                savedChats = current.savedChats.filterNot { it.id == id },
                chat = current.chat?.takeUnless { it.id == id },
            )
        }
        persistChats()
    }

    /**
     * Adds the user's question to the open chat and asks the service. [context] goes with the
     * question without being shown as its text; [contextLabel] says what it is.
     */
    fun send(
        text: String,
        systemPrompt: String,
        context: String? = null,
        contextLabel: String? = null,
    ) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.sending) return
        val now = clock()
        val message = AiChatMessage(newId(), AiRole.USER, question, now, context, contextLabel)
        _state.update { current ->
            val chat = current.chat ?: AiChat(newId(), now, now, emptyList())
            current.copy(chat = chat.copy(updatedMs = now, messages = chat.messages + message), failure = null, showingHistory = false)
        }
        ask(systemPrompt)
    }

    /** Asks again after a failure, with the same last question. */
    fun retry(systemPrompt: String) {
        if (_state.value.messages
                .lastOrNull()
                ?.role == AiRole.USER
        ) {
            ask(systemPrompt)
        }
    }

    private fun ask(systemPrompt: String) {
        val history = _state.value.messages
        _state.update { it.copy(sending = true, failure = null) }
        sendJob =
            scope.launch {
                try {
                    val reply = request(buildAiRequestMessages(systemPrompt, history))
                    val now = clock()
                    _state.update { current ->
                        val chat = current.chat ?: return@update current.copy(sending = false)
                        val answer = AiChatMessage(newId(), AiRole.ASSISTANT, reply, now)
                        current.copy(chat = chat.copy(updatedMs = now, messages = chat.messages + answer), sending = false)
                    }
                } catch (error: AiChatException) {
                    _state.update { it.copy(sending = false, failure = AiFailure(error.kind, error.detail)) }
                } catch (cancelled: CancellationException) {
                    _state.update { it.copy(sending = false) }
                    throw cancelled
                }
                persistChats()
            }
    }

    /** Unlocks the key for this one request only; it is never kept in the state. */
    private suspend fun request(messages: List<AiWireMessage>): String {
        val settings = _state.value.settings
        val provider = settings.provider
        val baseUrl = settings.baseUrlFor(provider)
        if (chatCompletionsUrl(baseUrl) == null) throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val key =
            when (val stored = withContext(io) { keyStore.load(provider) }) {
                is StoredAiKey.Found -> stored.key
                StoredAiKey.Missing -> throw AiChatException(AiErrorKind.NO_KEY)
                StoredAiKey.Unreadable -> {
                    _state.update { it.copy(keyHints = it.keyHints - provider) }
                    throw AiChatException(AiErrorKind.KEY_UNREADABLE)
                }
            }
        return withContext(io) { transport.complete(AiChatRequest(baseUrl, settings.modelFor(provider), key, messages)) }
    }

    /** Merges the open chat into the saved ones and writes what the retention keeps. */
    private fun persistChats() {
        val current = _state.value
        val retention = current.settings.historyRetention
        val merged =
            current.chat
                ?.takeIf { it.messages.isNotEmpty() }
                ?.let { open -> listOf(open) + current.savedChats.filterNot { it.id == open.id } }
                ?: current.savedChats
        val kept = keptAiChats(merged, retention, clock())
        _state.update { it.copy(savedChats = kept) }
        scope.launch { withContext(io) { if (kept.isEmpty()) historyStorage.clear() else historyStorage.save(kept) } }
    }

    private fun updateSettings(change: (AiAssistantSettings) -> AiAssistantSettings) {
        val updated = change(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        scope.launch { withContext(io) { settingsStorage.save(updated) } }
    }

    private fun cancelRequests() {
        sendJob?.cancel()
        sendJob = null
        _state.update { it.copy(sending = false) }
    }
}
