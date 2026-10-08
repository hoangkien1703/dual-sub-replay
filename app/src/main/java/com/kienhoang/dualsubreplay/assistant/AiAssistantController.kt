package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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

private class LoadedAiState(
    val settings: AiAssistantSettings,
    val keyHints: Map<AiProvider, String>,
    val checkedSetups: Map<AiProvider, String>,
    val savedChats: List<AiChat>,
)

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

/** What the assistant panel shows below its header. */
internal enum class AiPanelPage {
    CHAT,
    HISTORY,
    SETTINGS,

    /** Every model the service offers, with search. */
    MODELS,
}

/** A model chosen in the model menu, tried before the chat switches to it. */
internal sealed interface AiModelCheck {
    val model: String

    data class Checking(
        override val model: String,
    ) : AiModelCheck

    data class Failed(
        override val model: String,
        val failure: AiFailure,
    ) : AiModelCheck
}

/** The service's model list for the model page; [Loaded] remembers which service it came from. */
internal sealed interface AiModelList {
    data object Idle : AiModelList

    data object Loading : AiModelList

    data class Loaded(
        val provider: AiProvider,
        val models: List<AiModelInfo>,
    ) : AiModelList

    data class Failed(
        val failure: AiFailure,
    ) : AiModelList
}

/**
 * A failed check with one of these means the key, model or address is wrong. Anything else (no
 * network, a slow or busy service, a used-up limit that resets) keeps an earlier pass.
 */
private val SETUP_PROBLEMS =
    setOf(
        AiErrorKind.NO_KEY,
        AiErrorKind.KEY_UNREADABLE,
        AiErrorKind.BAD_ADDRESS,
        AiErrorKind.INVALID_KEY,
        AiErrorKind.UNKNOWN_MODEL,
        AiErrorKind.BAD_REQUEST,
    )

/**
 * A busy model (HTTP 503 "high demand") or a short rate limit is asked again after these waits.
 * Gemini's free `gemini-flash-latest` answers 503 to about half of all requests at busy times.
 */
internal val AI_BUSY_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

/** The service answered but was busy: it accepted the key before it looked for a free model. */
private val BUSY_PROBLEMS = setOf(AiErrorKind.SERVER, AiErrorKind.RATE_LIMITED)

private val KEY_CHECK_MESSAGES =
    listOf(AiWireMessage(AiRole.SYSTEM, "Reply with the single word OK."), AiWireMessage(AiRole.USER, "OK?"))

