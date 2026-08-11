package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val PLAYER_LOG_TAG = "DualSubPlayer"
private const val PLAYER_PROBE_INTERVAL_MS = 500L

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
              const videos = Array.from(document.querySelectorAll('video'));
              const ranked = videos.map(function(candidate) {
                const bounds = candidate.getBoundingClientRect();
                const visibleWidth = Math.max(0, Math.min(bounds.right, window.innerWidth) - Math.max(bounds.left, 0));
                const visibleHeight = Math.max(0, Math.min(bounds.bottom, window.innerHeight) - Math.max(bounds.top, 0));
                return { video: candidate, area: visibleWidth * visibleHeight };
              }).sort(function(left, right) { return right.area - left.area; });
              const video = ranked.length > 0 ? ranked[0].video : null;
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

    fun scrollPlayerIntoView() {
        webView?.evaluateJavascript(
            """
            (function() {
              const videos = Array.from(document.querySelectorAll('video'));
              const ranked = videos.map(function(candidate) {
                const bounds = candidate.getBoundingClientRect();
                const visibleWidth = Math.max(0, Math.min(bounds.right, window.innerWidth) - Math.max(bounds.left, 0));
                const visibleHeight = Math.max(0, Math.min(bounds.bottom, window.innerHeight) - Math.max(bounds.top, 0));
                return { video: candidate, area: visibleWidth * visibleHeight };
              }).sort(function(left, right) { return right.area - left.area; });
              const video = ranked.length > 0 ? ranked[0].video : null;
              const player = video && (
                video.closest('ytm-player') ||
                video.closest('.html5-video-player') ||
                video.closest('#player-container-id') ||
                video.closest('#player-container') ||
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
    onPlayerTelemetry: (PlayerTelemetry) -> Unit,
    onPlayerBottomFraction: (Float) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentOnPlayerTelemetry by rememberUpdatedState(onPlayerTelemetry)
    val currentOnPlayerBottomFraction by rememberUpdatedState(onPlayerBottomFraction)
    var canGoBack by remember { mutableStateOf(false) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var lastTelemetryLogMs by remember { mutableStateOf(0L) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var rendererRecoveryUsed by remember { mutableStateOf(false) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }

    val webView = remember(webViewGeneration) {
        if (BuildConfig.DEBUG) {
            val packageInfo = WebView.getCurrentWebViewPackage()
            Log.d(
                PLAYER_LOG_TAG,
                "WebView=${packageInfo?.packageName ?: "unknown"}/${packageInfo?.versionName ?: "unknown"}",
            )
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
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

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageError = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    rendererRecoveryUsed = false
                    webViewUnavailable = false
                    reportNavigation(view, url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        pageError = "YouTube could not load: ${error.description}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        pageError = "YouTube returned HTTP ${errorResponse.statusCode}."
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val message = if (detail.didCrash()) {
                        "The Android WebView renderer crashed."
                    } else {
                        "Android stopped the WebView renderer to reclaim memory."
                    }
                    Log.e(PLAYER_LOG_TAG, message)
                    webViewUnavailable = true
                    controller.detach(view)
                    view.post {
                        (view.parent as? ViewGroup)?.removeView(view)
                        runCatching { view.destroy() }
                        if (!rendererRecoveryUsed) {
                            rendererRecoveryUsed = true
                            webViewUnavailable = false
                            webViewGeneration += 1
                        } else {
                            pageError = "$message Tap Reload to recreate it."
                        }
                    }
                    return true
                }
            }
            controller.attach(this)
            loadUrl(initialUrl)
        }
    }

    LaunchedEffect(navigationRequestId) {
        if (navigationRequestId > 0L) controller.navigate(initialUrl)
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (lifecycleStarted) webView.onResume() else webView.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleStarted) webView.onResume() else webView.onPause()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { webView.onPause() }
        }
    }

    LaunchedEffect(webView, watchPageActive, lifecycleStarted) {
        if (!watchPageActive || !lifecycleStarted) return@LaunchedEffect
        while (isActive) {
            val rawValue = webView.evaluateJavascriptAwait(PLAYER_PROBE_SCRIPT)
            val telemetry = PlayerTelemetryParser.parse(rawValue)
            if (telemetry != null) {
                currentOnPlayerTelemetry(telemetry)
                currentOnPlayerBottomFraction(telemetry.playerBottomFraction)
                if (BuildConfig.DEBUG && SystemClock.elapsedRealtime() - lastTelemetryLogMs >= 2_000L) {
                    lastTelemetryLogMs = SystemClock.elapsedRealtime()
                    Log.d(
                        PLAYER_LOG_TAG,
                        "ready=${telemetry.readyState} network=${telemetry.networkState} " +
                            "frames=${telemetry.decodedFrameCount} viewport=${telemetry.viewportWidth}x${telemetry.viewportHeight} " +
                            "player=[${telemetry.playerLeft},${telemetry.playerTop}," +
                            "${telemetry.playerRight},${telemetry.playerBottom}] " +
                            "video=${telemetry.videoWidth}x${telemetry.videoHeight}",
                    )
                }
            }
            delay(PLAYER_PROBE_INTERVAL_MS)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            controller.detach(webView)
            runCatching { webView.stopLoading() }
            if (!webViewUnavailable) runCatching { webView.destroy() }
        }
    }

    BackHandler {
        if (canGoBack) controller.goBack() else (context as? Activity)?.finish()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        pageError?.let { message ->
            WebPageErrorCard(
                message = message,
                onReload = {
                    pageError = null
                    rendererRecoveryUsed = false
                    if (webViewUnavailable) {
                        webViewUnavailable = false
                        webViewGeneration += 1
                    } else {
                        webView.reload()
                    }
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
internal fun WebPageErrorCard(
    message: String,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 420.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Video page unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            Button(onClick = onReload, modifier = Modifier.align(Alignment.End)) {
                Text("Reload")
            }
        }
    }
}

private suspend fun WebView.evaluateJavascriptAwait(script: String): String =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

internal val PLAYER_PROBE_SCRIPT: String =
    """
    (function() {
      const viewportWidth = Math.max(window.innerWidth, document.documentElement.clientWidth, 1);
      const viewportHeight = Math.max(window.innerHeight, document.documentElement.clientHeight, 1);
      const videos = Array.from(document.querySelectorAll('video'));
      if (videos.length === 0) return null;

      const ranked = videos.map(function(video) {
        const bounds = video.getBoundingClientRect();
        const visibleWidth = Math.max(0, Math.min(bounds.right, viewportWidth) - Math.max(bounds.left, 0));
        const visibleHeight = Math.max(0, Math.min(bounds.bottom, viewportHeight) - Math.max(bounds.top, 0));
        return { video: video, area: visibleWidth * visibleHeight };
      }).sort(function(left, right) { return right.area - left.area; });

      const video = ranked[0].video;
      if (!video || !Number.isFinite(video.currentTime)) return null;
      const player = video.closest(
        'ytm-player, .html5-video-player, #player-container-id, #player-container'
      ) || video.parentElement || video;
      let bounds = player.getBoundingClientRect();
      if (bounds.width < 40 || bounds.height < 40) bounds = video.getBoundingClientRect();

      let decodedFrameCount = 0;
      if (typeof video.getVideoPlaybackQuality === 'function') {
        const quality = video.getVideoPlaybackQuality();
        decodedFrameCount = quality && Number.isFinite(quality.totalVideoFrames)
          ? quality.totalVideoFrames
          : 0;
      } else if (Number.isFinite(video.webkitDecodedFrameCount)) {
        decodedFrameCount = video.webkitDecodedFrameCount;
      }

      return JSON.stringify({
        playbackSecond: video.currentTime,
        viewportWidth: viewportWidth,
        viewportHeight: viewportHeight,
        playerLeft: bounds.left,
        playerTop: bounds.top,
        playerRight: bounds.right,
        playerBottom: bounds.bottom,
        videoWidth: video.videoWidth || 0,
        videoHeight: video.videoHeight || 0,
        readyState: video.readyState,
        networkState: video.networkState,
        decodedFrameCount: decodedFrameCount
      });
    })();
    """.trimIndent()
