package com.kienhoang.dualsubreplay.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlayerProbeWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun probeReadsNestedPlayerWithoutChangingInlineStyles() {
        lateinit var webView: WebView
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url?.startsWith("https://fixture.test") == true) loaded.countDown()
                    }
                }
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 400, 800)
                loadDataWithBaseURL(
                    "https://fixture.test/",
                    """
                    <html><body style="margin:0">
                      <ytm-player id="player" style="width:320px;height:180px">
                        <video id="video" style="width:160px;height:90px"></video>
                      </ytm-player>
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        }

        assertTrue("Fixture page did not load", loaded.await(10, TimeUnit.SECONDS))
        val styleSnapshot =
            "JSON.stringify([document.getElementById('player').getAttribute('style')," +
                "document.getElementById('video').getAttribute('style')]);"
        val before = evaluate(webView, styleSnapshot)
        val telemetry = evaluate(webView, PLAYER_PROBE_SCRIPT)
        val after = evaluate(webView, styleSnapshot)

        assertNotEquals("null", telemetry)
        assertEquals(before, after)
        instrumentation.runOnMainSync { webView.destroy() }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result = it
                completed.countDown()
            }
        }
        assertTrue("JavaScript evaluation timed out", completed.await(10, TimeUnit.SECONDS))
        return result
    }
}
