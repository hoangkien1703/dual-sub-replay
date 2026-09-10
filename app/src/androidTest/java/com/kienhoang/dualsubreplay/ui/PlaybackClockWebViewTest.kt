package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackClockWebViewTest {
    @Test fun mediaEventsAndElementReplacementSurviveFullscreenTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val snapshots = mutableListOf<WebPlaybackSnapshot?>()
        var web: WebView? = null
        val actions =
            listOf(
                "",
                "video.dispatchEvent(new Event('waiting'));",
                "video.dispatchEvent(new Event('playing')); video._rate = 2; video._time = 8;",
                "video._seeking = true; video.dispatchEvent(new Event('seeking'));",
                "video._seeking = false; video._time = 2; video.dispatchEvent(new Event('seeked'));",
                "document.dispatchEvent(new Event('fullscreenchange')); video._time = 3;",
                "document.dispatchEvent(new Event('fullscreenchange')); video._time = 4;",
                "video.remove(); video = makeVideo(); video._time = 20;",
            )
        try {
            instrumentation.runOnMainSync {
                web =
                    WebView(instrumentation.targetContext).apply {
                        settings.javaScriptEnabled = true
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(
                                    view: WebView,
                                    url: String,
                                ) {
                                    fun next(index: Int) {
                                        if (index == actions.size) {
                                            done.countDown()
                                            return
                                        }
                                        view.evaluateJavascript(actions[index] + "\n" + WEB_PLAYBACK_SNAPSHOT_SCRIPT) {
                                            snapshots.add(parseWebPlaybackSnapshot(it))
                                            next(index + 1)
                                        }
                                    }
                                    next(0)
                                }
                            }
                        loadDataWithBaseURL(
                            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                            """
                            <html><body><script>
                            function makeVideo() {
                              const v = document.createElement('video');
                              v._time = 1; v._rate = 1; v._seeking = false;
                              Object.defineProperties(v, {
                                currentTime: {get: () => v._time}, playbackRate: {get: () => v._rate},
                                paused: {get: () => false}, readyState: {get: () => 4}, seeking: {get: () => v._seeking}
                              });
                              v.requestVideoFrameCallback = function() {};
                              document.body.appendChild(v); return v;
                            }
                            let video = makeVideo();
                            </script></body></html>
                            """.trimIndent(),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertEquals(actions.size, snapshots.size)
            val result = snapshots.map { requireNotNull(it) }
            assertFalse(result[0].buffering)
            assertTrue(result[1].buffering)
            assertFalse(result[2].buffering)
            assertEquals(2.0, result[2].playbackRate, 0.0)
            assertTrue(result[3].seeking)
            assertNotEquals(result[2].sessionId, result[3].sessionId)
            assertFalse(result[4].seeking)
            assertEquals(result[4].sessionId, result[6].sessionId)
            assertEquals(4f, result[6].currentSecond!!, 0.01f)
            assertNotEquals(result[6].sessionId, result[7].sessionId)
            assertEquals(20f, result[7].currentSecond!!, 0.01f)
            assertTrue(result.all { it.sampledAtEpochMs > 0 })
        } finally {
            instrumentation.runOnMainSync { web?.destroy() }
        }
    }
}
