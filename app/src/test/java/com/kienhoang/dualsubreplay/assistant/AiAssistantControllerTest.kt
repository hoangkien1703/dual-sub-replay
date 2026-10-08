package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemorySettings(
    var value: AiAssistantSettings = AiAssistantSettings(),
) : AiSettingsStorage {
    override fun load() = value

    override fun save(settings: AiAssistantSettings) {
        value = settings
    }
}

private class MemoryHistory(
    var chats: List<AiChat> = emptyList(),
) : AiHistoryStorage {
    var cleared = 0

    override fun load() = chats

    override fun save(chats: List<AiChat>) {
        this.chats = chats
    }

    override fun clear() {
        chats = emptyList()
        cleared++
    }
}

/** Answers every request at once with [reply], or fails with [failure]; or waits for [pending]. */
private class FakeTransport : AiChatTransport {
    val requests = mutableListOf<AiChatRequest>()
    var reply = "OK"
    var failure: AiChatException? = null
    var pending: CompletableDeferred<String>? = null

    override suspend fun complete(request: AiChatRequest): String {
        requests += request
        failure?.let { throw it }
        return pending?.await() ?: reply
    }
}

class AiAssistantControllerTest {
    private val geminiKey = "AIza-FAKE-test-key-for-unit-tests-rstu"
    private val openAiKey = "sk-proj-abcdefghijklmnop1234"
    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day
    private var ids = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val transport = FakeTransport()
    private val secrets = MapStorage()
    private val keyStore = AiKeyStore(secrets, FakeCipher())
    private val settings = MemorySettings()
    private val history = MemoryHistory()

    private val listedModels = mutableListOf<String>()
    private var modelListFailure: AiChatException? = null

    private fun controller() =
        AiAssistantController(
            scope = scope,
            transport = transport,
            keyStore = keyStore,
            settingsStorage = settings,
            historyStorage = history,
            io = Dispatchers.Unconfined,
            clock = { now },
            newId = { "id${ids++}" },
            modelLister = { baseUrl, apiKey ->
                listedModels += "$baseUrl $apiKey"
                modelListFailure?.let { throw it }
                listOf(AiModelInfo("gemini-flash-latest"), AiModelInfo("gemini-pro-latest"), AiModelInfo("text-embedding-004"))
            },
        )

    private fun savedChat(
        id: String,
        daysAgo: Int,
    ) = AiChat(id, now - daysAgo * day, now - daysAgo * day, listOf(AiChatMessage("$id-q", AiRole.USER, "q", now - daysAgo * day)))

