package com.kienhoang.dualsubreplay.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsTest {
    @Test
    fun theAssistantIsOnByDefaultWithGeminiAndSevenDaysOfHistory() {
        val settings = AiAssistantSettings()
        assertTrue(settings.enabled)
        assertEquals(AiProvider.GEMINI, settings.provider)
        assertEquals(ChatHistoryRetention.WEEK, settings.historyRetention)
        assertTrue(settings.memoryEnabled)
        // Nobody has seen the intro yet, so there is nothing new to tell.
        assertFalse(settings.showsNews)
        assertTrue(settings.copy(introSeen = true).showsNews)
        assertFalse(settings.copy(introSeen = true, newsSeen = AI_NEWS_VERSION).showsNews)
        assertEquals(ChatHistoryRetention.WEEK, storedChatHistoryRetention(null))
        assertEquals(ChatHistoryRetention.WEEK, storedChatHistoryRetention("bogus"))
        assertEquals(AiProvider.GEMINI, storedAiProvider("bogus"))
        assertEquals(AiProvider.OPENROUTER, storedAiProvider("openrouter"))
    }

    @Test
    fun aBlankModelUsesTheServiceDefault() {
        val settings = AiAssistantSettings(models = mapOf(AiProvider.OPENAI to "  ", AiProvider.OPENROUTER to " meta/llama:free "))
        assertEquals(AiProvider.OPENAI.defaultModel, settings.modelFor(AiProvider.OPENAI))
        assertEquals("meta/llama:free", settings.modelFor(AiProvider.OPENROUTER))
        assertEquals(AiProvider.GEMINI.defaultModel, settings.modelFor(AiProvider.GEMINI))
        assertEquals("https://x.example/v1", AiAssistantSettings(customBaseUrl = " https://x.example/v1 ").baseUrlFor(AiProvider.CUSTOM))
        assertEquals(AiProvider.OPENAI.baseUrl, settings.baseUrlFor(AiProvider.OPENAI))
    }

    @Test
    fun retentionCutoffsCountWholeDays() {
        val now = 100L * 24 * 60 * 60 * 1000
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, ChatHistoryRetention.WEEK.cutoffMs(now))
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, ChatHistoryRetention.MONTH.cutoffMs(now))
        assertNull(ChatHistoryRetention.FOREVER.cutoffMs(now))
    }

    @Test
    fun everyBuiltInServiceHasAnHttpsChatAddress() {
        AiProvider.entries.filter { it != AiProvider.CUSTOM && it.needsKey }.forEach { provider ->
            val anthropic = provider.format == AiWireFormat.ANTHROPIC_MESSAGES
            val url = if (anthropic) anthropicMessagesUrl(provider.baseUrl) else chatCompletionsUrl(provider.baseUrl)
            assertEquals(provider.name, "https", url?.scheme)
            assertTrue(provider.name, url.toString().endsWith(if (anthropic) "/v1/messages" else "/chat/completions"))
            assertTrue(provider.name, provider.defaultModel.isNotBlank())
            assertTrue(provider.name, provider.keyPageUrl!!.startsWith("https://"))
        }
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            chatCompletionsUrl(AiProvider.GEMINI.baseUrl).toString(),
        )
    }

    @Test
    fun theModelListSitsNextToTheChatAddress() {
        assertEquals("https://opencode.ai/zen/v1/models", modelsUrl(AiProvider.OPENCODE_ZEN.baseUrl).toString())
        assertEquals("https://opencode.ai/zen/go/v1/chat/completions", chatCompletionsUrl(AiProvider.OPENCODE_GO.baseUrl).toString())
        assertNull(modelsUrl("http://192.168.1.2:11434/v1"))
        AiProvider.entries.forEach { provider ->
            assertTrue(provider.name, provider.defaultModel.isEmpty() || provider.defaultModel in provider.suggestedModels)
        }
        assertEquals(AiThinking.AUTO, AiAssistantSettings().thinking)
        assertNull(AiThinking.AUTO.effort)
        assertEquals(AiThinking.HIGH, storedAiThinking("high"))
        assertEquals(AiThinking.AUTO, storedAiThinking("bogus"))
    }

    @Test
    fun onlyPlainHttpsAddressesAreAccepted() {
        assertEquals("https://ai.example.com/v1/chat/completions", chatCompletionsUrl("https://ai.example.com/v1/").toString())
        assertEquals("https://ai.example.com/chat/completions", chatCompletionsUrl("https://ai.example.com").toString())
        assertNull(chatCompletionsUrl("http://192.168.1.2:11434/v1"))
        assertNull(chatCompletionsUrl("https://user:pass@ai.example.com/v1"))
        assertNull(chatCompletionsUrl("https://ai.example.com/v1?key=1"))
        assertNull(chatCompletionsUrl("https://ai.example.com/v1#x"))
        assertNull(chatCompletionsUrl("ai.example.com/v1"))
        assertNull(chatCompletionsUrl(""))
    }

    @Test
    fun pastedKeysAreRecognizedByTheirStart() {
        assertEquals(AiProvider.GEMINI, aiProviderForKey(" AIza-FAKE-test-key-for-unit-tests-rstu ", AiProvider.OPENAI))
        // Google AI Studio's newer key format.
        assertEquals(AiProvider.GEMINI, aiProviderForKey("AQ.FAKE-test-key-for-unit-tests-0123456789", AiProvider.OPENROUTER))
        assertEquals(AiProvider.OPENROUTER, aiProviderForKey("sk-or-v1-0123456789abcdef", AiProvider.GEMINI))
        assertEquals(AiProvider.OPENAI, aiProviderForKey("sk-proj-0123456789abcdef", AiProvider.OPENCODE_ZEN))
        assertEquals(AiProvider.GEMINI, aiProviderForKey("gsk_0123456789abcdefghij", AiProvider.GEMINI))
        assertEquals(AiProvider.CUSTOM, aiProviderForKey("AIza-FAKE-test-key-for-unit-tests-rstu", AiProvider.CUSTOM))
    }

    @Test
    fun anAnthropicKeyMeansClaudeEvenFromAnotherSkService() {
        val key = "sk-ant-api03-0123456789abcdefghij"
        for (current in listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.OPENCODE_ZEN, AiProvider.OPENROUTER)) {
            assertEquals(current.name, AiProvider.CLAUDE, aiProviderForKey(key, current))
        }
        assertEquals(AiProvider.CUSTOM, aiProviderForKey(key, AiProvider.CUSTOM))
        assertEquals("https://api.anthropic.com/v1/messages", anthropicMessagesUrl(AiProvider.CLAUDE.baseUrl).toString())
        assertEquals("https://api.anthropic.com/v1/models", modelsUrl(AiProvider.CLAUDE.baseUrl).toString())
    }

    @Test
    fun newGeminiKeysStartOnFlashLiteAndTheQuickListHasNoFixedVersions() {
        assertEquals("gemini-flash-lite-latest", AiAssistantSettings().modelFor(AiProvider.GEMINI))
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-flash-latest"), AiProvider.GEMINI.suggestedModels)
        assertEquals("claude-haiku-5-5", AiProvider.CLAUDE.defaultModel)
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), AiProvider.CLAUDE.suggestedModels)
    }

    @Test
    fun aKeyCheckedWithAnOldDefaultKeepsThatModelUntilAnotherIsChosen() {
        val gemini = AiProvider.GEMINI.baseUrl
        val oldDefault = mapOf(AiProvider.GEMINI to "$gemini\ngemini-flash-latest")
        assertEquals(mapOf(AiProvider.GEMINI to "gemini-flash-latest"), aiModelsKeptFromChecks(AiAssistantSettings(), oldDefault).models)
        // A chosen model, a check with today's default, or no check at all change nothing.
        val chosen = AiAssistantSettings(models = mapOf(AiProvider.GEMINI to "gemini-pro-latest"))
        assertEquals(chosen, aiModelsKeptFromChecks(chosen, oldDefault))
        val today = mapOf(AiProvider.GEMINI to "$gemini\ngemini-flash-lite-latest")
        assertEquals(AiAssistantSettings(), aiModelsKeptFromChecks(AiAssistantSettings(), today))
        assertEquals(AiAssistantSettings(), aiModelsKeptFromChecks(AiAssistantSettings(), emptyMap()))
        // A check through another address, or Other's blank default, keeps nothing either.
        assertEquals(
            AiAssistantSettings(),
            aiModelsKeptFromChecks(
                AiAssistantSettings(),
                mapOf(AiProvider.GEMINI to "https://x.example/v1\nm"),
            ),
        )
        assertEquals(AiAssistantSettings(), aiModelsKeptFromChecks(AiAssistantSettings(), mapOf(AiProvider.CUSTOM to "\n")))
    }

    @Test
    fun aPlainSkKeyStaysWithOpenCodeButOtherwiseMeansOpenAi() {
        val key = "sk-0123456789abcdefghijklmnop"
        assertEquals(AiProvider.OPENCODE_ZEN, aiProviderForKey(key, AiProvider.OPENCODE_ZEN))
        assertEquals(AiProvider.OPENCODE_GO, aiProviderForKey(key, AiProvider.OPENCODE_GO))
        assertEquals(AiProvider.OPENAI, aiProviderForKey(key, AiProvider.GEMINI))
        assertEquals(AiProvider.OPENAI, aiProviderForKey(key, AiProvider.OPENROUTER))
    }

    @Test
    fun aCheckCoversTheAddressAndModel() {
        val settings = AiAssistantSettings()
        val checked = aiCheckedSetup(settings, AiProvider.GEMINI)
        assertEquals(checked, aiCheckedSetup(settings.copy(models = mapOf(AiProvider.GEMINI to " ")), AiProvider.GEMINI))
        assertFalse(checked == aiCheckedSetup(settings.copy(models = mapOf(AiProvider.GEMINI to "gemini-pro-latest")), AiProvider.GEMINI))
        val custom = settings.copy(customBaseUrl = "https://a.example/v1")
        assertFalse(
            aiCheckedSetup(custom, AiProvider.CUSTOM) ==
                aiCheckedSetup(custom.copy(customBaseUrl = "https://b.example/v1"), AiProvider.CUSTOM),
        )
    }

    @Test
    fun cutOrSpacedTextIsNotAKey() {
        assertTrue(looksLikeAiKey("AIza-FAKE-test-key-for-unit-tests-rstu"))
        assertTrue(looksLikeAiKey("  sk-proj-0123456789abcdefghij\n"))
        assertFalse(looksLikeAiKey("AIzaSyA123"))
        assertFalse(looksLikeAiKey("this is a sentence, not a key at all"))
        assertFalse(looksLikeAiKey(""))
        // Invisible or non-ASCII characters copied from a web page cannot go in an HTTP header.
        assertFalse(looksLikeAiKey("AIza-FAKE-test-key\u200B-for-unit-tests"))
        assertFalse(looksLikeAiKey("AIza-FAKE-test-key-for-unit-tests-é"))
    }
}
