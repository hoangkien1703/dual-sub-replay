package com.kienhoang.dualsubreplay.ui

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LabCaptionBridgeTest {
    @Test
    fun pageEnginePushesWordChangesWithoutPlaybackPolling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val result = AtomicReference<LiveCaptionSample>()
        val web = AtomicReference<WebView>()
        val advanced = AtomicBoolean(false)
        val diagnostics = CopyOnWriteArrayList<String>()
        try {
            instrumentation.runOnMainSync {
                val view = WebView(instrumentation.targetContext)
                web.set(view)
                view.settings.javaScriptEnabled = true
                view.webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            diagnostics.add("JS: ${message.message()}")
                            return true
                        }
                    }
                installLabCaptionBridge(view) { sample ->
                    diagnostics.add("caption: ${sample.activeWordIndex} at ${sample.mediaTimeMs}")
                    if (sample.text == "one two three" && sample.activeWordIndex == 0 && advanced.compareAndSet(false, true)) {
                        // Advance only after the engine anchored the initial caption, even on a slow emulator.
                        view.evaluateJavascript("video._time = 1.35;", null)
                    }
                    if (sample.activeWordIndex == 1) {
                        result.set(sample)
                        done.countDown()
                    }
                }
                view.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse {
                            // A real navigation gives WebView.url the same identity as location.href.
                            // Intercept every request so the fixture never reaches live YouTube.
                            val html =
                                if (request.isForMainFrame) {
                                    """
                                    <html><body><video></video><span class="ytp-caption-segment">one two three</span>
                                    <script>
                                    const video = document.querySelector('video');
                                    video._time = 1;
                                    Object.defineProperties(video, {
                                      currentTime: {get: () => video._time}, readyState: {get: () => 4},
                                      paused: {get: () => false}, seeking: {get: () => false}
                                    });
                                    video.requestVideoFrameCallback = function() {};
                                    window.fetch = function() { return new Promise(function() {}); };
                                    </script></body></html>
                                    """.trimIndent()
                                } else {
                                    ""
                                }
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            val engine =
                                view.context.assets
                                    .open("youtube-caption-engine.js")
                                    .bufferedReader()
                                    .use { it.readText() }
                            view.evaluateJavascript(labEngineConfigurationScript(true, "en", engine)) {
                                view.evaluateJavascript(
                                    """
                                    JSON.stringify({url: location.href,
                                      bridge: typeof window.DualSubCaptionBridge?.postMessage,
                                      installed: window.__highlightDualSubLabEngineV2Installed,
                                      enabled: window.__dualSubLabOptions?.enabled,
                                      second: document.querySelector('video').currentTime})
                                    """.trimIndent(),
                                ) { diagnostics.add("installed: $it, native URL: ${view.url}") }
                            }
                        }
                    }
                view.loadUrl("https://m.youtube.com/watch?v=obQgWiSX8tY")
            }
            val received = done.await(15, TimeUnit.SECONDS)
            assertTrue("Expected direct engine word update: $diagnostics", received)
            assertEquals("one two three", result.get().text)
            assertEquals(1350L, result.get().mediaTimeMs)
        } finally {
            instrumentation.runOnMainSync { web.get()?.destroy() }
        }
    }
}