internal data class AiAssistantUiState(
    val settings: AiAssistantSettings = AiAssistantSettings(),
    /** The last characters of each saved key; a service without an entry has no key. */
    val keyHints: Map<AiProvider, String> = emptyMap(),
    /** The setup each saved key last answered with ([aiCheckedSetup]); a key without an entry is unchecked. */
    val checkedSetups: Map<AiProvider, String> = emptyMap(),
    val panelOpen: Boolean = false,
    val page: AiPanelPage = AiPanelPage.CHAT,
    /** Saved chats, newest first. Empty when history is off. */
    val savedChats: List<AiChat> = emptyList(),
    val chat: AiChat? = null,
    val sending: Boolean = false,
    /** Why the last question got no answer; cleared by the next one. */
    val failure: AiFailure? = null,
    val connectionTest: AiConnectionTest = AiConnectionTest.Idle,
    val modelCheck: AiModelCheck? = null,
    val modelList: AiModelList = AiModelList.Idle,
    /** Pictures and files chosen for the next question. */
    val draftAttachments: List<AiAttachment> = emptyList(),
) {
    val hasKey: Boolean get() = settings.provider in keyHints
    val messages: List<AiChatMessage> get() = chat?.messages.orEmpty()
    val addressValid: Boolean get() = chatCompletionsUrl(settings.baseUrlFor(settings.provider)) != null

    /** The saved key answered through the current service, address and model. */
    val keyChecked: Boolean
        get() = hasKey && checkedSetups[settings.provider] == aiCheckedSetup(settings, settings.provider)

    /** The assistant can be asked: on, with a key that has answered through the current setup. */
    val ready: Boolean
        get() = settings.enabled && keyChecked && addressValid
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
    private val modelLister: AiModelLister = AiModelLister { _, _ -> emptyList() },
    /** Waits before asking a busy service again; each entry is one more try. */
    private val busyRetryDelaysMs: List<Long> = AI_BUSY_RETRY_DELAYS_MS,
) {
    private val _state = MutableStateFlow(AiAssistantUiState())
    val state: StateFlow<AiAssistantUiState> = _state.asStateFlow()
    private var sendJob: Job? = null
    private var testJob: Job? = null
    private var modelJob: Job? = null
    private var listJob: Job? = null

    /** Changes with every saved or removed key, so a check that was already running cannot count for a new key. */
    @Volatile private var keyGeneration = 0

    init {
        scope.launch {
            val loaded =
                withContext(io) {
                    val settings = settingsStorage.load()
                    val hints = AiProvider.entries.mapNotNull { provider -> keyStore.hint(provider)?.let { provider to it } }.toMap()
                    val checked =
                        AiProvider.entries.mapNotNull { provider -> keyStore.checkedSetup(provider)?.let { provider to it } }.toMap()
                    val stored = historyStorage.load()
                    val kept = keptAiChats(stored, settings.historyRetention, clock())
                    if (kept != stored) historyStorage.save(kept)
                    LoadedAiState(settings, hints, checked, kept)
                }
            _state.update {
                it.copy(
                    settings = loaded.settings,
                    keyHints = loaded.keyHints,
                    checkedSetups = loaded.checkedSetups,
                    savedChats = loaded.savedChats,
                )
            }
        }
    }

    fun openPanel() = _state.update { it.copy(panelOpen = true) }

    fun closePanel() = _state.update { it.copy(panelOpen = false, page = AiPanelPage.CHAT) }

    fun showPage(page: AiPanelPage) = _state.update { it.copy(page = page) }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            cancelRequests()
            modelJob?.cancel()
        }
        updateSettings { it.copy(enabled = enabled) }
        if (!enabled) {
            _state.update {
                it.copy(
                    panelOpen = false,
                    page = AiPanelPage.CHAT,
                    modelCheck = null,
                    draftAttachments = emptyList(),
                )
            }
        }
    }

    fun selectProvider(provider: AiProvider) {
        if (provider == _state.value.settings.provider) return
        testJob?.cancel()
        modelJob?.cancel()
        updateSettings { it.copy(provider = provider) }
        _state.update { it.copy(connectionTest = AiConnectionTest.Idle, failure = null, modelCheck = null) }
    }

    fun setThinking(thinking: AiThinking) = updateSettings { it.copy(thinking = thinking) }

    /**
     * Switches the chat to [model] once it has answered one tiny request with the saved key, so a
     * model the service does not offer never breaks a working chat; the current model keeps
     * answering meanwhile. A model that fails stays unchosen, and [AiAssistantUiState.modelCheck] says why.
     */
    fun chooseModel(model: String) {
        val name = model.trim()
        val settings = _state.value.settings
        val provider = settings.provider
        modelJob?.cancel()
        if (name.isEmpty() || name == settings.modelFor(provider)) {
            _state.update { it.copy(modelCheck = null) }
            return
        }
        val candidate = settings.copy(models = settings.models + (provider to name))
        val setup = aiCheckedSetup(candidate, provider)
        val generation = keyGeneration
        _state.update { it.copy(modelCheck = AiModelCheck.Checking(name)) }
        modelJob =
            scope.launch {
                try {
                    request(candidate, KEY_CHECK_MESSAGES, AI_TEST_TIMEOUT_MS)
                } catch (error: AiChatException) {
                    _state.update { it.copy(modelCheck = AiModelCheck.Failed(name, AiFailure(error.kind, error.detail))) }
                    return@launch
                }
                if (generation != keyGeneration || _state.value.settings.provider != provider) {
                    _state.update { it.copy(modelCheck = null) }
                    return@launch
                }
                updateSettings { it.copy(models = it.models + (provider to name)) }
                _state.update { it.copy(checkedSetups = it.checkedSetups + (provider to setup), modelCheck = null) }
                withContext(io) { if (generation == keyGeneration) keyStore.markChecked(provider, setup) }
            }
    }

    fun dismissModelCheck() = _state.update { it.copy(modelCheck = null) }

    /** Adds files for the next question; returns false when some did not fit under [MAX_AI_ATTACHMENTS]. */
    fun addAttachments(files: List<AiAttachment>): Boolean {
        val current = _state.value.draftAttachments
        _state.update { it.copy(draftAttachments = (current + files).take(MAX_AI_ATTACHMENTS)) }
        return current.size + files.size <= MAX_AI_ATTACHMENTS
    }

    fun removeAttachment(index: Int) =
        _state.update { it.copy(draftAttachments = it.draftAttachments.filterIndexed { i, _ -> i != index }) }

    /** Asks the service which models it offers, keeping those this app can chat with. */
    fun loadModels() {
        val settings = _state.value.settings
        val provider = settings.provider
        listJob?.cancel()
        _state.update { it.copy(modelList = AiModelList.Loading) }
        listJob =
            scope.launch {
                val result =
                    try {
                        val key = unlockKey(provider)
                        val models = withContext(io) { aiCall { modelLister.listModels(settings.baseUrlFor(provider), key) } }
                        AiModelList.Loaded(provider, models.filter { aiModelCanChat(provider, it.id) })
                    } catch (error: AiChatException) {
                        AiModelList.Failed(AiFailure(error.kind, error.detail))
                    }
                _state.update { it.copy(modelList = result) }
            }
    }

    /** A different model or address needs a new check, so an old result no longer shows. */
    fun setModel(
        provider: AiProvider,
        model: String,
    ) = changeSetup { it.copy(models = it.models + (provider to model)) }

    fun setCustomBaseUrl(url: String) = changeSetup { it.copy(customBaseUrl = url) }

    /**
     * Saves a pasted or typed key, encrypted. A key whose start names its service switches to that
     * service, so nobody has to know which one they have. Returns the service, or null when the
     * text cannot be a key.
     */
    fun saveKey(text: String): AiProvider? {
        if (!looksLikeAiKey(text)) return null
        val key = text.trim()
        val provider = aiProviderForKey(key, _state.value.settings.provider)
        keyGeneration++
        selectProvider(provider)
        testJob?.cancel()
        _state.update {
            it.copy(checkedSetups = it.checkedSetups - provider, connectionTest = AiConnectionTest.Testing, failure = null)
        }
        scope.launch {
            withContext(io) { keyStore.save(provider, key) }
            _state.update { it.copy(keyHints = it.keyHints + (provider to key.takeLast(4))) }
            testConnection()
        }
        return provider
    }

    fun removeKey(provider: AiProvider) {
        keyGeneration++
        if (provider == _state.value.settings.provider) testJob?.cancel()
        _state.update {
            it.copy(keyHints = it.keyHints - provider, checkedSetups = it.checkedSetups - provider, connectionTest = AiConnectionTest.Idle)
        }
        scope.launch { withContext(io) { keyStore.remove(provider) } }
    }

    /**
     * Asks for one word with the chosen service, key and model. Chat opens only after this passes;
     * a wrong key, model or address makes the key unchecked again, while a network hiccup does not.
     */
    fun testConnection() {
        testJob?.cancel()
        val settings = _state.value.settings
        val provider = settings.provider
        val setup = aiCheckedSetup(settings, provider)
        val generation = keyGeneration
        _state.update { it.copy(connectionTest = AiConnectionTest.Testing) }
        testJob =
            scope.launch {
                val result =
                    try {
                        request(settings, KEY_CHECK_MESSAGES, AI_TEST_TIMEOUT_MS)
                        AiConnectionTest.Passed
                    } catch (error: AiChatException) {
                        // A key the service rejects fails before it is busy, so busy still means the key works.
                        if (error.kind in
                            BUSY_PROBLEMS
                        ) {
                            AiConnectionTest.Passed
                        } else {
                            AiConnectionTest.Failed(AiFailure(error.kind, error.detail))
                        }
                    }
                if (generation != keyGeneration) return@launch
                when {
                    result == AiConnectionTest.Passed -> {
                        _state.update { it.copy(checkedSetups = it.checkedSetups + (provider to setup)) }
                        withContext(io) { if (generation == keyGeneration) keyStore.markChecked(provider, setup) }
                    }
                    result is AiConnectionTest.Failed && result.failure.kind in SETUP_PROBLEMS -> forgetCheck(provider)
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
        _state.update { it.copy(chat = null, failure = null, page = AiPanelPage.CHAT) }
    }

    fun openChat(id: String) {
        val chat = _state.value.savedChats.firstOrNull { it.id == id } ?: return
        cancelRequests()
        _state.update { it.copy(chat = chat, failure = null, page = AiPanelPage.CHAT) }
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
     * question without being shown as its text; [contextLabel] says what it is. [attachments] are
     * the pictures and files the user chose for this question.
     */
    fun send(
        text: String,
        systemPrompt: String,
        context: String? = null,
        contextLabel: String? = null,
        attachments: List<AiAttachment> = emptyList(),
    ) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.sending || !_state.value.ready) return
        val now = clock()
        val message = AiChatMessage(newId(), AiRole.USER, question, now, context, contextLabel, attachments.take(MAX_AI_ATTACHMENTS))
        _state.update { current ->
            val chat = current.chat ?: AiChat(newId(), now, now, emptyList())
            // The model could not read the earlier pictures, so the chat goes on without them.
            val earlier =
                if (current.failure?.kind ==
                    AiErrorKind.UNSUPPORTED_ATTACHMENT
                ) {
                    chat.messages.map(::withoutFiles)
                } else {
                    chat.messages
                }
            current.copy(
                chat = chat.copy(updatedMs = now, messages = earlier + message),
                failure = null,
                page = AiPanelPage.CHAT,
                draftAttachments = if (attachments.isEmpty()) current.draftAttachments else emptyList(),
            )
        }
        ask(systemPrompt)
    }

    /** Keeps the names of a message's pictures and PDFs but no longer sends them; text files stay. */
    private fun withoutFiles(message: AiChatMessage): AiChatMessage =
        if (message.attachments.none { it.kind != AiAttachmentKind.TEXT && it.available }) {
            message
        } else {
            message.copy(attachments = message.attachments.map { if (it.kind == AiAttachmentKind.TEXT) it else it.copy(data = "") })
        }

    /** Asks again after a failure, with the same last question. */
    fun retry(systemPrompt: String) {
        val current = _state.value
        if (current.ready && !current.sending && current.messages.lastOrNull()?.role == AiRole.USER) ask(systemPrompt)
    }

    private fun ask(systemPrompt: String) {
        val history = _state.value.messages
        val settings = _state.value.settings
        val generation = keyGeneration
        _state.update { it.copy(sending = true, failure = null) }
        sendJob =
            scope.launch {
                try {
                    val messages = buildAiRequestMessages(systemPrompt, history)
                    val reply = request(settings, messages, AI_CHAT_TIMEOUT_MS, settings.thinking.effort)
                    val now = clock()
                    _state.update { current ->
                        val chat = current.chat ?: return@update current.copy(sending = false)
                        val answer = AiChatMessage(newId(), AiRole.ASSISTANT, reply, now)
                        current.copy(chat = chat.copy(updatedMs = now, messages = chat.messages + answer), sending = false)
                    }
                } catch (error: AiChatException) {
                    val failure = AiFailure(error.kind, error.detail)
                    _state.update { it.copy(sending = false, failure = failure) }
                    // A key that stopped working goes back to the check card, which says why, unless
                    // the key was replaced while the question was on its way.
                    if (error.kind == AiErrorKind.INVALID_KEY && generation == keyGeneration) {
                        forgetCheck(settings.provider)
                        if (_state.value.settings.provider == settings.provider) {
                            _state.update { it.copy(connectionTest = AiConnectionTest.Failed(failure)) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    _state.update { it.copy(sending = false) }
                    throw cancelled
                }
                persistChats()
            }
    }

    /** Unlocks the key for this one request only; it is never kept in the state. */
    private suspend fun request(
        settings: AiAssistantSettings,
        messages: List<AiWireMessage>,
        timeoutMs: Long,
        reasoningEffort: String? = null,
    ): String {
        val provider = settings.provider
        val baseUrl = settings.baseUrlFor(provider)
        if (chatCompletionsUrl(baseUrl) == null) throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val key = unlockKey(provider)
        val request = AiChatRequest(baseUrl, settings.modelFor(provider), key, messages, timeoutMs, reasoningEffort)
        for (wait in busyRetryDelaysMs) {
            try {
                return withContext(io) { aiCall { transport.complete(request) } }
            } catch (error: AiChatException) {
                if (error.kind !in BUSY_PROBLEMS) throw error
            }
            delay(wait)
        }
        return withContext(io) { aiCall { transport.complete(request) } }
    }

    /** Any network error becomes an [AiChatException], so no request can crash the app. */
    private inline fun <T> aiCall(block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw aiConnectionFailure(error)
        }

    private suspend fun unlockKey(provider: AiProvider): String =
        when (val stored = withContext(io) { keyStore.load(provider) }) {
            is StoredAiKey.Found -> stored.key
            StoredAiKey.Missing -> throw AiChatException(AiErrorKind.NO_KEY)
            StoredAiKey.Unreadable -> {
                _state.update { it.copy(keyHints = it.keyHints - provider, checkedSetups = it.checkedSetups - provider) }
                throw AiChatException(AiErrorKind.KEY_UNREADABLE)
            }
        }

    private fun forgetCheck(provider: AiProvider) {
        _state.update { it.copy(checkedSetups = it.checkedSetups - provider) }
        scope.launch { withContext(io) { keyStore.clearChecked(provider) } }
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

    private fun changeSetup(change: (AiAssistantSettings) -> AiAssistantSettings) {
        testJob?.cancel()
        updateSettings(change)
        _state.update { it.copy(connectionTest = AiConnectionTest.Idle) }
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
