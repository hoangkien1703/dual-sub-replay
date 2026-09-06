package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseWebViewLifecycleTest {
    @Test
    fun embeddedWebViewSecurityPolicyDisablesLocalAndMixedContentAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            webView.settings.applyEmbeddedSecurityPolicy()

            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertEquals(
                WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                webView.settings.mixedContentMode,
            )
            assertTrue(webView.settings.safeBrowsingEnabled)
            webView.destroySafely()
        }
    }

    @Test
    fun destroySafelyDetachesWebViewAndIsIdempotent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val parent = FrameLayout(instrumentation.targetContext)
            val webView = WebView(instrumentation.targetContext)
            parent.addView(webView)

            webView.destroySafely()
            assertNull(webView.parent)

            webView.destroySafely()
            assertNull(webView.parent)
        }
    }

    @Test
    fun oneNativePageVideoProvidesPlaybackTimeAndReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val snapshotResult = AtomicReference<String?>()
        val replayResult = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(WEB_PLAYBACK_SNAPSHOT_SCRIPT) { snapshot ->
                            snapshotResult.set(snapshot)
                            view.evaluateJavascript(webReplayScript(14.5f)) { replayed ->
                                replayResult.set(replayed)
                                finished.countDown()
                            }
                        }
                    }
                }
                loadDataWithBaseURL(
                    "https://m.youtube.com/watch?v=testvideo01",
                    """
                    <!doctype html>
                    <html><body>
                      <video id="native-video"></video>
                      <section id="details">Comments and recommendations</section>
                      <script>
                        const video = document.getElementById('native-video');
                        Object.defineProperty(video, 'currentTime', {
                          configurable: true,
                          get: function() { return this._time || 8.25; },
                          set: function(value) { this._time = value; }
                        });
                        video.play = function() { return Promise.resolve(); };
                      </script>
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }

        assertTrue("WebView fixture did not finish", finished.await(10, TimeUnit.SECONDS))
        val snapshot = parseWebPlaybackSnapshot(snapshotResult.get())
        assertEquals("https://m.youtube.com/watch?v=testvideo01", snapshot?.url)
        assertEquals(8.25f, snapshot?.currentSecond)
        assertEquals("true", replayResult.get())

        val replayedTime = AtomicReference<String?>()
        val replayCheckFinished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("document.querySelector('video').currentTime") {
                replayedTime.set(it)
                replayCheckFinished.countDown()
            }
        }
        assertTrue(replayCheckFinished.await(10, TimeUnit.SECONDS))
        assertEquals("14.5", replayedTime.get())

        instrumentation.runOnMainSync { webView.destroySafely() }
    }

    @Test
    fun liveCaptionObserverReadsMutationsAndRestoresCaptionButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pageLoaded = CountDownLatch(1)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        pageLoaded.countDown()
                    }
                }
                loadDataWithBaseURL(
                    "https://m.youtube.com/watch?v=abcdefghijk",
                    """
                    <!doctype html>
                    <html><body>
                      <video id="native-video"></video>
                      <button class="ytmClosedCaptioningButtonButton" aria-label="字幕がオフになりました" aria-pressed="false">CC</button>
                      <div id="movie_player"></div>
                      <div class="ytp-caption-window-container">
                        <span class="ytp-caption-segment">Hello</span>
                      </div>
                      <script>
                        const video = document.getElementById('native-video');
                        Object.defineProperty(video, 'currentTime', {
                          configurable: true,
                          get: function() { return 4.5; }
                        });
                        const player = document.getElementById('movie_player');
                        player.getOption = function() {
                          return document.querySelector('button').getAttribute('aria-pressed') === 'true' ? {languageCode:'en'} : {};
                        };
                        player.setOption = function() {};
                        player.getPlayerResponse = function() { return {videoDetails:{videoId:'abcdefghijk'}}; };
                        document.querySelector('.ytmClosedCaptioningButtonButton').onclick = function() {
                          this.setAttribute(
                            'aria-pressed',
                            this.getAttribute('aria-pressed') === 'true' ? 'false' : 'true'
                          );
                        };
                      </script>
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }

        assertTrue("Live-caption fixture did not load", pageLoaded.await(10, TimeUnit.SECONDS))
        assertEquals("true", evaluateJavascript(webView, webLiveCaptionConfigurationScript(enabled = true)))
        assertEquals(
            "\"true\"",
            evaluateJavascript(
                webView,
                "document.querySelector('.ytmClosedCaptioningButtonButton').getAttribute('aria-pressed')",
            ),
        )
        evaluateJavascript(
            webView,
            "document.querySelector('.ytp-caption-segment').textContent = 'Hello world'",
        )
        Thread.sleep(150)
        val snapshot = parseWebPlaybackSnapshot(evaluateJavascript(webView, WEB_PLAYBACK_SNAPSHOT_SCRIPT))
        assertEquals("Hello world", snapshot?.liveCaption?.text)
        assertEquals("abcdefghijk", snapshot?.liveCaption?.videoId)
        assertEquals("en", snapshot?.liveCaption?.languageCode)
        assertTrue((snapshot?.liveCaption?.revision ?: 0L) >= 2L)
        assertEquals(4_500L, snapshot?.liveCaption?.mediaTimeMs)
        assertEquals("true", evaluateJavascript(webView, webLiveCaptionConfigurationScript(enabled = false)))
        assertEquals(
            "\"false\"",
            evaluateJavascript(
                webView,
                "document.querySelector('.ytmClosedCaptioningButtonButton').getAttribute('aria-pressed')",
            ),
        )

        instrumentation.runOnMainSync { webView.destroySafely() }
    }

    @Test fun liveObserverRejectsUntrustedOrigin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                }
                loadDataWithBaseURL("https://youtube.com.evil.test/", "<html><video></video></html>", "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals("false", evaluateJavascript(view, webLiveCaptionConfigurationScript(true)))
            assertNull(parseWebPlaybackSnapshot(evaluateJavascript(view, WEB_PLAYBACK_SNAPSHOT_SCRIPT)))
        } finally { instrumentation.runOnMainSync { view.destroySafely() } }
    }

    @Test fun nativeSettingsReceiveClickAndTouchEventsWithCaptionScriptsInstalled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                }
                loadDataWithBaseURL("https://m.youtube.com/watch?v=abcdefghijk", """
                    <html><body><div id="movie_player"><video></video>
                    <button class="player-settings-icon" aria-label="Playback Settings">Settings</button>
                    </div><ytm-mobile-topbar-renderer><button aria-label="More options">More</button></ytm-mobile-topbar-renderer>
                    <bottom-sheet-container id="native-menu" role="dialog" aria-modal="true" style="display:none">
                    <ytw-scrim style="display:block;position:fixed;left:0;top:0;width:300px;height:400px">YouTube settings fixture</ytw-scrim>
                    </bottom-sheet-container>
                    <script>
                      window.clicks = 0; window.touches = 0; window.bubbled = 0;
                      document.querySelectorAll('button').forEach(function(button) {
                        button.addEventListener('click', function() {
                          window.clicks++; document.getElementById('native-menu').style.display = 'contents';
                        });
                        button.addEventListener('touchend', function() { window.touches++; });
                      });
                      document.addEventListener('click', function() { window.bubbled++; });
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            evaluateJavascript(view, webLiveCaptionConfigurationScript(true))
            evaluateJavascript(view, webCaptionVisibilityScript(true))
            evaluateJavascript(view, WEB_PLAYBACK_SNAPSHOT_SCRIPT)
            assertEquals("true", evaluateJavascript(view, """
                (function() {
                  let allowed = true;
                  document.querySelectorAll('button').forEach(function(button) {
                    allowed = button.dispatchEvent(new Event('touchend', {bubbles:true, cancelable:true})) && allowed;
                    button.click();
                  });
                  return allowed && window.clicks === 2 && window.touches === 2 && window.bubbled === 2 &&
                    !document.getElementById('dualsub-settings-overlay');
                })();
            """.trimIndent()))
            assertTrue(parseWebPlaybackSnapshot(evaluateJavascript(view, WEB_PLAYBACK_SNAPSHOT_SCRIPT))!!.nativeDialogVisible)
            evaluateJavascript(view, "document.getElementById('native-menu').style.display = 'none'")
            assertFalse(parseWebPlaybackSnapshot(evaluateJavascript(view, WEB_PLAYBACK_SNAPSHOT_SCRIPT))!!.nativeDialogVisible)
        } finally { instrumentation.runOnMainSync { view.destroySafely() } }
    }

    private fun evaluateJavascript(webView: WebView, script: String): String? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String?>()
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result.set(value)
                completed.countDown()
            }
        }
        assertTrue("JavaScript evaluation did not finish", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
