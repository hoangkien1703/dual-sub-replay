package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.content.res.Configuration
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleWord
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LandscapeLiveCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun realPlayerKeepsLiveWordsWhenLandscapeAndFullscreenHideThePanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.all
        preferences
            .edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
            .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false)
            .putBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, true)
            .putBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, true)
            .putBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true)
            .putString(PLAYER_EXPERIENCE_MODE_PREFERENCE, PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue)
            .putString("last_browser_url", "https://m.youtube.com/watch?v=abcdefghijk")
            .putString("preferred_caption_language", "en")
            .putString("target_language", "en")
            .putString(CAPTION_FORMAT_PREFERENCE, CaptionFormat.WHOLE_SENTENCE.storageValue)
            .putString(ORIGINAL_VISIBILITY, CaptionVisibility.ALWAYS.name)
            .commit()
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(application, DelayedTranscript()) }
        val configuration = mutableStateOf(Configuration(application.resources.configuration))
        configuration.value = Configuration(configuration.value).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
        val page = AtomicReference<WebView>()
        val loads = AtomicInteger()
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides configuration.value,
                    LocalYouTubeRequestInterceptor provides { view, request ->
                        page.compareAndSet(null, view)
                        val html = if (request.isForMainFrame) OFFLINE_PAGE.also { loads.incrementAndGet() } else ""
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    },
                ) {
                    LearningPlayerRoot(model)
                }
            }
            compose.waitUntil(15_000) { model.state.value.generatedCaptions && model.state.value.activeWordIndex == 0 }
            assertEquals("true", javascript(page.get(), "!!window['$LIVE_CAPTION_CAPTURE_STATE_KEY']?.enabled"))
            // The transcript starts at 2 seconds. Earlier highlights can only
            // come from the actual WebView live-caption path.
            advanceCaption(page.get(), model, "One two", 0.2, 1)
            compose.runOnIdle {
                configuration.value = Configuration(configuration.value).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            }
            compose.waitUntil(5000) { !model.state.value.subtitlePanelVisible }
            assertEquals("true", javascript(page.get(), "!!window['$LIVE_CAPTION_CAPTURE_STATE_KEY']?.enabled"))
            advanceCaption(page.get(), model, "One two three", 0.3, 2)
            compose.runOnIdle {
                page.get().webChromeClient!!.onShowCustomView(FrameLayout(page.get().context)) {}
            }
            compose.waitUntil(5000) { youtubeFullscreenActive.value }
            advanceCaption(page.get(), model, "One two three four", 0.4, 3)
            compose.runOnIdle { page.get().webChromeClient!!.onHideCustomView() }
            compose.waitUntil(5000) { !youtubeFullscreenActive.value }
            assertFalse(model.state.value.subtitlePanelVisible)
            compose.runOnIdle {
                configuration.value = Configuration(configuration.value).apply { orientation = Configuration.ORIENTATION_PORTRAIT }
            }
            compose.waitUntil(5000) { model.state.value.subtitlePanelVisible }
            advanceCaption(page.get(), model, "One two three four five", 0.5, 4)
            assertEquals("The same native page must survive every presentation switch", 1, loads.get())
        } finally {
            instrumentation.runOnMainSync {
                page.get()?.webChromeClient?.onHideCustomView()
                model.viewModelScope.cancel()
                youtubeFullscreenActive.value = false
            }
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            editor.commit()
        }
    }

    private fun advanceCaption(
        page: WebView,
        model: AppViewModel,
        text: String,
        second: Double,
        word: Int,
    ) {
        javascript(page, "video._time = $second; document.querySelector('.ytp-caption-segment').textContent = '$text';")
        compose.waitUntil(5000) { model.state.value.activeWordIndex == word }
        assertTrue("A late transcript must not explain this update", second < 1.0)
    }

    private fun javascript(
        page: WebView,
        script: String,
    ): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            page.evaluateJavascript(script) {
                result.set(it)
                done.countDown()
            }
        }
        assertTrue("WebView script did not complete", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private class DelayedTranscript : CaptionProvider {
        override suspend fun fetch(
            videoId: String,
            preferredLanguages: List<String>,
        ): CaptionTrackResult =
            CaptionTrackResult(
                "en",
                true,
                listOf(
                    RawCaptionCue(
                        0,
                        12_000,
                        "One two three four five",
                        listOf("One", "two", "three", "four", "five").mapIndexed { index, word ->
                            SubtitleWord(word, (index + 1) * 2000L, (index + 2) * 2000L)
                        },
                    ),
                ),
            )
    }

    companion object {
        private val OFFLINE_PAGE =
            """
            <html><body>
            <div id="movie_player" class="html5-video-player ytp-autohide">
              <video></video><span class="ytp-caption-segment">One</span>
            </div>
            <script>
            const video = document.querySelector('video');
            video._time = 0.1;
            Object.defineProperties(video, {
              currentTime: {get: () => video._time}, readyState: {get: () => 4},
              paused: {get: () => false}, seeking: {get: () => false}
            });
            video.requestVideoFrameCallback = function() {};
            const caption = document.querySelector('.ytp-caption-segment');
            const bootstrap = setInterval(function() {
              if (caption.textContent === 'One') caption.textContent = 'One.';
              else if (caption.textContent === 'One.') caption.textContent = 'One';
              else clearInterval(bootstrap);
            }, 250);
            const player = document.getElementById('movie_player');
            player.getOption = function() { return {languageCode: 'en'}; };
            player.setOption = function() {};
            player.getPlayerResponse = function() { return {videoDetails: {videoId: 'abcdefghijk'}}; };
            </script></body></html>
            """.trimIndent()
    }
}
