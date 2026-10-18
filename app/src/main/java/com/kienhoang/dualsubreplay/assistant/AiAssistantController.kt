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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Where memories and instructions live; [AiMemoryStore] in the app. */
internal interface AiMemoryStorage {
    fun load(): AiMemoryData

    fun save(data: AiMemoryData)
}

/** Keeps memory only while the app runs; for tests and previews. */
internal class InMemoryAiMemoryStorage(
    var data: AiMemoryData = AiMemoryData(),
) : AiMemoryStorage {
    override fun load() = data

    override fun save(data: AiMemoryData) {
        this.data = data
    }
}

/** Memory changes from the assistant's actions and their Undo; [AiAssistantController] in the app. */
internal interface AiMemoryKeeper {
    /** Why [action] cannot change memory now, in English, or null. */
    fun memoryRefusal(action: AiAction): String?

    fun changeMemory(action: AiAction): AiMemoryChange
}

private class LoadedAiState(
    val settings: AiAssistantSettings,
    val keys: Map<AiProvider, List<AiSavedKey>>,
    val savedChats: List<AiChat>,
    val memory: AiMemoryData,
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

    /** Saved memories, the instructions box and the Use memory switch. */
    MEMORY,
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
 * Gemini's free `gemini-flash-latest` answers 503 to about half of all requests at busy times, and
 * Anthropic answers 529 "overloaded".
 */
internal val AI_BUSY_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

/** The service answered but was busy: it accepted the key before it looked for a free model. */
private val BUSY_PROBLEMS = setOf(AiErrorKind.SERVER, AiErrorKind.RATE_LIMITED)

/**
 * Problems of one key, not of the service: the same request goes to the service's next key at
 * once. A rate limit counts too, since free limits are per key.
 */
private val KEY_SWITCH_PROBLEMS =
    setOf(AiErrorKind.NO_KEY, AiErrorKind.KEY_UNREADABLE, AiErrorKind.INVALID_KEY, AiErrorKind.NO_CREDIT, AiErrorKind.RATE_LIMITED)

/**
 * OpenRouter's free model picker chooses at random and, for a question with a picture, picks a
 * safety checker about two times in five, so the same question is asked again at once.
 */
internal const val AI_NOT_A_CHAT_MODEL_RETRIES = 4

/** Answers that still prove the key works. */
private val KEY_WORKS_PROBLEMS = BUSY_PROBLEMS + AiErrorKind.NOT_A_CHAT_MODEL

private val KEY_CHECK_MESSAGES =
    listOf(AiWireMessage(AiRole.SYSTEM, "Reply with the single word OK."), AiWireMessage(AiRole.USER, "OK?"))

/** A question with hidden [context], asked from outside the panel; [contextLabel] says what the context is. */
internal data class AiPendingQuestion(
    val text: String,
    val context: String? = null,
    val contextLabel: String? = null,
)

internal data class AiAssistantUiState(
    val settings: AiAssistantSettings = AiAssistantSettings(),
    /** Each service's saved keys, in the order they were added; a service without an entry has no key. */
    val keys: Map<AiProvider, List<AiSavedKey>> = emptyMap(),
    /** The key each service answered with last; questions try it first. Lost when the app closes. */
    val keysInUse: Map<AiProvider, String> = emptyMap(),
    /** The last check of each of the chosen service's keys, by key id; cleared when the service changes. */
    val keyTests: Map<String, AiConnectionTest> = emptyMap(),
    val panelOpen: Boolean = false,
    val page: AiPanelPage = AiPanelPage.CHAT,
    /** Saved chats, newest first. Empty when history is off. */
    val savedChats: List<AiChat> = emptyList(),
    val chat: AiChat? = null,
    val sending: Boolean = false,
    /** The user stopped the last question before it was answered; cleared by the next one. */
    val stopped: Boolean = false,
    /** Why the last question got no answer; cleared by the next one. */
    val failure: AiFailure? = null,
    val connectionTest: AiConnectionTest = AiConnectionTest.Idle,
    val modelCheck: AiModelCheck? = null,
    val modelList: AiModelList = AiModelList.Idle,
    /** Pictures and files chosen for the next question. */
    val draftAttachments: List<AiAttachment> = emptyList(),
    val memory: AiMemoryData = AiMemoryData(),
    /** Whether the next new chat reads and saves memories; the open chat keeps its own choice. */
    val newChatMemory: Boolean = true,
    /** A question asked from elsewhere in the app (the word card's grammar button), sent once the panel can. */
    val pendingQuestion: AiPendingQuestion? = null,
) {
    /** The chosen service's keys. */
    val currentKeys: List<AiSavedKey> get() = keys[settings.provider].orEmpty()
    val hasKey: Boolean get() = currentKeys.isNotEmpty()
    val messages: List<AiChatMessage> get() = chat?.messages.orEmpty()
    val addressValid: Boolean get() = chatCompletionsUrl(settings.baseUrlFor(settings.provider)) != null

    /** A saved key answered through the current service, address and model. */
    val keyChecked: Boolean
        get() = aiCheckedSetup(settings, settings.provider).let { setup -> currentKeys.any { it.checkedSetup == setup } }

    /** The key [provider]'s questions try first: the one that answered last, else the first checked one, else the first. */
    fun keyInUse(provider: AiProvider): String? {
        val saved = keys[provider].orEmpty()
        val setup = aiCheckedSetup(settings, provider)
        return keysInUse[provider]?.takeIf { id -> saved.any { it.id == id } }
            ?: saved.firstOrNull { it.checkedSetup == setup }?.id
            ?: saved.firstOrNull()?.id
    }

    /** The assistant can be asked: on, with a key that has answered through the current setup. */
    val ready: Boolean
        get() = settings.enabled && keyChecked && addressValid

    /** Whether the open chat, or the next new one, reads and saves memories. */
    val memoryUse: AiMemoryUse get() = aiMemoryUse(settings.memoryEnabled, chat?.memory ?: newChatMemory)
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
    private val modelLister: AiModelLister = AiModelLister { _, _, _ -> emptyList() },
    /** Waits before asking a busy service again; each entry is one more try. */
    private val busyRetryDelaysMs: List<Long> = AI_BUSY_RETRY_DELAYS_MS,
    private val memoryStorage: AiMemoryStorage = InMemoryAiMemoryStorage(),
) : AiMemoryKeeper {
    private val _state = MutableStateFlow(AiAssistantUiState())
    val state: StateFlow<AiAssistantUiState> = _state.asStateFlow()
    private var sendJob: Job? = null
    private var testJob: Job? = null
    private var modelJob: Job? = null
    private var listJob: Job? = null

    /** The app's actions; the panel binds them while it is shown. Without them no actions are offered. */
    @Volatile var appActions: AiAppActions? = null

    /** Service, address and model combinations that refused actions; asked without them from then on. */
    private val noToolSetups = mutableSetOf<String>()

    /** The actions that undo done actions, and the actions behind waiting cards, by record id; lost when the app closes. */
    private val undos = mutableMapOf<String, List<AiAction>>()
    private val waitingActions = mutableMapOf<String, AiAction>()

    /** Changes with every saved or removed key, so a check that was already running cannot count for a new key. */
    @Volatile private var keyGeneration = 0

    /** One memory write at a time, each writing the newest memory, so an older one never lands last. */
    private val memoryWrites = Mutex()

    init {
        scope.launch {
            val loaded =
                withContext(io) {
                    val keys = AiProvider.entries.associateWith(keyStore::keys).filterValues { it.isNotEmpty() }
                    val checked =
                        keys
                            .mapNotNull { (provider, saved) ->
                                saved.firstNotNullOfOrNull { it.checkedSetup }?.let { provider to it }
                            }.toMap()
                    val saved = settingsStorage.load()
                    val settings = aiModelsKeptFromChecks(saved, checked)
                    if (settings != saved) settingsStorage.save(settings)
                    val stored = historyStorage.load()
                    val kept = keptAiChats(stored, settings.historyRetention, clock())
                    if (kept != stored) historyStorage.save(kept)
                    LoadedAiState(settings, keys, kept, memoryStorage.load())
                }
            _state.update {
                it.copy(
                    settings = loaded.settings,
                    keys = loaded.keys,
                    savedChats = loaded.savedChats,
                    memory = loaded.memory,
                )
            }
        }
    }

    fun openPanel() = _state.update { it.copy(panelOpen = true) }

    fun closePanel() = _state.update { it.copy(panelOpen = false, page = AiPanelPage.CHAT, pendingQuestion = null) }

    /** Opens the chat with [question] waiting; the panel sends it with [sendPendingQuestion] once it can. */
    fun askFromApp(question: AiPendingQuestion) =
        _state.update { it.copy(panelOpen = true, page = AiPanelPage.CHAT, pendingQuestion = question) }

    /** Sends the waiting question when the assistant is ready and not busy; until then it keeps waiting. */
    fun sendPendingQuestion(systemPrompt: String) {
        val current = _state.value
        val question = current.pendingQuestion ?: return
        if (!current.panelOpen || !current.ready || current.sending) return
        _state.update { it.copy(pendingQuestion = null) }
        send(question.text, systemPrompt, question.context, question.contextLabel)
    }

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

    /** Let's start on the first page: the panel shows the key setup or the chat from now on. The page already told what is new. */
    fun finishIntro() = updateSettings { it.copy(introSeen = true, newsSeen = AI_NEWS_VERSION) }

    /** Don't use AI on the first page: the assistant turns off until it is turned on in More settings. */
    fun declineIntro() {
        updateSettings { it.copy(introSeen = true, newsSeen = AI_NEWS_VERSION) }
        setEnabled(false)
    }

    /** Got it, or Manage memory, on the what's-new card. */
    fun dismissNews() = updateSettings { it.copy(newsSeen = AI_NEWS_VERSION) }

    /** Off: the assistant neither reads nor saves memories; the saved ones stay until deleted. */
    fun setMemoryEnabled(enabled: Boolean) = updateSettings { it.copy(memoryEnabled = enabled) }

    /** The chip on a new, empty chat. */
    fun setNewChatMemory(enabled: Boolean) = _state.update { it.copy(newChatMemory = enabled) }

    fun setInstructions(text: String) = updateMemory { it.copy(instructions = text.trim().take(MAX_AI_INSTRUCTIONS_CHARS)) }

    /** Saves the user's edit; returns false when [text] is empty or too long. */
    fun editMemory(
        id: String,
        text: String,
    ): Boolean {
        val clean = aiMemoryText(text) ?: return false
        updateMemory { data -> data.copy(memories = data.memories.map { if (it.id == id) it.copy(text = clean) else it }) }
        return true
    }

    fun deleteMemory(id: String) = updateMemory { data -> data.copy(memories = data.memories.filterNot { it.id == id }) }

    fun deleteAllMemories() = updateMemory { it.copy(memories = emptyList()) }

    override fun memoryRefusal(action: AiAction): String? = _state.value.let { aiMemoryRefusal(it.memory, action, it.memoryUse) }

    override fun changeMemory(action: AiAction): AiMemoryChange {
        memoryRefusal(action)?.let { return AiMemoryChange.Refused(it) }
        val change = changeAiMemory(_state.value.memory, action, newId, clock())
        if (change is AiMemoryChange.Done) updateMemory { change.data }
        return change
    }

    fun selectProvider(provider: AiProvider) {
        if (provider == _state.value.settings.provider) return
        stopTests()
        modelJob?.cancel()
        updateSettings { it.copy(provider = provider) }
        _state.update { it.copy(connectionTest = AiConnectionTest.Idle, keyTests = emptyMap(), failure = null, modelCheck = null) }
    }

    /** Off by default: on, the assistant may also change its own settings, keys, saved words and reset settings. */
    fun setFullAccess(enabled: Boolean) = updateSettings { it.copy(fullAccess = enabled) }

    fun setThinking(thinking: AiThinking) = updateSettings { it.copy(thinking = thinking) }

    /**
     * Switches the chat to [model] once it has answered one tiny request with a saved key, so a
     * model the service does not offer never breaks a working chat; the current model keeps
     * answering meanwhile. A model that fails stays unchosen, and [AiAssistantUiState.modelCheck] says why.
     */
    fun chooseModel(model: String) {
        val name = model.trim()
        val settings = _state.value.settings
        modelJob?.cancel()
        if (name.isEmpty() || name == settings.modelFor(settings.provider)) {
            _state.update { it.copy(modelCheck = null) }
            return
        }
        _state.update { it.copy(modelCheck = AiModelCheck.Checking(name)) }
        modelJob =
            scope.launch {
                val failure = switchModel(name)
                _state.update { it.copy(modelCheck = failure?.let { AiModelCheck.Failed(name, it) }) }
            }
    }

    /**
     * Tries [model] with one tiny request and switches the chosen service to it when it answers.
     * Returns why it did not answer, or null; a check overtaken by a new key or service changes nothing.
     */
    suspend fun switchModel(model: String): AiFailure? {
        val name = model.trim()
        val settings = _state.value.settings
        val provider = settings.provider
        val candidate = settings.copy(models = settings.models + (provider to name))
        val setup = aiCheckedSetup(candidate, provider)
        val generation = keyGeneration
        try {
            request(candidate, KEY_CHECK_MESSAGES, AI_TEST_TIMEOUT_MS, markChecked = false)
        } catch (error: AiChatException) {
            return AiFailure(error.kind, error.detail)
        }
        if (generation != keyGeneration || _state.value.settings.provider != provider) return null
        updateSettings { it.copy(models = it.models + (provider to name)) }
        _state.value.keyInUse(provider)?.let { markKeyChecked(provider, it, setup) }
        return null
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
                        val models =
                            withEachKey(provider, setup = null) { key, _ ->
                                withContext(io) { aiCall { modelLister.listModels(settings.baseUrlFor(provider), key, provider.format) } }
                            }
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
     * Saves a pasted or typed key, encrypted, after the service's other keys. A key whose start
     * names its service switches to that service, so nobody has to know which one they have.
     * Returns the service, or null when the text cannot be a key.
     */
    fun saveKey(text: String): AiProvider? {
        val provider = prepareKeySave(text) ?: return null
        scope.launch { storeKey(provider, text.trim()) }
        return provider
    }

    /** [saveKey] for the assistant's full access: waits until the key is saved and returns where. */
    suspend fun addKey(text: String): Pair<AiProvider, AiKeyAdded>? {
        val provider = prepareKeySave(text) ?: return null
        return provider to storeKey(provider, text.trim())
    }

    private fun prepareKeySave(text: String): AiProvider? {
        if (!looksLikeAiKey(text)) return null
        val provider = aiProviderForKey(text.trim(), _state.value.settings.provider)
        keyGeneration++
        selectProvider(provider)
        stopTests()
        _state.update { it.copy(connectionTest = AiConnectionTest.Testing, failure = null) }
        return provider
    }

    /** Saves [key] and checks it; a service that already has [MAX_AI_KEYS_PER_SERVICE] keys keeps them. */
    private suspend fun storeKey(
        provider: AiProvider,
        key: String,
    ): AiKeyAdded {
        val added = withContext(io) { keyStore.add(provider, key) }
        val saved = withContext(io) { keyStore.keys(provider) }
        _state.update { it.copy(keys = it.keys + (provider to saved)) }
        when (added) {
            is AiKeyAdded.Saved -> testKeys(added.id)
            AiKeyAdded.Full -> _state.update { it.copy(connectionTest = AiConnectionTest.Idle) }
        }
        return added
    }

    fun removeKey(
        provider: AiProvider,
        id: String,
    ) {
        keyGeneration++
        if (provider == _state.value.settings.provider) stopTests()
        _state.update { current ->
            val rest = current.keys[provider].orEmpty().filterNot { it.id == id }
            current.copy(
                keys = if (rest.isEmpty()) current.keys - provider else current.keys + (provider to rest),
                keysInUse = if (current.keysInUse[provider] == id) current.keysInUse - provider else current.keysInUse,
                keyTests = current.keyTests - id,
                connectionTest = AiConnectionTest.Idle,
            )
        }
        scope.launch { withContext(io) { keyStore.remove(provider, id) } }
    }

    /**
     * Asks for one word with each of the chosen service's keys and its model. Chat opens only after
     * one passes; a wrong key, model or address makes that key unchecked again, while a network
     * hiccup does not.
     */
    fun testConnection() = testKeys(onlyId = null)

    private fun testKeys(onlyId: String?) {
        stopTests()
        val current = _state.value
        val settings = current.settings
        val provider = settings.provider
        val setup = aiCheckedSetup(settings, provider)
        val generation = keyGeneration
        val targets = current.currentKeys.filter { onlyId == null || it.id == onlyId }
        val checking = targets.associate { it.id to AiConnectionTest.Testing }
        _state.update { it.copy(connectionTest = AiConnectionTest.Testing, keyTests = it.keyTests + checking) }
        testJob =
            scope.launch {
                val results =
                    targets.map { saved ->
                        val result = testKey(settings, saved.id)
                        if (generation != keyGeneration) return@launch
                        when {
                            result == AiConnectionTest.Passed -> markKeyChecked(provider, saved.id, setup)
                            result is AiConnectionTest.Failed && result.failure.kind in SETUP_PROBLEMS -> forgetKeyCheck(provider, saved.id)
                        }
                        _state.update { it.copy(keyTests = it.keyTests + (saved.id to result)) }
                        result
                    }
                val tested = targets.zip(results)
                val passed = tested.firstOrNull { (_, result) -> result == AiConnectionTest.Passed }?.first
                val inUse = _state.value.keyInUse(provider)
                // A key in use that failed hands over to one that passed.
                if (passed != null && tested.any { (key, result) -> key.id == inUse && result != AiConnectionTest.Passed }) {
                    _state.update { it.copy(keysInUse = it.keysInUse + (provider to passed.id)) }
                }
                val overall =
                    when {
                        passed != null -> AiConnectionTest.Passed
                        else -> results.firstOrNull() ?: AiConnectionTest.Failed(AiFailure(AiErrorKind.NO_KEY))
                    }
                _state.update { it.copy(connectionTest = overall) }
            }
    }

    /** One key's check; a key the service rejects fails before it is busy, so busy still means the key works. */
    private suspend fun testKey(
        settings: AiAssistantSettings,
        id: String,
    ): AiConnectionTest =
        try {
            requestWithKey(settings, id, KEY_CHECK_MESSAGES, AI_TEST_TIMEOUT_MS)
            AiConnectionTest.Passed
        } catch (error: AiChatException) {
            if (error.kind in KEY_WORKS_PROBLEMS) AiConnectionTest.Passed else AiConnectionTest.Failed(AiFailure(error.kind, error.detail))
        }

    /** Stops a running check; keys it had not reached yet lose their "checking" state. */
    private fun stopTests() {
        testJob?.cancel()
        _state.update { current -> current.copy(keyTests = current.keyTests.filterValues { it != AiConnectionTest.Testing }) }
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
        _state.update { it.copy(chat = null, failure = null, stopped = false, page = AiPanelPage.CHAT, newChatMemory = true) }
    }

    fun openChat(id: String) {
        val chat = _state.value.savedChats.firstOrNull { it.id == id } ?: return
        cancelRequests()
        _state.update { it.copy(chat = chat, failure = null, stopped = false, page = AiPanelPage.CHAT) }
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
            val chat = current.chat ?: AiChat(newId(), now, now, emptyList(), memory = current.newChatMemory)
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
        val current = _state.value
        val history = current.messages
        val chatId = current.chat?.id
        val settings = current.settings
        val generation = keyGeneration
        val setup = aiCheckedSetup(settings, settings.provider)
        val memoryUse = current.memoryUse
        val memories = if (memoryUse == AiMemoryUse.ON) current.memory.memories else emptyList()
        val tools =
            when {
                setup in noToolSetups -> emptyList()
                else ->
                    AI_TOOLS + (if (memoryUse == AiMemoryUse.ON) AI_MEMORY_TOOLS else emptyList()) +
                        (if (settings.fullAccess) AI_FULL_ACCESS_TOOLS else emptyList())
            }
        val prompt =
            systemPrompt + aiMemoryPrompt(current.memory.instructions, memories, memoryUse) +
                (if (settings.fullAccess) aiFullAccessPrompt(current) else "")
        val run =
            AiAnswerRun(appActions, history, tools, memories, newId, settings.fullAccess) { messages, offered ->
                request(settings, messages, AI_CHAT_TIMEOUT_MS, settings.thinking.effort, offered)
            }
        _state.update { it.copy(sending = true, failure = null, stopped = false) }
        sendJob =
            scope.launch {
                try {
                    run.run(prompt)
                    addAnswer(run, chatId)
                } catch (error: AiChatException) {
                    // Actions that already ran still show, with their Undo.
                    if (run.records.isNotEmpty()) addAnswer(run, chatId)
                    val failure = AiFailure(error.kind, error.detail)
                    _state.update { it.copy(sending = false, failure = failure) }
                    // Once no key works any more, the chat goes back to the check card, which says why,
                    // unless a key was added or removed while the question was on its way.
                    val latest = _state.value
                    if (error.kind == AiErrorKind.INVALID_KEY && generation == keyGeneration &&
                        latest.settings.provider == settings.provider && !latest.keyChecked
                    ) {
                        _state.update { it.copy(connectionTest = AiConnectionTest.Failed(failure)) }
                    }
                } catch (cancelled: CancellationException) {
                    if (run.records.isNotEmpty()) {
                        addAnswer(run, chatId)
                        persistChats()
                    }
                    _state.update { it.copy(sending = false) }
                    throw cancelled
                } finally {
                    if (run.toolsRefused) noToolSetups += setup
                }
                persistChats()
            }
    }

    /** Adds the answer to the chat it was asked in, if that chat is still open. */
    private fun addAnswer(
        run: AiAnswerRun,
        chatId: String?,
    ) {
        undos += run.undos
        waitingActions += run.waiting
        val now = clock()
        _state.update { current ->
            val chat = current.chat?.takeIf { it.id == chatId } ?: return@update current.copy(sending = false)
            val answer = AiChatMessage(newId(), AiRole.ASSISTANT, run.text, now, actions = run.records.toList())
            current.copy(chat = chat.copy(updatedMs = now, messages = chat.messages + answer), sending = false)
        }
    }

    /** The button on a waiting card: runs the action now. */
    fun confirmAction(actionId: String) {
        val app = appActions ?: return
        val action = waitingActions.remove(actionId) ?: return
        scope.launch {
            when (val outcome = app.perform(action)) {
                is AiActionOutcome.Done -> {
                    val undoable = outcome.undo.isNotEmpty()
                    if (undoable) undos[actionId] = outcome.undo
                    updateAction(actionId) {
                        it.copy(state = AiActionState.DONE, label = outcome.label, note = outcome.note, undoable = undoable)
                    }
                }
                is AiActionOutcome.Refused -> updateAction(actionId) { it.copy(state = AiActionState.FAILED) }
            }
            persistChats()
        }
    }

    fun cancelAction(actionId: String) {
        waitingActions.remove(actionId) ?: return
        updateAction(actionId) { it.copy(state = AiActionState.CANCELLED) }
        persistChats()
    }

    /** Puts back what an action changed, through the actions bound now. */
    fun undoAction(actionId: String) {
        val app = appActions ?: return
        val undo = undos.remove(actionId) ?: return
        scope.launch {
            // An Undo the app refuses now (the video was closed, for example) only loses its button.
            val undone = undo.map { app.perform(it) }.all { it is AiActionOutcome.Done }
            updateAction(actionId) { it.copy(state = if (undone) AiActionState.UNDONE else it.state, undoable = false) }
            persistChats()
        }
    }

    private fun updateAction(
        actionId: String,
        change: (AiActionRecord) -> AiActionRecord,
    ) = _state.update { current ->
        val chat = current.chat ?: return@update current
        val messages =
            chat.messages.map { message ->
                if (message.actions.none { it.id == actionId }) {
                    message
                } else {
                    message.copy(actions = message.actions.map { if (it.id == actionId) change(it) else it })
                }
            }
        current.copy(chat = chat.copy(messages = messages))
    }

    /**
     * Asks the service, trying its keys from the one in use until one answers. Each key is unlocked
     * for its one request only and never kept in the state. [markChecked] records the setup on the
     * key that answered.
     */
    private suspend fun request(
        settings: AiAssistantSettings,
        messages: List<AiWireMessage>,
        timeoutMs: Long,
        reasoningEffort: String? = null,
        tools: List<AiTool> = emptyList(),
        markChecked: Boolean = true,
    ): AiReply {
        val provider = settings.provider
        val baseUrl = settings.baseUrlFor(provider)
        if (chatCompletionsUrl(baseUrl) == null) throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val setup = aiCheckedSetup(settings, provider).takeIf { markChecked }
        return withEachKey(provider, setup) { key, last ->
            complete(
                AiChatRequest(baseUrl, settings.modelFor(provider), key, messages, timeoutMs, reasoningEffort, tools, provider.format),
                waitWhenRateLimited = last,
            )
        }
    }

    /** One key only, for its check. */
    private suspend fun requestWithKey(
        settings: AiAssistantSettings,
        id: String,
        messages: List<AiWireMessage>,
        timeoutMs: Long,
    ): AiReply {
        val provider = settings.provider
        val baseUrl = settings.baseUrlFor(provider)
        if (chatCompletionsUrl(baseUrl) == null) throw AiChatException(AiErrorKind.BAD_ADDRESS)
        val key = unlockKey(provider, id)
        return complete(AiChatRequest(baseUrl, settings.modelFor(provider), key, messages, timeoutMs, null, emptyList(), provider.format))
    }

    /**
     * Runs [call] with [provider]'s keys, the one in use first, until one answers; a key that is
     * rejected, out of credit or rate-limited hands over to the next at once. The key that answers
     * is in use from then on. When every key fails, the last key's error is thrown.
     */
    private suspend fun <T> withEachKey(
        provider: AiProvider,
        setup: String?,
        call: suspend (key: String, last: Boolean) -> T,
    ): T {
        val current = _state.value
        val saved = current.keys[provider].orEmpty()
        val start = saved.indexOfFirst { it.id == current.keyInUse(provider) }.coerceAtLeast(0)
        val ordered = saved.drop(start) + saved.take(start)
        var failure = AiChatException(AiErrorKind.NO_KEY)
        for ((index, key) in ordered.withIndex()) {
            try {
                val result = call(unlockKey(provider, key.id), index == ordered.lastIndex)
                keyAnswered(provider, key.id, setup)
                return result
            } catch (error: AiChatException) {
                if (error.kind !in KEY_SWITCH_PROBLEMS) throw error
                if (error.kind == AiErrorKind.INVALID_KEY) forgetKeyCheck(provider, key.id)
                failure = error
            }
        }
        throw failure
    }

    /** Asks again while the model is busy; a rate limit waits only when no other key is left to try. */
    private suspend fun complete(
        request: AiChatRequest,
        waitWhenRateLimited: Boolean = true,
    ): AiReply {
        val busyWaits = busyRetryDelaysMs.iterator()
        var wrongModelAnswers = 0
        while (true) {
            try {
                return withContext(io) { aiCall { transport.complete(request) } }
            } catch (error: AiChatException) {
                val waits = error.kind in BUSY_PROBLEMS && (error.kind != AiErrorKind.RATE_LIMITED || waitWhenRateLimited)
                when {
                    error.kind == AiErrorKind.NOT_A_CHAT_MODEL && wrongModelAnswers++ < AI_NOT_A_CHAT_MODEL_RETRIES -> Unit
                    waits && busyWaits.hasNext() -> delay(busyWaits.next())
                    else -> throw error
                }
            }
        }
    }

    /** Any network error becomes an [AiChatException], so no request can crash the app. */
    private inline fun <T> aiCall(block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw aiConnectionFailure(error)
        }

    private suspend fun unlockKey(
        provider: AiProvider,
        id: String,
    ): String =
        when (val stored = withContext(io) { keyStore.load(provider, id) }) {
            is StoredAiKey.Found -> stored.key
            StoredAiKey.Missing -> throw AiChatException(AiErrorKind.NO_KEY)
            StoredAiKey.Unreadable -> {
                _state.update { current ->
                    val rest = current.keys[provider].orEmpty().filterNot { it.id == id }
                    current.copy(keys = if (rest.isEmpty()) current.keys - provider else current.keys + (provider to rest))
                }
                throw AiChatException(AiErrorKind.KEY_UNREADABLE)
            }
        }

    /** The key answered: questions try it first from now on, and [setup], when given, counts as checked. */
    private suspend fun keyAnswered(
        provider: AiProvider,
        id: String,
        setup: String?,
    ) {
        _state.update { it.copy(keysInUse = it.keysInUse + (provider to id)) }
        val known =
            _state.value.keys[provider]
                .orEmpty()
                .firstOrNull { it.id == id }
        if (setup != null && known != null && known.checkedSetup != setup) markKeyChecked(provider, id, setup)
    }

    private suspend fun markKeyChecked(
        provider: AiProvider,
        id: String,
        setup: String,
    ) {
        updateKey(provider, id) { it.copy(checkedSetup = setup) }
        withContext(io) { keyStore.markChecked(provider, id, setup) }
    }

    private fun forgetKeyCheck(
        provider: AiProvider,
        id: String,
    ) {
        updateKey(provider, id) { it.copy(checkedSetup = null) }
        scope.launch { withContext(io) { keyStore.clearChecked(provider, id) } }
    }

    private fun updateKey(
        provider: AiProvider,
        id: String,
        change: (AiSavedKey) -> AiSavedKey,
    ) = _state.update { current ->
        val saved = current.keys[provider] ?: return@update current
        current.copy(keys = current.keys + (provider to saved.map { if (it.id == id) change(it) else it }))
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
        stopTests()
        updateSettings(change)
        _state.update { it.copy(connectionTest = AiConnectionTest.Idle) }
    }

    /** Changes memory or instructions and writes them; the newest state is what lands in the file. */
    private fun updateMemory(change: (AiMemoryData) -> AiMemoryData) {
        _state.update { it.copy(memory = change(it.memory)) }
        scope.launch { memoryWrites.withLock { withContext(io) { memoryStorage.save(_state.value.memory) } } }
    }

    private fun updateSettings(change: (AiAssistantSettings) -> AiAssistantSettings) {
        val updated = change(_state.value.settings)
        _state.update { it.copy(settings = updated) }
        scope.launch { withContext(io) { settingsStorage.save(updated) } }
    }

    /**
     * The Stop button: drops the question's request while it waits for an answer. The question stays
     * in the chat, so it can be asked again; actions that already ran keep their Undo.
     */
    fun stop() {
        if (!_state.value.sending) return
        cancelRequests()
        _state.update { it.copy(stopped = true) }
        persistChats()
    }

    private fun cancelRequests() {
        sendJob?.cancel()
        sendJob = null
        _state.update { it.copy(sending = false) }
    }
}
