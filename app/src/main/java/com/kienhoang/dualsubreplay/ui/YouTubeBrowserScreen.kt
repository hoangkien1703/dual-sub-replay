package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

internal class YouTubeWebController {
    private var webView: WebView? = null

    internal fun attach(view: WebView) {
        webView = view
    }

    internal fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    fun navigate(url: String) {
        if (url.startsWith("https://") || url.startsWith("http://")) webView?.loadUrl(url)
    }

    fun goBack() {
        webView?.goBack()
    }

    fun replayFrom(second: Float) {
        val safeSecond = second.coerceAtLeast(0f)
        webView?.evaluateJavascript(
            """
            (function() {
              const video = document.querySelector('video');
              if (!video) return false;
              video.currentTime = $safeSecond;
              const playResult = video.play();
              if (playResult && playResult.catch) playResult.catch(function() {});
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeWebPage(
    controller: YouTubeWebController,
    initialUrl: String,
    navigationRequestId: Long,
    onUrlChanged: (String) -> Unit,
    onPlaybackSecond: (Float) -> Unit,
) {
    val context = LocalContext.current
    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentOnPlaybackSecond by rememberUpdatedState(onPlaybackSecond)
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(true)
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                private fun reportNavigation(view: WebView, url: String?) {
                    val currentUrl = url?.takeIf(String::isNotBlank) ?: return
                    canGoBack = view.canGoBack()
                    currentOnUrlChanged(currentUrl)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in setOf("http", "https")

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    reportNavigation(view, url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    reportNavigation(view, url)
                }
            }
            controller.attach(this)
            loadUrl(initialUrl)
        }
    }

    LaunchedEffect(navigationRequestId) {
        if (navigationRequestId > 0L) controller.navigate(initialUrl)
    }

    LaunchedEffect(webView) {
        while (true) {
            delay(400)
            webView.evaluateJavascript(
                """
                (function() {
                  const video = document.querySelector('video');
                  if (!video || !Number.isFinite(video.currentTime)) return -1;
                  return video.currentTime;
                })();
                """.trimIndent(),
            ) { rawValue ->
                rawValue.trim().trim('"').toFloatOrNull()
                    ?.takeIf { it >= 0f }
                    ?.let(currentOnPlaybackSecond)
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            controller.detach(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (canGoBack) controller.goBack() else (context as? Activity)?.finish()
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
    )
}
