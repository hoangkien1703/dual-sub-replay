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

    fun focusPlayer() {
        webView?.evaluateJavascript(
            """
            (function() {
              const player = document.querySelector('ytm-watch ytm-player') ||
                document.querySelector('#player-container-id') ||
                document.querySelector('ytm-watch #player') ||
                document.querySelector('video');
              if (!player) return false;
              player.scrollIntoView({ block: 'start', behavior: 'smooth' });
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
    watchPageActive: Boolean,
    onUrlChanged: (String) -> Unit,
    onPlaybackSecond: (Float) -> Unit,
    onPlayerBottomFraction: (Float) -> Unit,
) {
    val context = LocalContext.current
    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentOnPlaybackSecond by rememberUpdatedState(onPlaybackSecond)
    val currentOnPlayerBottomFraction by rememberUpdatedState(onPlayerBottomFraction)
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

    LaunchedEffect(webView, watchPageActive) {
        while (true) {
            delay(400)
            val watchMode = watchPageActive.toString()
            webView.evaluateJavascript(
                """
                (function() {
                  if ($watchMode) {
                    let style = document.getElementById('dualsub-player-style');
                    if (!style) {
                      style = document.createElement('style');
                      style.id = 'dualsub-player-style';
                      style.textContent = `
                        @media (orientation: portrait) {
                          html.dualsub-watch ytm-watch ytm-player,
                          html.dualsub-watch ytm-watch #player-container-id,
                          html.dualsub-watch ytm-watch .player-container,
                          html.dualsub-watch ytm-watch #player {
                            width: 100vw !important;
                            max-width: 100vw !important;
                            height: 56.25vw !important;
                            min-height: 56.25vw !important;
                            max-height: 56.25vw !important;
                          }
                          html.dualsub-watch ytm-watch video {
                            width: 100% !important;
                            height: 100% !important;
                            object-fit: contain !important;
                          }
                        }
                      `;
                      document.head.appendChild(style);
                    }
                    document.documentElement.classList.add('dualsub-watch');
                  } else {
                    document.documentElement.classList.remove('dualsub-watch');
                  }
                  const video = document.querySelector('video');
                  if (!video || !Number.isFinite(video.currentTime)) return '-1,-1';
                  let player = document.querySelector('ytm-watch ytm-player') ||
                    document.querySelector('#player-container-id') ||
                    document.querySelector('ytm-watch #player') ||
                    document.querySelector('.player-container') || video;
                  let bounds = player.getBoundingClientRect();
                  if (bounds.height < 60) bounds = video.getBoundingClientRect();
                  const viewportHeight = Math.max(window.innerHeight, 1);
                  return [video.currentTime, bounds.bottom / viewportHeight].join(',');
                })();
                """.trimIndent(),
            ) { rawValue ->
                val values = rawValue.trim().trim('"').split(',')
                values.getOrNull(0)?.toFloatOrNull()
                    ?.takeIf { it >= 0f }
                    ?.let(currentOnPlaybackSecond)
                values.getOrNull(1)?.toFloatOrNull()
                    ?.takeIf { it in 0.12f..0.80f }
                    ?.let(currentOnPlayerBottomFraction)
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
