package com.kienhoang.dualsubreplay.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiTranslationAccessTest {
    private val gemini = AiAssistantSettings(provider = AiProvider.GEMINI, models = mapOf(AiProvider.GEMINI to "gemini-pro-latest"))

    @Test fun translationDefaultsToTheFastModelNotTheAssistantsModel() {
        assertEquals("gemini-flash-lite-latest", aiTranslationModelName(gemini, null))
        assertEquals("gemini-flash-lite-latest", aiTranslationModelName(gemini, ""))
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-pro-latest"), aiTranslationModelChoices(gemini))
    }

    @Test fun aChosenModelIsUsedOnlyWhileTheServiceOffersIt() {
        assertEquals("gemini-pro-latest", aiTranslationModelName(gemini, "gemini-pro-latest"))
        assertEquals("gemini-flash-lite-latest", aiTranslationModelName(gemini, "gpt-5-mini"))
    }

    @Test fun aServiceWithoutSuggestionsUsesTheAssistantsModel() {
        val custom = AiAssistantSettings(provider = AiProvider.CUSTOM, models = mapOf(AiProvider.CUSTOM to "my-model"))
        assertEquals("my-model", aiTranslationModelName(custom, null))
    }

    @Test fun thinkingIsTurnedOffWhereTheServiceAllowsIt() {
        assertEquals("none", aiTranslationEffort(AiProvider.GEMINI))
        assertEquals("minimal", aiTranslationEffort(AiProvider.OPENAI))
        assertNull(aiTranslationEffort(AiProvider.CLAUDE))
    }

    @Test fun requestsTakeTurnsOverTheKeys() =
        runBlocking {
            val pacer = AiKeyPacer(intervalMs = 0)
            val asked = List(4) { pacer.take(listOf("a", "b")) }
            assertEquals(listOf("a", "b", "a", "b"), asked)
        }

    @Test fun aLimitedKeyRestsLongerEachTimeAndEveryKeyRestingMeansGoogle() =
        runBlocking {
            var now = 0L
            val pacer = AiKeyPacer(now = { now }, intervalMs = 0)
            pacer.limited("a")
            assertEquals("b", pacer.take(listOf("a", "b")))
            pacer.limited("b")
            assertNull(pacer.take(listOf("a", "b")))
            now = AI_TRANSLATION_KEY_REST_MS
            assertEquals("a", pacer.take(listOf("a")))
            pacer.limited("a")
            now += AI_TRANSLATION_KEY_REST_MS
            assertNull(pacer.take(listOf("a")))
            now += AI_TRANSLATION_KEY_REST_MS
            assertEquals("a", pacer.take(listOf("a")))
        }

    @Test fun aRateLimitedKeyHandsTheRequestToTheNextKey() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaFirstKey000000000000000000000000000")
            store.add(AiProvider.GEMINI, "AIzaSecondKey00000000000000000000000000")
            val keys = mutableListOf<String>()
            val transport =
                AiChatTransport { request ->
                    keys += request.apiKey
                    if (request.apiKey.startsWith("AIzaFirst")) throw AiChatException(AiErrorKind.RATE_LIMITED)
                    AiReply("{\"sentences\":[]}")
                }
            val model = aiTranslationModel(gemini, null, store, transport, AiKeyPacer(intervalMs = 0))!!
            assertEquals("{\"sentences\":[]}", model.ask("system", "user"))
            assertEquals(2, keys.size)
            assertEquals("gemini/gemini-flash-lite-latest", model.scope)
        }

    @Test fun aRefusedThinkingSettingIsDroppedAndAskedAgain() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaOnlyKey0000000000000000000000000000")
            val efforts = mutableListOf<String?>()
            val transport =
                AiChatTransport { request ->
                    efforts += request.reasoningEffort
                    if (request.reasoningEffort != null) throw AiChatException(AiErrorKind.UNSUPPORTED_THINKING)
                    AiReply("ok")
                }
            val model = aiTranslationModel(gemini, null, store, transport, AiKeyPacer(intervalMs = 0))!!
            assertEquals("ok", model.ask("system", "user"))
            assertEquals(listOf("none", null), efforts)
        }

    @Test fun theAnswerIsHandedOverWhileItStreams() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaOnlyKey0000000000000000000000000000")
            val transport =
                object : AiChatTransport {
                    override suspend fun complete(request: AiChatRequest): AiReply = error("must stream")

                    override suspend fun stream(
                        request: AiChatRequest,
                        onText: (String) -> Unit,
                    ): AiReply {
                        onText("{\"sentences\":[")
                        onText("{\"id\":1}]}")
                        return AiReply("{\"sentences\":[{\"id\":1}]}")
                    }
                }
            val pieces = mutableListOf<String>()
            val model = aiTranslationModel(gemini, null, store, transport, AiKeyPacer(intervalMs = 0))!!
            model.ask("system", "user") { pieces += it }
            assertEquals(listOf("{\"sentences\":[", "{\"id\":1}]}"), pieces)
        }

    @Test fun aFailureAfterPartOfTheAnswerIsNotAskedAgainWithAnotherKey() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaFirstKey000000000000000000000000000")
            store.add(AiProvider.GEMINI, "AIzaSecondKey00000000000000000000000000")
            var calls = 0
            val transport =
                object : AiChatTransport {
                    override suspend fun complete(request: AiChatRequest): AiReply = error("must stream")

                    override suspend fun stream(
                        request: AiChatRequest,
                        onText: (String) -> Unit,
                    ): AiReply {
                        calls++
                        onText("{\"sentences\":[")
                        throw AiChatException(AiErrorKind.RATE_LIMITED)
                    }
                }
            val model = aiTranslationModel(gemini, null, store, transport, AiKeyPacer(intervalMs = 0))!!
            try {
                model.ask("system", "user")
                fail("expected a failure")
            } catch (_: AiChatException) {
            }
            assertEquals(1, calls)
        }

    @Test fun noKeyMeansNoModel() {
        assertNull(aiTranslationModel(gemini, null, AiKeyStore(MapStorage(), FakeCipher()), { error("unused") }))
    }

    @Test fun aRejectedKeyIsNotAskedAgain() =
        runBlocking {
            val store = AiKeyStore(MapStorage(), FakeCipher())
            store.add(AiProvider.GEMINI, "AIzaBadKey00000000000000000000000000000")
            var calls = 0
            val transport =
                AiChatTransport {
                    calls++
                    throw AiChatException(AiErrorKind.INVALID_KEY)
                }
            val model = aiTranslationModel(gemini, null, store, transport, AiKeyPacer(intervalMs = 0))!!
            repeat(2) {
                try {
                    model.ask("system", "user")
                    fail("expected a failure")
                } catch (_: AiChatException) {
                }
            }
            assertEquals(1, calls)
            assertTrue(store.keys(AiProvider.GEMINI).isNotEmpty())
        }
}
