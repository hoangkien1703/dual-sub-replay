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
}
