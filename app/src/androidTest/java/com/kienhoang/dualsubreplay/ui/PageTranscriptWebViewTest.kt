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
 * exactly like YouTube's web addresses marked `exp=xpe`, and the app keeps the document the page
 * player itself downloads.
 */
class PageTranscriptWebViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theTranscriptComesFromThePagePlayersOwnDownloadWhenTheAppsIsRefused() =
        withOfflinePage(OFFLINE_PAGE, PageFirstCaptionProvider(PageTranscriptSource.Shared, RefusedDownload())) { model, page, requests ->
            compose.waitUntil(30_000) { model.state.value.resolvedSourceLanguage == "zh-Hans" }
            val state = model.state.value
            assertFalse("The full transcript loaded, so live subtitles are not needed", state.liveFallback)
            assertTrue(state.generatedCaptions)
            // The app never asks again with the token: it keeps what the page player downloaded.
            assertEquals("Only the page player sent its token: $requests", 1, requests.count { it.contains("pot=TOKEN") })
            assertEquals("Captions the app switched on are switched back off", "{}", javascript(page, "JSON.stringify(currentTrack)"))
        }

    @Test
    fun captionsThePageLoadsLaterReplaceLiveSubtitles() =
        withOfflinePage(
            OFFLINE_PAGE.replace("const playerLoadsCaptionsWhenAsked = true;", "const playerLoadsCaptionsWhenAsked = false;"),
            PageFirstCaptionProvider(PageTranscriptSource.Shared, RefusedDownload(), pageTimeoutMs = 2_000),
        ) { model, page, _ ->
            compose.waitUntil(30_000) { model.state.value.liveFallback }
            val problem = model.state.value.fullTranscriptProblem ?: ""
            assertTrue("The sheet says why: $problem", problem.contains("LOGIN_REQUIRED"))
            // Playback starts: the page player downloads the captions on its own.
            javascript(page, "startPlaying()")
            compose.waitUntil(30_000) { !model.state.value.liveFallback && model.state.value.resolvedSourceLanguage == "zh-Hans" }
            assertTrue(model.state.value.generatedCaptions)
            assertEquals(null, model.state.value.fullTranscriptProblem)
        }

    @Test
    fun captionsThePlayerDownloadedAreUsedWhenItsDetailsListNoTrack() =
        withOfflinePage(
            OFFLINE_PAGE
                .replace("captions: {playerCaptionsTracklistRenderer", "noCaptions: {playerCaptionsTracklistRenderer")
                .replace(
                    "if (option === 'tracklist') return [{languageCode: 'zh-Hans', kind: 'asr'}];",
                    "if (option === 'tracklist') return [];",
                ).replace("window.startPlaying = playerDownload;", "window.startPlaying = playerDownload; playerDownload();"),
            PageFirstCaptionProvider(PageTranscriptSource.Shared, RefusedDownload()),
        ) { model, _, _ ->
            // As on the owner's phone: YouTube's player shows captions, its details list none.
            compose.waitUntil(30_000) { model.state.value.resolvedSourceLanguage == "zh-Hans" }
            assertFalse(model.state.value.liveFallback)
            assertTrue(model.state.value.generatedCaptions)
        }

    @Test
    fun youTubesTranscriptPanelIsTheLastResort() =
        withOfflinePage(
            OFFLINE_PAGE
                .replace("captions: {playerCaptionsTracklistRenderer", "noCaptions: {playerCaptionsTracklistRenderer")
                .replace(
                    "if (option === 'tracklist') return [{languageCode: 'zh-Hans', kind: 'asr'}];",
                    "if (option === 'tracklist') return [];",
                ).replace("const playerLoadsCaptionsWhenAsked = true;", "const playerLoadsCaptionsWhenAsked = false;")
                .replace("</script></body>", "</script>" + PANEL_PAGE_PART + "</body>"),
            PageFirstCaptionProvider(PageTranscriptSource.Shared, RefusedDownload()),
        ) { model, page, _ ->
            compose.waitUntil(60_000) { !model.state.value.liveFallback && model.state.value.resolvedSourceLanguage == "zh" }
            assertFalse("The panel names no auto-generated track", model.state.value.generatedCaptions)
            val clicks = javascript(page, "clicks.join(',')")
            assertTrue("The description was unfolded, then the transcript opened: $clicks", clicks.startsWith("more,transcript"))
        }

    private fun withOfflinePage(
        offlinePage: String,
        provider: CaptionProvider,
        block: (AppViewModel, WebView, List<String>) -> Unit,
    ) {
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
        instrumentation.runOnMainSync { model = AppViewModel(application, provider) }
        val page = AtomicReference<WebView>()
        val captionRequests = Collections.synchronizedList(mutableListOf<String>())
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalYouTubeRequestInterceptor provides { view, request ->
                        page.compareAndSet(null, view)
                        val url = request.url
                        when {
                            url.path == "/youtubei/v1/get_transcript" ->
                                WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(PANEL_REPLY.toByteArray()))
                            url.path == "/api/timedtext" -> {
                                captionRequests += url.query.orEmpty()
                                val body = if (url.getQueryParameter("pot") == "TOKEN") JSON3 else ""
                                WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                            }
                            request.isForMainFrame ->
                                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(offlinePage.toByteArray()))
                            else -> WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    },
                ) {
                    LearningPlayerRoot(model)
                }
            }
            compose.waitUntil(30_000) { page.get() != null }
            block(model, page.get(), captionRequests)
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

        private const val PANEL_REPLY =
            """{"actions":[{"updateEngagementPanelAction":{"content":{"transcriptRenderer":{"content":{"transcriptSearchPanelRenderer":""" +
                """{"body":{"transcriptSegmentListRenderer":{"initialSegments":[""" +
                """{"transcriptSegmentRenderer":{"startMs":"0","endMs":"1500","snippet":{"runs":[{"text":"你好"}]}}},""" +
                """{"transcriptSegmentRenderer":{"startMs":"2000","endMs":"3500","snippet":{"runs":[{"text":"再见"}]}}}]}}}}}}}}]}"""

        /** A folded description whose transcript button opens YouTube's transcript panel. */
        private val PANEL_PAGE_PART =
            """
            <ytm-slim-video-information-renderer><button class="slim-video-information-show-more">more</button></ytm-slim-video-information-renderer>
            <div id="description"></div><div id="panel"></div>
            <script>
            var clicks = [];
            document.querySelector('.slim-video-information-show-more').addEventListener('click', function() {
              clicks.push('more');
              document.getElementById('description').innerHTML =
                '<ytm-video-description-transcript-section-renderer><button>Show transcript</button></ytm-video-description-transcript-section-renderer>';
              document.querySelector('ytm-video-description-transcript-section-renderer button').addEventListener('click', function() {
                clicks.push('transcript');
                fetch('/youtubei/v1/get_transcript', {method: 'POST', body: '{}'}).then(function(reply) { return reply.json(); }).then(function() {
                  document.getElementById('panel').innerHTML =
                    '<ytm-engagement-panel-section-list-renderer><button class="close-button" onclick="clicks.push(\'close\')">x</button>' +
                    '<ytm-transcript-segment-renderer><div class="segment-timestamp">0:00</div><div class="segment-text">你好</div></ytm-transcript-segment-renderer>' +
                    '</ytm-engagement-panel-section-list-renderer>';
                });
              });
            });
            </script>
            """.trimIndent()

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
            const playerLoadsCaptionsWhenAsked = true;
            function playerDownload() {
              const request = new XMLHttpRequest();
              request.open('GET', '/api/timedtext?v=abcdefghijk&lang=zh-Hans&kind=asr&pot=TOKEN&c=MWEB&fmt=json3');
              request.send();
            }
            window.startPlaying = playerDownload;
            player.setOption = function(module, option, value) {
              currentTrack = value || {};
              if (playerLoadsCaptionsWhenAsked && currentTrack.languageCode) setTimeout(playerDownload, 200);
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
