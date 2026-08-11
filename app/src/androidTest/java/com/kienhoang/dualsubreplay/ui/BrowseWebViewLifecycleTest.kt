package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
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
}
