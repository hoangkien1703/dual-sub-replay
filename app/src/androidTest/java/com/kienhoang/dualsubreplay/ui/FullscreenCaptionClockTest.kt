package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FullscreenCaptionClockTest {
    @Test fun frozenFrameCallbacksDoNotTimestampNewCaptionsInThePast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        val web = AtomicReference<WebView>()
        try {
            instrumentation.runOnMainSync {
                val view = WebView(instrumentation.targetContext)
                web.set(view)
                view.settings.javaScriptEnabled = true
                view.webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            view.evaluateJavascript(webLiveCaptionConfigurationScript(true)) {
                                view.evaluateJavascript(
                                    """
                                    window['$LIVE_CAPTION_CAPTURE_STATE_KEY'].latestMediaSecond = 1;
                                    document.querySelector('video')._time = 12;
                                    document.querySelector('.ytp-caption-segment').textContent = 'New caption';
                                    """.trimIndent(),
                                ) {
                                    view.evaluateJavascript(WEB_PLAYBACK_SNAPSHOT_SCRIPT) { value ->
                                        result.set(value)
                                        done.countDown()
                                    }
                                }
                            }
                        }
                    }
                view.loadDataWithBaseURL(
                    "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                    """
                    <html><body><video></video><span class="ytp-caption-segment">Old caption</span>
                    <script>
                    const video = document.querySelector('video');
                    video._time = 1;
                    Object.defineProperty(video, 'currentTime', { get: function() { return this._time; } });
                    video.requestVideoFrameCallback = function() {};
                    </script></body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue(done.await(15, TimeUnit.SECONDS))
            val snapshot = parseWebPlaybackSnapshot(result.get())!!
            assertEquals(12f, snapshot.currentSecond!!, 0.01f)
            assertEquals(12_000, snapshot.liveCaption!!.mediaTimeMs)
        } finally {
            instrumentation.runOnMainSync { web.get()?.destroy() }
        }
    }
}
