package com.kienhoang.dualsubreplay.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PronunciationTest {
    private val japanese = PronunciationVoice("ja-offline", Locale.JAPAN)
    private val network = PronunciationVoice("ja-network", Locale.JAPAN, network = true)

    @Test
    fun missingJapaneseInDefaultEngineFallsBackToAnotherInstalledEngine() {
        runBlocking {
            val vendor = FakeEngine()
            val multilingual = FakeEngine(listOf(japanese))
            val requested = mutableListOf<String?>()
            val result =
                pronounceWord("座れそう", "ja", listOf("vendor", "multilingual", "vendor")) {
                    requested += it
                    if (it == "vendor") vendor else multilingual
                }
            assertEquals(PronunciationResult.SPOKEN, result)
            assertEquals(listOf("vendor", "multilingual"), requested)
            assertEquals(listOf("座れそう"), multilingual.spoken)
            assertEquals(listOf(japanese), multilingual.selected)
            assertTrue(vendor.closed && multilingual.closed)
        }
    }

    @Test
    fun defaultEngineSuccessDoesNotOpenOtherEngines() {
        runBlocking {
            val engine = FakeEngine(listOf(japanese))
            val requested = mutableListOf<String?>()
            val result =
                pronounceWord("こんにちは", "ja", listOf(null, "other")) {
                    requested += it
                    engine
                }
            assertEquals(PronunciationResult.SPOKEN, result)
            assertEquals(listOf<String?>(null), requested)
            assertTrue(engine.closed)
        }
    }

    @Test
    fun availableNetworkVoiceWorksWithoutAnOfflineLanguagePack() {
        runBlocking {
            val engine = FakeEngine(listOf(japanese.copy(installed = false), network))
            assertEquals(PronunciationResult.SPOKEN, pronounce(engine))
            assertEquals(listOf(network), engine.selected)
            assertEquals(0, engine.languageRequests)
        }
    }

    @Test
    fun asynchronousPlaybackFailureTriesNetworkVoice() {
        runBlocking {
            val firstPlayback = CompletableDeferred<Boolean>()
            val started = CompletableDeferred<Unit>()
            val engine = FakeEngine(listOf(network, japanese))
            engine.play = {
                if (engine.selected.last() == japanese) {
                    started.complete(Unit)
                    firstPlayback.await()
                } else {
                    true
                }
            }
            val result = async { pronounce(engine) }
            started.await()
            assertFalse(result.isCompleted)
            firstPlayback.complete(false)
            assertEquals(PronunciationResult.SPOKEN, result.await())
            assertEquals(listOf(japanese, network), engine.selected)
        }
    }

    @Test
    fun brokenInitializationFallsBackAndClosesEngine() {
        runBlocking {
            val broken = FakeEngine().apply { ready = false }
            val working = FakeEngine(listOf(japanese))
            val result =
                pronounceWord("座れそう", "ja", listOf("broken", "working")) {
                    if (it == "broken") broken else working
                }
            assertEquals(PronunciationResult.SPOKEN, result)
            assertTrue(broken.closed && working.closed)
        }
    }

    @Test
    fun vendorExceptionDoesNotPreventFallback() {
        runBlocking {
            val working = FakeEngine(listOf(japanese))
            val result =
                pronounceWord("座れそう", "ja", listOf("broken", "working")) {
                    if (it == "broken") error("Engine failed to bind") else working
                }
            assertEquals(PronunciationResult.SPOKEN, result)
        }
    }

    @Test
    fun olderEngineWithoutVoiceCatalogCanUseSetLanguage() {
        runBlocking {
            val engine = FakeEngine().apply { languageAvailable = true }
            assertEquals(PronunciationResult.SPOKEN, pronounce(engine))
            assertEquals(1, engine.languageRequests)
        }
    }

    @Test
    fun missingVoiceAndPlaybackFailureHaveDifferentResults() {
        runBlocking {
            assertEquals(PronunciationResult.NO_VOICE, pronounce(FakeEngine()))
            assertEquals(PronunciationResult.UNAVAILABLE, pronounce(FakeEngine().apply { ready = false }))
            assertEquals(PronunciationResult.PLAYBACK_FAILED, pronounce(FakeEngine(listOf(japanese)).apply { play = { false } }))
        }
    }

    @Test
    fun cancellationWhileInitializingClosesEngineWithoutFallback() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val engine =
                FakeEngine(listOf(japanese)).apply {
                    init = {
                        started.complete(Unit)
                        CompletableDeferred<Boolean>().await()
                    }
                }
            val requested = mutableListOf<String?>()
            val job =
                async {
                    pronounceWord("座れそう", "ja", listOf("first", "other")) {
                        requested += it
                        engine
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertTrue(engine.closed)
            assertTrue(engine.spoken.isEmpty())
            assertEquals(listOf("first"), requested)
        }
    }

    @Test
    fun cancellationDuringPlaybackClosesEngineWithoutFallback() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val engine =
                FakeEngine(listOf(japanese)).apply {
                    play = {
                        started.complete(Unit)
                        CompletableDeferred<Boolean>().await()
                    }
                }
            val job = async { pronounce(engine) }
            started.await()
            job.cancelAndJoin()
            assertTrue(engine.closed)
            assertEquals(1, engine.spoken.size)
        }
    }

    @Test
    fun retryRechecksEnginesAfterVoiceInstallation() {
        runBlocking {
            assertEquals(PronunciationResult.NO_VOICE, pronounce(FakeEngine()))
            assertEquals(PronunciationResult.SPOKEN, pronounce(FakeEngine(listOf(japanese))))
        }
    }

    @Test
    fun languageTagsKeepRegionAndNormalizeUnderscores() {
        assertEquals(Locale.JAPAN, pronunciationLocale(" ja_JP "))
        assertEquals("vi", pronunciationLocale("vi-VN")?.language)
        assertNull(pronunciationLocale("auto"))
        assertNull(pronunciationLocale("und"))
        assertNull(pronunciationLocale(""))
    }

    @Test
    fun invalidLanguageDoesNotStartAnEngine() {
        runBlocking {
            val result =
                pronounceWord("word", "auto", listOf(null)) {
                    error("Must not open an engine for an unresolved language")
                }
            assertEquals(PronunciationResult.INVALID_LANGUAGE, result)
        }
    }

    @Test
    fun voiceChoicePrefersInstalledOfflineVoicesAndNeverUsesAnotherLanguage() {
        val english = PronunciationVoice("en", Locale.US)
        val missing = japanese.copy(name = "missing", installed = false)
        assertEquals(listOf(japanese, network), pronunciationVoices(listOf(english, missing, network, japanese), Locale.JAPAN))
        val british = PronunciationVoice("gb", Locale.UK)
        assertEquals(listOf(british, english), pronunciationVoices(listOf(english, british), Locale.UK))
    }

    private suspend fun pronounce(engine: FakeEngine): PronunciationResult = pronounceWord("座れそう", "ja", listOf(null)) { engine }

    @Test
    fun manyOfflineVoicesCannotCrowdOutTheNetworkFallback() {
        val offline = (1..5).map { japanese.copy(name = "offline-$it") }
        assertEquals(listOf(offline.first(), network, offline[1]), pronunciationVoices(offline + network, Locale.JAPAN))
    }

    private class FakeEngine(
        private val available: List<PronunciationVoice> = emptyList(),
    ) : PronunciationEngine {
        var ready = true
        var languageAvailable = false
        var languageRequests = 0
        var closed = false
        val selected = mutableListOf<PronunciationVoice>()
        val spoken = mutableListOf<String>()
        var init: suspend () -> Boolean = { ready }
        var play: suspend () -> Boolean = { true }

        override suspend fun initialize(): Boolean = init()

        override fun voices(): List<PronunciationVoice> = available

        override fun selectVoice(voice: PronunciationVoice): Boolean {
            selected += voice
            return true
        }

        override fun selectLanguage(locale: Locale): Boolean {
            languageRequests++
            return languageAvailable
        }

        override suspend fun speak(word: String): Boolean {
            spoken += word
            return play()
        }

        override fun close() {
            closed = true
        }
    }
}
