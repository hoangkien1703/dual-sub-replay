package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.PageFirstCaptionProvider
import com.kienhoang.dualsubreplay.data.PageTranscriptSource
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The full transcript comes from the page when YouTube refuses the app's own download: the
 * offline page's track address answers with nothing unless it carries the page player's token,
 * exactly like YouTube's web addresses marked `exp=xpe`.
 */
class PageTranscriptWebViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thePageFetchesTheWholeTrackWhenTheDownloadIsRefused() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.all
        preferences
            .edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
            .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false)
            .putString(PLAYER_EXPERIENCE_MODE_PREFERENCE, PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue)
            .putString("last_browser_url", "https://m.youtube.com/watch?v=abcdefghijk")
            .putString("preferred_caption_language", "auto")
            .putString("target_language", "en")
            .commit()
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync {
            model = AppViewModel(application, PageFirstCaptionProvider(PageTranscriptSource.Shared, RefusedDownload()))
        }
        val page = AtomicReference<WebView>()
        val captionRequests = Collections.synchronizedList(mutableListOf<String>())
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalYouTubeRequestInterceptor provides { view, request ->
                        page.compareAndSet(null, view)
                        val url = request.url
                        when {
                            url.path == "/api/timedtext" -> {
                                captionRequests += url.query.orEmpty()
                                val body = if (url.getQueryParameter("pot") == "TOKEN") JSON3 else ""
                                WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                            }
                            request.isForMainFrame ->
                                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(OFFLINE_PAGE.toByteArray()))
                            else -> WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    },
                ) {
                    LearningPlayerRoot(model)
                }
            }
            compose.waitUntil(30_000) { model.state.value.resolvedSourceLanguage == "zh-Hans" }
            val state = model.state.value
            assertFalse("The full transcript loaded, so live subtitles are not needed", state.liveFallback)
            assertTrue(state.generatedCaptions)
            assertTrue(
                "The page fetched the track with its own token: $captionRequests",
                captionRequests.any { it.contains("pot=TOKEN") && it.contains("fmt=json3") },
            )
            assertEquals("Captions the app switched on are switched back off", "{}", javascript(page.get(), "JSON.stringify(currentTrack)"))
        } finally {
            instrumentation.runOnMainSync { model.viewModelScope.cancel() }
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
        return result.get().removeSurrounding("\"").replace("\\\"", "\"")
    }

    /** YouTube's bot check: the app's own lookup never gets the captions. */
    private class RefusedDownload : CaptionProvider {
        override suspend fun fetch(
            videoId: String,
            preferredLanguages: List<String>,
            learningLanguage: String?,
        ): CaptionTrackResult = throw CaptionUnavailableException("Player returned LOGIN_REQUIRED: Sign in to confirm you're not a bot")
    }

    companion object {
        private const val JSON3 =
            """{"events":[{"tStartMs":0,"dDurationMs":1500,"segs":[{"utf8":"你好"}]},{"tStartMs":2000,"dDurationMs":1500,"segs":[{"utf8":"再见"}]}]}"""

        private val OFFLINE_PAGE =
            """
            <html><head><meta charset="utf-8"></head><body>
            <div id="movie_player" class="html5-video-player"><video></video></div>
            <script>
            const player = document.getElementById('movie_player');
            var currentTrack = {};
            player.loadModule = function() {};
            player.getOption = function(module, option) {
              if (option === 'tracklist') return [{languageCode: 'zh-Hans', kind: 'asr'}];
              return currentTrack;
            };
            player.setOption = function(module, option, value) {
              currentTrack = value || {};
              if (currentTrack.languageCode) {
                setTimeout(function() {
                  fetch('/api/timedtext?v=abcdefghijk&lang=' + currentTrack.languageCode + '&kind=asr&pot=TOKEN&c=MWEB&fmt=srv3');
                }, 200);
              }
            };
            player.getPlayerResponse = function() {
              return {
                videoDetails: {videoId: 'abcdefghijk', title: '每天中文'},
                captions: {playerCaptionsTracklistRenderer: {captionTracks: [{
                  baseUrl: 'https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=zh-Hans&kind=asr&exp=xpe',
                  languageCode: 'zh-Hans', kind: 'asr', vssId: 'a.zh-Hans', name: {simpleText: 'Chinese (auto-generated)'}
                }]}}
              };
            };
            </script></body></html>
            """.trimIndent()
    }
}
