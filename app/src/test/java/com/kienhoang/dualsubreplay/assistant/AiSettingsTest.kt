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
        AiProvider.entries.filter { it != AiProvider.CUSTOM }.forEach { provider ->
            val url = chatCompletionsUrl(provider.baseUrl)
            assertEquals(provider.name, "https", url?.scheme)
            assertTrue(provider.name, url.toString().endsWith("/chat/completions"))
            assertTrue(provider.name, provider.defaultModel.isNotBlank())
            assertTrue(provider.name, provider.keyPageUrl!!.startsWith("https://"))
        }
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            chatCompletionsUrl(AiProvider.GEMINI.baseUrl).toString(),
        )
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
        assertEquals(AiProvider.GEMINI, aiProviderForKey(" AIza-FAKE-test-key-for-unit-tests-rstu "))
        assertEquals(AiProvider.OPENROUTER, aiProviderForKey("sk-or-v1-0123456789abcdef"))
        assertEquals(AiProvider.OPENAI, aiProviderForKey("sk-proj-0123456789abcdef"))
        assertNull(aiProviderForKey("gsk_0123456789abcdefghij"))
    }

    @Test
    fun cutOrSpacedTextIsNotAKey() {
        assertTrue(looksLikeAiKey("AIza-FAKE-test-key-for-unit-tests-rstu"))
        assertTrue(looksLikeAiKey("  sk-proj-0123456789abcdefghij\n"))
        assertFalse(looksLikeAiKey("AIzaSyA123"))
        assertFalse(looksLikeAiKey("this is a sentence, not a key at all"))
        assertFalse(looksLikeAiKey(""))
    }
}
