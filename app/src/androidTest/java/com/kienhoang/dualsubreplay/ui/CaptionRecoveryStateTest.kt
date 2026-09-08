package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CaptionRecoveryStateTest {
    @Test fun failedLookupUsesLiveCaptionsAndRetryPreservesThemUntilTranscriptReturns() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val previousPreload = preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true)
        preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false).commit()
        var attempts = 0
        val retryResult = CompletableDeferred<CaptionTrackResult>()
        val provider = object : CaptionProvider {
            override suspend fun fetch(videoId: String, preferredLanguages: List<String>): CaptionTrackResult {
                attempts++
                if (attempts == 1) throw CaptionUnavailableException("Simulated HTTP 429")
                return retryResult.await()
            }
        }
        val vm = withContext(Dispatchers.Main) { AppViewModel(application, provider) }
        val oldTarget = vm.state.value.targetLanguage
        val oldHighlight = vm.state.value.wordHighlightEnabled
        try {
            withContext(Dispatchers.Main) {
                vm.setTargetLanguage("en") // Exercises ML Kit's no-download same-language path.
                vm.setWordHighlightEnabled(false)
                vm.onYouTubePageChanged("https://m.youtube.com/watch?v=abcdefghijk")
            }
            withTimeout(5000) { vm.state.first { it.liveFallback } }
            withContext(Dispatchers.Main) {
                vm.onWebPlaybackSecond("abcdefghijk", 1f, LiveCaptionSample("Hello", 1, 1000, true, "abcdefghijk", "en"))
            }
            withTimeout(5000) { vm.state.first { it.liveTranslated == "Hello" } }
            withContext(Dispatchers.Main) { vm.retryCaptions() }
            assertEquals("Hello", vm.state.value.liveOriginal)
            assertEquals("Hello", vm.state.value.liveTranslated)
            assertTrue(vm.state.value.retryingTranscript)
            retryResult.complete(CaptionTrackResult("en", false, listOf(RawCaptionCue(0, 5000, "Full transcript"))))
            withTimeout(5000) { vm.state.first { !it.liveFallback && it.segments.isNotEmpty() } }
            assertNull(vm.state.value.liveOriginal)
            assertFalse(vm.state.value.retryingTranscript)
        } finally {
            withContext(Dispatchers.Main) {
                vm.onYouTubePageChanged(YOUTUBE_HOME_URL)
                vm.setTargetLanguage(oldTarget)
                vm.setWordHighlightEnabled(oldHighlight)
            }
            preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, previousPreload).commit()
        }
    }
}
