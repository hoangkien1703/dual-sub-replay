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

/**
 * Answers every request at once with [reply], or fails with [failure]; or waits for [pending].
 * Queued [replies] (for example tool calls) are used first, before any failure.
 */
private class FakeTransport : AiChatTransport {
    val requests = mutableListOf<AiChatRequest>()
    var reply = "OK"
    var failure: AiChatException? = null
    var pending: CompletableDeferred<String>? = null
    val replies = ArrayDeque<AiReply>()

    /** Thrown once each, before [failure], as a busy service would. */
    val failOnce = ArrayDeque<AiChatException>()

    override suspend fun complete(request: AiChatRequest): AiReply {
        requests += request
        replies.removeFirstOrNull()?.let { return it }
        failOnce.removeFirstOrNull()?.let { throw it }
        failure?.let { throw it }
        return AiReply(pending?.await() ?: reply)
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
    private val memoryStore = InMemoryAiMemoryStorage()

    private val listedModels = mutableListOf<String>()

    /** No waiting and, unless a test sets it, no second try for a busy service. */
    private var busyRetries = emptyList<Long>()
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
            busyRetryDelaysMs = busyRetries,
            memoryStorage = memoryStore,
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
        // The guide, then what memory holds.
        assertEquals("guide\n\nSaved memories: none yet.", sent.first().content)
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

    @Test
    fun aBusyModelIsAskedAgainBeforeTheQuestionFails() {
        checkedGeminiKey()
        busyRetries = listOf(0L, 0L)
        val controller = controller()
        transport.failOnce += AiChatException(AiErrorKind.SERVER, "This model is currently experiencing high demand.")
        transport.failOnce += AiChatException(AiErrorKind.SERVER, "This model is currently experiencing high demand.")
        controller.send("Why?", "guide")
        assertNull(controller.state.value.failure)
        assertEquals(3, transport.requests.size)
        // A wrong key is not asked again.
        transport.failure = AiChatException(AiErrorKind.INVALID_KEY)
        controller.send("Again?", "guide")
        assertEquals(4, transport.requests.size)
    }

    @Test
    fun aKeyCheckThatOnlyFindsTheModelBusyStillPasses() {
        keyStore.save(AiProvider.GEMINI, geminiKey)
        val controller = controller()
        transport.failure = AiChatException(AiErrorKind.SERVER, "This model is currently experiencing high demand.")
        controller.testConnection()
        assertTrue(controller.state.value.ready)
        assertEquals(aiCheckedSetup(settings.value, AiProvider.GEMINI), keyStore.checkedSetup(AiProvider.GEMINI))
        transport.failure = AiChatException(AiErrorKind.INVALID_KEY)
        controller.testConnection()
        assertFalse(controller.state.value.ready)
    }

    @Test
    fun whatsNewShowsOnceForPeopleWhoSawTheIntroBefore() {
        val newUser = controller()
        newUser.finishIntro()
        assertFalse(newUser.state.value.settings.showsNews)

        settings.value = AiAssistantSettings(introSeen = true)
        val existing = controller()
        assertTrue(existing.state.value.settings.showsNews)
        existing.dismissNews()
        assertFalse(existing.state.value.settings.showsNews)
        assertFalse(
            controller()
                .state.value.settings.showsNews,
        )
    }

    @Test
    fun theFirstPageIsAnsweredOnceWithLetsStartOrDontUseAi() {
        val controller = controller()
        assertFalse(controller.state.value.settings.introSeen)
        controller.openPanel()
        controller.finishIntro()
        assertTrue(settings.value.introSeen)
        assertTrue(settings.value.enabled)
        assertTrue(controller.state.value.panelOpen)

        settings.value = AiAssistantSettings()
        val declining = controller()
        declining.openPanel()
        declining.declineIntro()
        assertTrue(settings.value.introSeen)
        assertFalse(settings.value.enabled)
        assertFalse(declining.state.value.panelOpen)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun aSafetyCheckerAnswerIsAskedAgainAtOnce() {
        checkedGeminiKey()
        val controller = controller()
        repeat(AI_NOT_A_CHAT_MODEL_RETRIES) { transport.failOnce += AiChatException(AiErrorKind.NOT_A_CHAT_MODEL) }
        controller.send("What is in this picture?", "guide")
        assertNull(controller.state.value.failure)
        assertEquals(
            "OK",
            controller.state.value.messages
                .last()
                .text,
        )
        assertEquals(AI_NOT_A_CHAT_MODEL_RETRIES + 1, transport.requests.size)
        // A model that only ever labels text gives up and suggests another model.
        transport.failure = AiChatException(AiErrorKind.NOT_A_CHAT_MODEL)
        controller.send("Again?", "guide")
        assertEquals(
            AiErrorKind.NOT_A_CHAT_MODEL,
            controller.state.value.failure
                ?.kind,
        )
        assertEquals(2 * (AI_NOT_A_CHAT_MODEL_RETRIES + 1), transport.requests.size)
    }

    /** Runs every action at once; a setting change can be undone by setting it back to 100. */
    private class RecordingApp : AiAppActions {
        val performed = mutableListOf<AiAction>()
        var refusal: String? = null

        override fun lookAtVideo(linesAround: Int) = "No video is open."

        override fun refusal(action: AiAction): String? = null

        override fun describe(action: AiAction) = AiActionText(aiActionNote(action), "Go")

        override suspend fun perform(action: AiAction): AiActionOutcome {
            refusal?.let { return AiActionOutcome.Refused(it) }
            performed += action
            val undo = if (action is AiAction.ChangeSetting) listOf(action.copy(value = "100")) else emptyList()
            return AiActionOutcome.Done("did: ${aiActionNote(action)}", aiActionNote(action), undo)
        }
    }

    private fun toolCall(
        name: String,
        arguments: String,
    ) = AiToolCall("t$name", name, arguments, """{"id":"t$name","type":"function","function":{"name":"$name","arguments":"{}"}}""")

    @Test
    fun anActionShowsUnderTheAnswerAndUndoPutsItBack() {
        checkedGeminiKey()
        val app = RecordingApp()
        val controller = controller().also { it.appActions = app }
        transport.replies += AiReply("", listOf(toolCall(AI_SETTING_TOOL, """{"setting":"text_size","value":"150"}""")))
        transport.reply = "Bigger now."
        controller.send("Bigger subtitles", "guide")
        val answer =
            controller.state.value.messages
                .last()
        assertEquals("Bigger now.", answer.text)
        val action = answer.actions.single()
        assertEquals(AiActionState.DONE, action.state)
        assertTrue(action.undoable)
        assertEquals(AI_TOOLS + AI_MEMORY_TOOLS, transport.requests.first().tools)

        controller.undoAction(action.id)
        assertEquals(
            listOf<AiAction>(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "150"), AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "100")),
            app.performed,
        )
        val undone =
            controller.state.value.messages
                .last()
                .actions
                .single()
        assertEquals(AiActionState.UNDONE, undone.state)
        assertFalse(undone.undoable)
        // A second tap does nothing, and the saved chat keeps what happened.
        controller.undoAction(action.id)
        assertEquals(2, app.performed.size)
        assertEquals(
            AiActionState.UNDONE,
            history.chats
                .single()
                .messages
                .last()
                .actions
                .single()
                .state,
        )
    }

