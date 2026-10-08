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
        )

    private fun savedChat(
        id: String,
        daysAgo: Int,
    ) = AiChat(id, now - daysAgo * day, now - daysAgo * day, listOf(AiChatMessage("$id-q", AiRole.USER, "q", now - daysAgo * day)))

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun withoutAKeyNothingIsSent() {
        val controller = controller()
        assertFalse(controller.state.value.ready)
        controller.send("What does に mean?", "guide")
        assertEquals(
            AiErrorKind.NO_KEY,
            controller.state.value.failure
                ?.kind,
        )
        assertTrue(transport.requests.isEmpty())
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
    }

    @Test
    fun aQuestionGetsAnAnswerAndTheChatIsSaved() {
        keyStore.save(AiProvider.GEMINI, geminiKey)
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
        keyStore.save(AiProvider.GEMINI, geminiKey)
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
        keyStore.save(AiProvider.GEMINI, geminiKey)
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
        keyStore.save(AiProvider.GEMINI, geminiKey)
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
        controller.showHistory(true)
        controller.openChat("b")
        assertEquals(
            "b",
            controller.state.value.chat
                ?.id,
        )
        assertFalse(controller.state.value.showingHistory)
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
        keyStore.save(AiProvider.GEMINI, geminiKey)
        val controller = controller()
        controller.removeKey(AiProvider.GEMINI)
        assertFalse(controller.state.value.hasKey)
        assertEquals(StoredAiKey.Missing, keyStore.load(AiProvider.GEMINI))
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
}