    /** A Gemini key that already answered through the current settings, as after a passed check. */
    private fun checkedGeminiKey() {
        keyStore.save(AiProvider.GEMINI, geminiKey)
        keyStore.markChecked(AiProvider.GEMINI, aiCheckedSetup(settings.value, AiProvider.GEMINI))
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun withoutAKeyNothingIsSent() {
        val controller = controller()
        assertFalse(controller.state.value.ready)
        controller.send("What does に mean?", "guide")
        assertTrue(
            controller.state.value.messages
                .isEmpty(),
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun aKeyThatHasNotAnsweredYetCannotChatUntilItsCheckPasses() {
        keyStore.save(AiProvider.GEMINI, geminiKey)
        val controller = controller()
        assertTrue(controller.state.value.hasKey)
        assertFalse(controller.state.value.ready)
        controller.send("Hi", "guide")
        assertTrue(transport.requests.isEmpty())
        controller.testConnection()
        assertTrue(controller.state.value.ready)
        assertEquals(AI_TEST_TIMEOUT_MS, transport.requests.single().timeoutMs)
        controller.send("Hi", "guide")
        assertEquals(AI_CHAT_TIMEOUT_MS, transport.requests.last().timeoutMs)
        assertEquals(2, controller.state.value.messages.size)
    }

    @Test
    fun aPassedCheckIsRememberedAfterARestart() {
        controller().saveKey(geminiKey)
        assertEquals(aiCheckedSetup(settings.value, AiProvider.GEMINI), keyStore.checkedSetup(AiProvider.GEMINI))
        assertTrue(controller().state.value.ready)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aFailedCheckKeepsChatClosedAndSaysWhy() {
        transport.failure = AiChatException(AiErrorKind.TIMEOUT, "InterruptedIOException: timeout")
        val controller = controller()
        controller.saveKey(geminiKey)
        val state = controller.state.value
        assertFalse(state.ready)
        assertEquals(AiConnectionTest.Failed(AiFailure(AiErrorKind.TIMEOUT, "InterruptedIOException: timeout")), state.connectionTest)
        transport.failure = null
        controller.testConnection()
        assertTrue(controller.state.value.ready)
    }

    @Test
    fun aNewModelIsCheckedAgainButANetworkHiccupKeepsAnEarlierPass() {
        checkedGeminiKey()
        val controller = controller()
        assertTrue(controller.state.value.ready)
        transport.failure = AiChatException(AiErrorKind.TIMEOUT)
        controller.testConnection()
        assertTrue(controller.state.value.ready)
        transport.failure = null
        controller.setModel(AiProvider.GEMINI, "gemini-pro-latest")
        assertFalse(controller.state.value.ready)
        assertEquals(AiConnectionTest.Idle, controller.state.value.connectionTest)
        controller.testConnection()
        assertTrue(controller.state.value.ready)
        assertEquals("gemini-pro-latest", transport.requests.last().model)
        transport.failure = AiChatException(AiErrorKind.UNKNOWN_MODEL)
        controller.testConnection()
        assertFalse(controller.state.value.ready)
        assertNull(keyStore.checkedSetup(AiProvider.GEMINI))
    }

    @Test
    fun aKeyRejectedDuringChatGoesBackToTheCheck() {
        checkedGeminiKey()
        val controller = controller()
        transport.failure = AiChatException(AiErrorKind.INVALID_KEY, "API key expired.")
        controller.send("Hi", "guide")
        val state = controller.state.value
        assertFalse(state.ready)
        assertTrue(state.hasKey)
        assertEquals(AiConnectionTest.Failed(AiFailure(AiErrorKind.INVALID_KEY, "API key expired.")), state.connectionTest)
        assertNull(keyStore.checkedSetup(AiProvider.GEMINI))
    }

    @Test
    fun choosingAServiceInThePickerKeepsItsOwnKeyAndCheck() {
        checkedGeminiKey()
        val controller = controller()
        controller.selectProvider(AiProvider.OPENCODE_ZEN)
        assertFalse(controller.state.value.hasKey)
        assertEquals(AiProvider.OPENCODE_ZEN, controller.saveKey("sk-0123456789abcdefghijklmnop"))
        assertEquals(AiProvider.OPENCODE_ZEN.baseUrl, transport.requests.last().baseUrl)
        assertEquals("big-pickle", transport.requests.last().model)
        controller.selectProvider(AiProvider.GEMINI)
        assertTrue(controller.state.value.ready)
    }

    @Test
    fun aPastedKeyPicksItsServiceAndTestsTheConnection() {
        settings.value = AiAssistantSettings(provider = AiProvider.OPENAI)
        val controller = controller()
        assertEquals(AiProvider.GEMINI, controller.saveKey("  $geminiKey "))
        val state = controller.state.value
        assertEquals(AiProvider.GEMINI, state.settings.provider)
        assertEquals(AiProvider.GEMINI, settings.value.provider)
        assertEquals(mapOf(AiProvider.GEMINI to "rstu"), state.keyHints)
        assertTrue(state.keyChecked)
        assertTrue(state.ready)
        assertEquals(AiConnectionTest.Passed, state.connectionTest)
        val test = transport.requests.single()
        assertEquals(geminiKey, test.apiKey)
        assertEquals(AiProvider.GEMINI.baseUrl, test.baseUrl)
        assertEquals(AiProvider.GEMINI.defaultModel, test.model)
        assertFalse(secrets.values.values.any { geminiKey in it })
    }

    @Test
    fun textThatCannotBeAKeyIsRefused() {
        val controller = controller()
        assertNull(controller.saveKey("hello"))
        assertTrue(
            controller.state.value.keyHints
                .isEmpty(),
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun aCustomServiceKeepsWhateverKeyItIsGiven() {
        settings.value = AiAssistantSettings(provider = AiProvider.CUSTOM, customBaseUrl = "https://llm.example.com/v1")
        val controller = controller()
        assertEquals(AiProvider.CUSTOM, controller.saveKey(openAiKey))
        assertEquals(AiProvider.CUSTOM, controller.state.value.settings.provider)
        assertEquals("https://llm.example.com/v1", transport.requests.single().baseUrl)
    }

    @Test
    fun aFailedTestSaysWhy() {
        transport.failure = AiChatException(AiErrorKind.INVALID_KEY, "API key not valid.")
        val controller = controller()
        controller.saveKey(geminiKey)
        assertEquals(
            AiConnectionTest.Failed(AiFailure(AiErrorKind.INVALID_KEY, "API key not valid.")),
            controller.state.value.connectionTest,
        )
        assertFalse(controller.state.value.ready)
    }

    @Test
    fun aQuestionGetsAnAnswerAndTheChatIsSaved() {
        checkedGeminiKey()
        val controller = controller()
        transport.reply = "It marks the target."
        controller.send("  What does に mean?  ", "guide", context = "line: 学校に行く", contextLabel = "Current line")
        val state = controller.state.value
        assertFalse(state.sending)
        assertNull(state.failure)
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT), state.messages.map { it.role })
        assertEquals("What does に mean?", state.messages.first().text)
        assertEquals("Current line", state.messages.first().contextLabel)
        assertEquals("It marks the target.", state.messages.last().text)
        val sent = transport.requests.single().messages
        assertEquals("guide", sent.first().content)
        assertEquals("What does に mean?\n\nline: 学校に行く", sent.last().content)
        assertEquals(listOf(state.chat), history.chats)
        assertEquals(listOf(state.chat), state.savedChats)
    }

    @Test
    fun aFailureKeepsTheQuestionAndRetryAsksAgain() {
        checkedGeminiKey()
        val controller = controller()
        transport.failure = AiChatException(AiErrorKind.RATE_LIMITED)
        controller.send("Why?", "guide")
        assertEquals(
            AiErrorKind.RATE_LIMITED,
            controller.state.value.failure
                ?.kind,
        )
        assertEquals(1, controller.state.value.messages.size)
        transport.failure = null
        controller.retry("guide")
        assertNull(controller.state.value.failure)
        assertEquals(
            listOf("Why?", "OK"),
            controller.state.value.messages
                .map { it.text },
        )
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun oneQuestionAtATimeAndTurningOffCancelsIt() {
        checkedGeminiKey()
        val controller = controller()
        controller.openPanel()
        transport.pending = CompletableDeferred()
        controller.send("First", "guide")
        assertTrue(controller.state.value.sending)
        controller.send("Second", "guide")
        assertEquals(
            listOf("First"),
            controller.state.value.messages
                .map { it.text },
        )
        controller.setEnabled(false)
        val state = controller.state.value
        assertFalse(state.sending)
        assertFalse(state.panelOpen)
        assertFalse(settings.value.enabled)
        assertFalse(state.ready)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aKeyThePhoneCanNoLongerUnlockAsksForANewOne() {
        val cipher = FakeCipher()
        val store = AiKeyStore(secrets, cipher)
        store.save(AiProvider.GEMINI, geminiKey)
        store.markChecked(AiProvider.GEMINI, aiCheckedSetup(settings.value, AiProvider.GEMINI))
        val controller =
            AiAssistantController(scope, transport, store, settings, history, Dispatchers.Unconfined, { now }, { "id${ids++}" })
        assertTrue(controller.state.value.hasKey)
        cipher.broken = true
        controller.send("Hi", "guide")
        assertEquals(
            AiErrorKind.KEY_UNREADABLE,
            controller.state.value.failure
                ?.kind,
        )
        assertFalse(controller.state.value.hasKey)
        assertFalse(controller.state.value.ready)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun oldChatsAreDeletedWhenTheAppStarts() {
        history.chats = listOf(savedChat("recent", 2), savedChat("old", 9))
        val controller = controller()
        assertEquals(
            listOf("recent"),
            controller.state.value.savedChats
                .map { it.id },
        )
        assertEquals(listOf("recent"), history.chats.map { it.id })
    }

    @Test
    fun changingHowLongChatsAreKeptAppliesAtOnce() {
        settings.value = AiAssistantSettings(historyRetention = ChatHistoryRetention.FOREVER)
        history.chats = listOf(savedChat("recent", 2), savedChat("month", 20), savedChat("old", 90))
        val controller = controller()
        assertEquals(3, controller.state.value.savedChats.size)
        controller.setHistoryRetention(ChatHistoryRetention.MONTH)
        assertEquals(listOf("recent", "month"), history.chats.map { it.id })
        controller.setHistoryRetention(ChatHistoryRetention.OFF)
        assertTrue(
            controller.state.value.savedChats
                .isEmpty(),
        )
        assertTrue(history.chats.isEmpty())
        assertEquals(ChatHistoryRetention.OFF, settings.value.historyRetention)
    }

    @Test
    fun withHistoryOffTheOpenChatStaysButNothingIsWritten() {
        settings.value = AiAssistantSettings(historyRetention = ChatHistoryRetention.OFF)
        checkedGeminiKey()
        val controller = controller()
        controller.send("Hi", "guide")
        assertEquals(2, controller.state.value.messages.size)
        assertTrue(
            controller.state.value.savedChats
                .isEmpty(),
        )
        assertTrue(history.chats.isEmpty())
    }

    @Test
    fun chatsCanBeReopenedDeletedOrAllCleared() {
        settings.value = AiAssistantSettings(historyRetention = ChatHistoryRetention.FOREVER)
        history.chats = listOf(savedChat("a", 1), savedChat("b", 2))
        val controller = controller()
        controller.showPage(AiPanelPage.HISTORY)
        controller.openChat("b")
        assertEquals(
            "b",
            controller.state.value.chat
                ?.id,
        )
        assertEquals(AiPanelPage.CHAT, controller.state.value.page)
        controller.deleteChat("b")
        assertNull(controller.state.value.chat)
        assertEquals(listOf("a"), history.chats.map { it.id })
        controller.deleteHistory()
        assertTrue(
            controller.state.value.savedChats
                .isEmpty(),
        )
        assertEquals(1, history.cleared)
    }

    @Test
    fun removingAKeyForgetsIt() {
        checkedGeminiKey()
        val controller = controller()
        controller.removeKey(AiProvider.GEMINI)
        assertFalse(controller.state.value.hasKey)
        assertFalse(controller.state.value.ready)
        assertEquals(StoredAiKey.Missing, keyStore.load(AiProvider.GEMINI))
        assertNull(keyStore.checkedSetup(AiProvider.GEMINI))
    }

    @Test
    fun aBadCustomAddressIsReportedWithoutSending() {
        settings.value = AiAssistantSettings(provider = AiProvider.CUSTOM, customBaseUrl = "http://192.168.1.2:11434/v1")
        keyStore.save(AiProvider.CUSTOM, openAiKey)
        val controller = controller()
        assertFalse(controller.state.value.ready)
        controller.testConnection()
        assertEquals(AiConnectionTest.Failed(AiFailure(AiErrorKind.BAD_ADDRESS)), controller.state.value.connectionTest)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun aChosenModelIsTriedFirstAndOnlyUsedIfItAnswers() {
        checkedGeminiKey()
        val controller = controller()
        transport.failure = AiChatException(AiErrorKind.UNKNOWN_MODEL, "models/gemini-9 is not found.")
        controller.chooseModel("gemini-9")
        var state = controller.state.value
        assertEquals(
            AiModelCheck.Failed("gemini-9", AiFailure(AiErrorKind.UNKNOWN_MODEL, "models/gemini-9 is not found.")),
            state.modelCheck,
        )
        assertEquals("gemini-flash-latest", state.settings.modelFor(AiProvider.GEMINI))
        assertTrue(state.ready)
        transport.failure = null
        controller.chooseModel(" gemini-pro-latest ")
        state = controller.state.value
        assertNull(state.modelCheck)
        assertEquals("gemini-pro-latest", settings.value.modelFor(AiProvider.GEMINI))
        assertTrue(state.ready)
        assertEquals(aiCheckedSetup(settings.value, AiProvider.GEMINI), keyStore.checkedSetup(AiProvider.GEMINI))
        assertEquals(listOf("gemini-9", "gemini-pro-latest"), transport.requests.map { it.model })
    }

    @Test
    fun theChosenThinkingLevelGoesWithEveryQuestionButNotTheChecks() {
        checkedGeminiKey()
        val controller = controller()
        controller.setThinking(AiThinking.HIGH)
        assertEquals(AiThinking.HIGH, settings.value.thinking)
        controller.send("Why?", "guide")
        assertEquals("high", transport.requests.last().reasoningEffort)
        controller.testConnection()
        assertNull(transport.requests.last().reasoningEffort)
    }

    @Test
    fun theModelListComesFromTheServiceWithoutModelsThatCannotChat() {
        checkedGeminiKey()
        val controller = controller()
        controller.loadModels()
        assertEquals(
            AiModelList.Loaded(AiProvider.GEMINI, listOf(AiModelInfo("gemini-flash-latest"), AiModelInfo("gemini-pro-latest"))),
            controller.state.value.modelList,
        )
        assertEquals(listOf("${AiProvider.GEMINI.baseUrl} $geminiKey"), listedModels)
        modelListFailure = AiChatException(AiErrorKind.NETWORK, "UnknownHostException")
        controller.loadModels()
        assertEquals(AiModelList.Failed(AiFailure(AiErrorKind.NETWORK, "UnknownHostException")), controller.state.value.modelList)
    }

    private fun picture(name: String) = AiAttachment(name, AiAttachmentKind.PICTURE, "data:image/jpeg;base64,AAAA")

    @Test
    fun chosenFilesWaitUnderTheChatBoxUpToTheLimit() {
        val controller = controller()
        assertTrue(controller.addAttachments(listOf(picture("1.jpg"), picture("2.jpg"))))
        assertFalse(controller.addAttachments((3..5).map { picture("$it.jpg") }))
        assertEquals(
            listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg"),
            controller.state.value.draftAttachments
                .map { it.name },
        )
        controller.removeAttachment(1)
        assertEquals(
            listOf("1.jpg", "3.jpg", "4.jpg"),
            controller.state.value.draftAttachments
                .map { it.name },
        )
    }

    @Test
    fun filesGoWithTheirQuestionAndLeaveTheChatBox() {
        checkedGeminiKey()
        val controller = controller()
        controller.addAttachments(listOf(picture("page.jpg")))
        controller.send("What does this say?", "guide", attachments = controller.state.value.draftAttachments)
        assertTrue(
            controller.state.value.draftAttachments
                .isEmpty(),
        )
        val question =
            transport.requests
                .last()
                .messages
                .last()
        assertEquals("What does this say?", question.content)
        assertEquals(listOf("page.jpg"), question.attachments.map { it.name })
        assertEquals(
            listOf("page.jpg"),
            controller.state.value.messages
                .first()
                .attachments
                .map { it.name },
        )
    }

    @Test
    fun afterAModelRefusesPicturesTheChatGoesOnWithoutThem() {
        checkedGeminiKey()
        val controller = controller()
        transport.failure = AiChatException(AiErrorKind.UNSUPPORTED_ATTACHMENT, "No endpoints found that support image input")
        controller.send("What is this?", "guide", attachments = listOf(picture("page.jpg")))
        assertEquals(
            AiErrorKind.UNSUPPORTED_ATTACHMENT,
            controller.state.value.failure
                ?.kind,
        )
        // Try again sends the picture again, for a model that has just been changed to one that reads pictures.
        controller.retry("guide")
        assertEquals(
            listOf("page.jpg"),
            transport.requests
                .last()
                .messages
                .last()
                .attachments
                .map { it.name },
        )
        transport.failure = null
        controller.send("Then just say hello", "guide")
        assertTrue(
            transport.requests
                .last()
                .messages
                .all { it.attachments.isEmpty() },
        )
        // The message still shows which picture was sent.
        assertEquals(
            listOf("page.jpg"),
            controller.state.value.messages
                .first()
                .attachments
                .map { it.name },
        )
    }
}