    @Test
    fun undoRunsThroughTheActionsBoundAtThatTime() {
        checkedGeminiKey()
        val before = RecordingApp()
        val controller = controller().also { it.appActions = before }
        transport.replies +=
            AiReply(
                "",
                listOf(
                    toolCall(AI_SETTING_TOOL, """{"setting":"text_size","value":"150"}"""),
                    toolCall(AI_PLAYBACK_TOOL, """{"action":"pause"}"""),
                ),
            )
        controller.send("Bigger subtitles and pause", "guide")
        val (size, pause) =
            controller.state.value.messages
                .last()
                .actions
        assertFalse(pause.undoable)

        // The screen was rebuilt: without actions bound, Undo waits; after, the new screen's actions run it.
        controller.appActions = null
        controller.undoAction(size.id)
        val after = RecordingApp()
        controller.appActions = after
        controller.undoAction(size.id)
        assertEquals(2, before.performed.size)
        assertEquals(listOf<AiAction>(AiAction.ChangeSetting(AiSetting.TEXT_SIZE, "100")), after.performed)
    }

    @Test
    fun anUndoTheAppRefusesOnlyLosesItsButton() {
        checkedGeminiKey()
        val app = RecordingApp()
        val controller = controller().also { it.appActions = app }
        transport.replies += AiReply("", listOf(toolCall(AI_SETTING_TOOL, """{"setting":"playback_speed","value":"0.5"}""")))
        controller.send("Slower", "guide")
        val action =
            controller.state.value.messages
                .last()
                .actions
                .single()
        app.refusal = "No video is open."
        controller.undoAction(action.id)
        val after =
            controller.state.value.messages
                .last()
                .actions
                .single()
        assertEquals(AiActionState.DONE, after.state)
        assertFalse(after.undoable)
    }

