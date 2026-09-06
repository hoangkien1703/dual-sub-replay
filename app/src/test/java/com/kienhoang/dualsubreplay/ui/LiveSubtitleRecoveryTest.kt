package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.captionFailureCategory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiveSubtitleRecoveryTest {
    private val sample = LiveCaptionSample("Hello world", 1, 1000, true, "abcdefghijk", "en-US")
    private val key = liveTranslationKey(sample, "abcdefghijk", "vi")!!

    @Test fun metadataMustMatchVideoAndHaveLanguage() {
        assertEquals("en", key.language)
        assertNull(liveTranslationKey(sample, "anotherVid1", "vi"))
        assertNull(liveTranslationKey(sample.copy(languageCode = null), "abcdefghijk", "vi"))
        assertNull(liveTranslationKey(sample.copy(present = false), "abcdefghijk", "vi"))
    }

    @Test fun rollingTextReplacesRatherThanAppendsAndDuplicateRevisionsDoNotRetranslate() {
        val gate = LiveTranslationGate()
        assertTrue(gate.update(key))
        val ticket = gate.generation
        assertFalse(gate.update(liveTranslationKey(sample.copy(revision = 2), "abcdefghijk", "vi")))
        assertTrue(gate.accepts(ticket, key))
        assertTrue(gate.update(key.copy(text = "world today")))
        assertEquals("world today", gate.key?.text)
        assertFalse(gate.accepts(ticket, key))
    }

    @Test fun seekVideoLanguageAndTranscriptRecoveryInvalidatePendingResults() {
        for (next in listOf(key.copy(videoId = "anotherVid1"), key.copy(language = "ja"), key.copy(target = "de"), null)) {
            val gate = LiveTranslationGate()
            gate.update(key)
            val ticket = gate.generation
            gate.update(next)
            assertFalse(gate.accepts(ticket, key))
        }
        val gate = LiveTranslationGate()
        gate.update(key)
        val ticket = gate.generation
        gate.update(key, seek = true)
        assertFalse(gate.accepts(ticket, key))
        val seekTicket = gate.generation
        gate.reset()
        assertFalse(gate.accepts(seekTicket, key))
    }

    @Test fun debounceSkipsReplacedTextAndDiscardsLateTranslation() = runBlocking {
        var current = true
        var calls = 0
        val pending = async {
            debouncedLiveTranslation(key, { current }) { _, _, _ -> calls++; "Xin chào" }
        }
        delay(50)
        assertEquals(0, calls)
        current = false
        assertNull(pending.await())
        assertEquals(0, calls)
        current = true
        assertNull(debouncedLiveTranslation(key, { current }) { _, _, _ -> current = false; "stale" })
        current = true
        assertEquals("Xin chào", debouncedLiveTranslation(key, { current }) { source, target, text ->
            assertEquals("en", source); assertEquals("vi", target); assertEquals("Hello world", text)
            "Xin chào"
        })
    }

    @Test fun overlayUsesLiveTextWithoutInventedReplaySegment() {
        val content = learningOverlayContent(DualSubUiState(activeVideoId = "abcdefghijk", liveFallback = true,
            liveOriginal = "Hello", liveTranslated = "Xin chào", wordHighlightEnabled = false,
            karaokeTimingMode = KaraokeTimingMode.TRANSCRIPT))!!
        assertEquals("Hello", content.originalText)
        assertEquals("Xin chào", content.translatedText)
        assertNull(content.segment)
        assertTrue(content.statusText!!.contains("Live subtitles"))
    }

    @Test fun diagnosticsNeverExposeServerTextOrSignedUrls() {
        assertEquals("HTTP 429", captionFailureCategory("HTTP 429 https://youtube.com/?secret=token"))
        assertEquals("playability", captionFailureCategory("Player returned UNPLAYABLE: secret"))
        assertEquals("empty-response", captionFailureCategory("empty response secret"))
        assertEquals("timeout", captionFailureCategory("request timed out secret"))
        assertEquals("discovery", captionFailureCategory("https://youtube.com/?secret=token"))
    }
}
