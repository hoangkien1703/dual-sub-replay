package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseWebViewLifecycleTest {
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
    fun watchDetailsScriptHidesSiblingPlayerButKeepsDetailsAndRecommendations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(WATCH_DETAILS_SCRIPT) {
                            view.evaluateJavascript(
                                """
                                (function() {
                                  return JSON.stringify({
                                    playerDisplay: getComputedStyle(document.getElementById('native-player')).display,
                                    detailsDisplay: getComputedStyle(document.getElementById('details')).display,
                                    recommendationDisplay: getComputedStyle(document.getElementById('recommendation')).display,
                                    nativeAutoplay: document.getElementById('native-video').hasAttribute('autoplay'),
                                    previewAutoplay: document.getElementById('preview-video').hasAttribute('autoplay')
                                  });
                                })();
                                """.trimIndent(),
                            ) { value ->
                                result.set(value)
                                finished.countDown()
                            }
                        }
                    }
                }
                loadDataWithBaseURL(
                    "https://m.youtube.com/watch?v=test",
                    """
                    <!doctype html>
                    <html><head></head><body>
                      <ytm-app>
                        <ytm-player id="native-player">
                          <div id="player-container-id"><video id="native-video" autoplay></video></div>
                        </ytm-player>
                        <ytm-watch><section id="details">Comments</section></ytm-watch>
                        <ytm-compact-video-renderer id="recommendation">
                          <video id="preview-video" autoplay></video>
                        </ytm-compact-video-renderer>
                      </ytm-app>
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }

        assertTrue("WebView fixture did not finish", finished.await(10, TimeUnit.SECONDS))
        val jsonText = JSONTokener(result.get()).nextValue() as String
        val state = JSONObject(jsonText)
        assertEquals("none", state.getString("playerDisplay"))
        assertFalse(state.getString("detailsDisplay") == "none")
        assertFalse(state.getString("recommendationDisplay") == "none")
        assertFalse(state.getBoolean("nativeAutoplay"))
        assertTrue(state.getBoolean("previewAutoplay"))

        instrumentation.runOnMainSync { webView.destroySafely() }
    }
}