    @Test
    fun aCardRunsItsActionOnlyWhenTapped() {
        checkedGeminiKey()
        val app = RecordingApp()
        val controller = controller().also { it.appActions = app }
        transport.replies +=
            AiReply(
                "",
                listOf(toolCall(AI_SEARCH_TOOL, """{"query":"cats"}"""), toolCall(AI_TRANSLATION_TOOL, """{"target_language":"en"}""")),
            )
        controller.send("Find cat videos and translate to English", "guide")
        val (search, translation) =
            controller.state.value.messages
                .last()
                .actions
        assertEquals(AiActionState.WAITING, search.state)
        assertTrue(app.performed.isEmpty())

        controller.confirmAction(search.id)
        assertEquals(listOf<AiAction>(AiAction.SearchYouTube("cats")), app.performed)
        controller.cancelAction(translation.id)
        controller.confirmAction(translation.id)
        assertEquals(1, app.performed.size)
        assertEquals(
            listOf(AiActionState.DONE, AiActionState.CANCELLED),
            controller.state.value.messages
                .last()
                .actions
                .map { it.state },
        )

        // Read back from the file after a restart, nothing can be undone and an unanswered card is not done.
        transport.replies += AiReply("", listOf(toolCall(AI_SEARCH_TOOL, """{"query":"dogs"}""")))
        controller.send("Dogs too", "guide")
        val saved = decodeAiChats(encodeAiChats(history.chats)).single().messages.filter { it.role == AiRole.ASSISTANT }
        assertEquals(listOf(AiActionState.DONE, AiActionState.CANCELLED), saved[0].actions.map { it.state })
        assertFalse(saved[0].actions[0].undoable)
        assertEquals("did: Search YouTube for \"cats\"", saved[0].actions[0].label)
        assertEquals("Go", saved[0].actions[0].button)
        assertEquals(AiActionKind.VIDEO, saved[0].actions[0].kind)
        assertEquals(AiActionState.CANCELLED, saved[1].actions.single().state)
    }

    @Test
    fun anActionThatRanStaysVisibleWhenTheAnswerFails() {
        checkedGeminiKey()
        val app = RecordingApp()
        val controller = controller().also { it.appActions = app }
        transport.replies += AiReply("", listOf(toolCall(AI_PLAYBACK_TOOL, """{"action":"pause"}""")))
        transport.failOnce += AiChatException(AiErrorKind.NETWORK)
        controller.send("Pause it", "guide")
        val state = controller.state.value
        assertEquals(AiErrorKind.NETWORK, state.failure?.kind)
        assertEquals(
            AiActionState.DONE,
            state.messages
                .last()
                .actions
                .single()
                .state,
        )
        assertEquals(listOf<AiAction>(AiAction.Playback(AiPlayback.PAUSE)), app.performed)
        assertFalse(state.sending)
    }

