package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.AI_SUBTITLE_TRACK_ID
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * After a video whose song lyrics or AI transcript showed, the next video with no captions must not
 * keep that track as loaded: it showed "AI transcript (Gemini)" selected and "Make subtitles with
 * AI" did nothing (Kien, 2026-11-17).
 */
class NextVideoForgetsLoadedTrackTest {
    @Test
    fun aVideoWithNoCaptionsDoesNotKeepTheLastVideosTrack() =
        runBlocking {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
            val previousPreload = preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true)
            preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false).commit()
            val provider =
                object : CaptionProvider {
                    override suspend fun fetch(
                        videoId: String,
                        preferredLanguages: List<String>,
                        learningLanguage: String?,
                        preferredTrack: String?,
                    ): CaptionTrackResult {
                        if (videoId == SONG) {
                            val cues = listOf(RawCaptionCue(0, 5000, "A sung line"))
                            return CaptionTrackResult("en", false, cues, trackId = AI_SUBTITLE_TRACK_ID, lyrics = true)
                        }
                        throw CaptionUnavailableException("This video has no captions.")
                    }
                }
            val vm = withContext(Dispatchers.Main) { AppViewModel(application, provider) }
            try {
                withContext(Dispatchers.Main) { vm.onYouTubePageChanged("https://m.youtube.com/watch?v=$SONG") }
                withTimeout(5000) { vm.state.first { it.loadedTrackId == AI_SUBTITLE_TRACK_ID } }
                withContext(Dispatchers.Main) { vm.onYouTubePageChanged("https://m.youtube.com/watch?v=$NO_CAPTIONS") }
                val state = withTimeout(5000) { vm.state.first { it.activeVideoId == NO_CAPTIONS && it.liveFallback } }
                assertEquals("", state.loadedTrackId)
                assertFalse(state.loadedLyrics)
                assertFalse(sourceChoiceSelection(state) == AI_TRANSCRIPT_CHOICE)
            } finally {
                withContext(Dispatchers.Main) { vm.onYouTubePageChanged(YOUTUBE_HOME_URL) }
                preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, previousPreload).commit()
            }
        }

    private companion object {
        const val SONG = "songlyrics1"
        const val NO_CAPTIONS = "nocaptions1"
    }
}
