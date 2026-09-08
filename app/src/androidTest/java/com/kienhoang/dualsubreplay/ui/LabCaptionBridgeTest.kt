package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LabCaptionBridgeTest {
    @Test
    fun pageEnginePushesWordChangesWithoutPlaybackPolling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val result = AtomicReference<LiveCaptionSample>()
        val web = AtomicReference<WebView>()
        try {
            instrumentation.runOnMainSync {
                val view = WebView(instrumentation.targetContext)
                web.set(view)
                view.settings.javaScriptEnabled = true
                installLabCaptionBridge(view) { sample ->
                    if (sample.activeWordIndex == 1) {
                        result.set(sample)
                        done.countDown()
                    }
                }
                view.webViewClient =
                    object : WebViewClient() {
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
                                view.evaluateJavascript("setTimeout(function() { video._time = 1.35; }, 200);", null)
                            }
                        }
                    }
                view.loadDataWithBaseURL(
                    "https://m.youtube.com/watch?v=obQgWiSX8tY",
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
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue("Expected direct engine word update", done.await(15, TimeUnit.SECONDS))
            assertEquals("one two three", result.get().text)
            assertEquals(1350L, result.get().mediaTimeMs)
        } finally {
            instrumentation.runOnMainSync { web.get()?.destroy() }
        }
    }
}