    @Test
    fun aModelThatRefusedActionsIsAskedWithoutThemFromThenOn() {
        checkedGeminiKey()
        val controller = controller().also { it.appActions = RecordingApp() }
        transport.failOnce += AiChatException(AiErrorKind.UNSUPPORTED_TOOLS)
        controller.send("Hi", "guide")
        assertEquals(
            "OK",
            controller.state.value.messages
                .last()
                .text,
        )
        controller.send("Again", "guide")
        assertEquals(listOf(true, false, false), transport.requests.map { it.tools.isNotEmpty() })
    }

    /** Changes memory through the controller, as the app's actions do. */
    private class MemoryApp(
        private val keeper: AiMemoryKeeper,
    ) : AiAppActions {
        override fun lookAtVideo(linesAround: Int) = "No video is open."

        override fun refusal(action: AiAction) = keeper.memoryRefusal(action)

        override fun describe(action: AiAction) = AiActionText(aiActionNote(action), "Save")

        override suspend fun perform(action: AiAction): AiActionOutcome =
            when (val change = keeper.changeMemory(action)) {
                is AiMemoryChange.Refused -> AiActionOutcome.Refused(change.reason)
                is AiMemoryChange.Done -> AiActionOutcome.Done(change.event.name, change.note, change.undo)
            }
    }

    private fun AiAssistantController.lastActions() =
        state.value.messages
            .last()
            .actions

    private fun systemPrompt(request: AiChatRequest) = request.messages.first().content

    @Test
    fun aSavedMemoryShowsWithUndoAndTheNextQuestionSeesIt() {
        checkedGeminiKey()
        val controller = controller()
        controller.appActions = MemoryApp(controller)
        transport.replies += AiReply("", listOf(toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3"}""")))
        transport.reply = "Good luck!"
        controller.send("I'm studying for JLPT N3", "guide")
        assertTrue("Saved memories: none yet." in systemPrompt(transport.requests.first()))
        val saved = controller.lastActions().single()
        assertEquals(AiActionKind.MEMORY, saved.kind)
        assertEquals("SAVED", saved.label)
        assertTrue(saved.undoable)
        assertEquals(listOf("Studies for JLPT N3"), memoryStore.data.memories.map { it.text })

        controller.undoAction(saved.id)
        assertTrue(memoryStore.data.memories.isEmpty())
        assertEquals(AiActionState.UNDONE, controller.lastActions().single().state)

