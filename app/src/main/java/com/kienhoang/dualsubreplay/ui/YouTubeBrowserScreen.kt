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
              const video = document.querySelector('video');
              const player = video && (
                video.closest('ytm-player') ||
                video.closest('.html5-video-player') ||
                video.closest('#player-container-id') ||
                video.parentElement
              );
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
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
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
                  const video = document.querySelector('video');
                  if (!video || !Number.isFinite(video.currentTime)) return '-1,-1';

                  let player = video;
                  if ($watchMode && window.matchMedia('(orientation: portrait)').matches) {
                    const viewportWidth = Math.max(window.innerWidth, 1);
                    const desiredHeight = Math.round(viewportWidth * 9 / 16);
                    const videoBounds = video.getBoundingClientRect();
                    const playerNodes = [];
                    let node = video.parentElement;

                    for (let depth = 0; node && node !== document.body && depth < 12; depth++) {
                      const bounds = node.getBoundingClientRect();
                      const isPlayerWidth = bounds.width >= viewportWidth * 0.65;
                      const sharesTop = Math.abs(bounds.top - videoBounds.top) <= 64;
                      const sharesBottom = Math.abs(bounds.bottom - videoBounds.bottom) <= 96;
                      const isPlayerSized = bounds.height >= 60 && bounds.height <= desiredHeight * 1.45;

                      if (isPlayerWidth && sharesTop && sharesBottom && isPlayerSized) {
                        playerNodes.push(node);
                      } else if (playerNodes.length > 0 && bounds.height > desiredHeight * 1.8) {
                        break;
                      }
                      node = node.parentElement;
                    }

                    if (playerNodes.length === 0 && video.parentElement) {
                      playerNodes.push(video.parentElement);
                    }

                    for (const playerNode of playerNodes) {
                      playerNode.style.setProperty('width', '100%', 'important');
                      playerNode.style.setProperty('max-width', '100vw', 'important');
                      playerNode.style.setProperty('height', desiredHeight + 'px', 'important');
                      playerNode.style.setProperty('min-height', desiredHeight + 'px', 'important');
                      playerNode.style.setProperty('max-height', desiredHeight + 'px', 'important');
                      playerNode.style.setProperty('padding-bottom', '0', 'important');
                      playerNode.style.setProperty('overflow', 'hidden', 'important');
                    }

                    video.style.setProperty('width', '100%', 'important');
                    video.style.setProperty('height', '100%', 'important');
                    video.style.setProperty('max-height', 'none', 'important');
                    video.style.setProperty('object-fit', 'contain', 'important');
                    player = playerNodes[playerNodes.length - 1] || video;
                  } else {
                    player = video.closest('ytm-player') ||
                      video.closest('.html5-video-player') ||
                      video.closest('#player-container-id') ||
                      video.parentElement || video;
                  }

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