        transport.replies += AiReply("", listOf(toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3 in December"}""")))
        controller.send("The exam is in December", "guide")
        controller.newChat()
        controller.send("What level am I?", "guide")
        val prompt = systemPrompt(transport.requests.last())
        assertTrue(prompt, prompt.startsWith("guide"))
        assertTrue(prompt, "(notes from what the user told you earlier; 1 of 30 used):\n1. Studies for JLPT N3 in December" in prompt)
    }

    @Test
    fun updatesAndForgetsUseTheNumberedListAndUndoPutsItBack() {
        val n4 = AiMemory("m1", "Studies for JLPT N4", 1)
        val short = AiMemory("m2", "Likes short answers", 2)
        memoryStore.data = AiMemoryData(memories = listOf(n4, short))
        checkedGeminiKey()
        val controller = controller()
        controller.appActions = MemoryApp(controller)
        transport.replies +=
            AiReply(
                "",
                listOf(
                    toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"Studies for JLPT N3","replaces":1}"""),
                    toolCall(AI_FORGET_MEMORY_TOOL, """{"number":2}"""),
                ),
            )
        controller.send("I passed N4, now it's N3. Forget that I like short answers.", "guide")
        assertTrue("1. Studies for JLPT N4\n2. Likes short answers" in systemPrompt(transport.requests.first()))
        assertEquals(listOf("Studies for JLPT N3"), memoryStore.data.memories.map { it.text })
        val (updated, forgot) = controller.lastActions()
        assertEquals(listOf("UPDATED", "FORGOT"), listOf(updated.label, forgot.label))

        controller.undoAction(forgot.id)
        controller.undoAction(updated.id)
        assertEquals(listOf(n4, short), memoryStore.data.memories)
    }

    @Test
    fun aFullMemoryAsksWhichToReplaceAndADuplicateIsNotSavedTwice() {
        memoryStore.data = AiMemoryData(memories = (1..MAX_AI_MEMORIES).map { AiMemory("m$it", "Memory $it", it.toLong()) })
        checkedGeminiKey()
        val controller = controller()
        controller.appActions = MemoryApp(controller)
        transport.replies += AiReply("", listOf(toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"A new fact"}""")))
        transport.replies += AiReply("", listOf(toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"memory  3"}""")))
        controller.send("Remember a new fact", "guide")
        val results = transport.requests[1].messages.filter { it.role == AiRole.TOOL }
        assertTrue(results.first().content, results.first().content.startsWith("Not done: Memory is full (30 saved)."))
        val already = controller.lastActions().single()
        assertEquals("ALREADY_SAVED", already.label)
        assertFalse(already.undoable)
        assertEquals(MAX_AI_MEMORIES, memoryStore.data.memories.size)
    }

    @Test
    fun withMemoryOffOrAChatWithoutItOnlyTheInstructionsAreSent() {
        memoryStore.data = AiMemoryData("Explain in Vietnamese.", listOf(AiMemory("m1", "Studies for JLPT N3", 1)))
        checkedGeminiKey()
        val controller = controller()
        controller.appActions = MemoryApp(controller)
        controller.setMemoryEnabled(false)
        controller.send("Hi", "guide")
        val off = transport.requests.last()
        assertEquals(AI_TOOLS, off.tools)
        assertTrue("Explain in Vietnamese." in systemPrompt(off))
        assertTrue("Memory is off in AI settings" in systemPrompt(off))
        assertFalse("JLPT" in systemPrompt(off))

        controller.setMemoryEnabled(true)
        controller.newChat()
        controller.setNewChatMemory(false)
        assertEquals(AiMemoryUse.OFF_IN_CHAT, controller.state.value.memoryUse)
        // A model that saves anyway is refused.
        transport.replies += AiReply("", listOf(toolCall(AI_SAVE_MEMORY_TOOL, """{"text":"Likes cats"}""")))
        controller.send("I like cats", "guide")
        val chatOff = transport.requests[transport.requests.size - 2]
        assertEquals(AI_TOOLS, chatOff.tools)
        assertTrue("Explain in Vietnamese." in systemPrompt(chatOff))
        assertTrue("Memory is off for this chat" in systemPrompt(chatOff))
        assertFalse("JLPT" in systemPrompt(chatOff))
        assertEquals(
            "Not done: Memory is off for this chat.",
            transport.requests
                .last()
                .messages
                .last()
                .content,
        )
        assertEquals(listOf("Studies for JLPT N3"), memoryStore.data.memories.map { it.text })
        assertFalse(history.chats.first().memory)

        // The next new chat uses memory again.
        controller.newChat()
        assertEquals(AiMemoryUse.ON, controller.state.value.memoryUse)
    }

    @Test
    fun memoriesAndInstructionsAreEditedAndSurviveARestart() {
        memoryStore.data = AiMemoryData(memories = listOf(AiMemory("m1", "Studies for JLPT N4", 1), AiMemory("m2", "Likes cats", 2)))
        val controller = controller()
        controller.setInstructions("  Keep answers short.  ")
        assertTrue(controller.editMemory("m1", "Studies for  JLPT N3"))
        assertFalse(controller.editMemory("m1", " "))
        controller.deleteMemory("m2")
        val restarted = controller().state.value.memory
        assertEquals("Keep answers short.", restarted.instructions)
        assertEquals(listOf(AiMemory("m1", "Studies for JLPT N3", 1)), restarted.memories)

        controller.setInstructions("x".repeat(MAX_AI_INSTRUCTIONS_CHARS + 10))
        assertEquals(MAX_AI_INSTRUCTIONS_CHARS, memoryStore.data.instructions.length)
        controller.deleteAllMemories()
        assertTrue(memoryStore.data.memories.isEmpty())
        assertEquals(
            MAX_AI_INSTRUCTIONS_CHARS,
            controller()
                .state.value.memory.instructions.length,
        )
    }
}
