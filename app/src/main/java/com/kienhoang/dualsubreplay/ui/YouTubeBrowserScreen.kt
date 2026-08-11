package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import java.util.Collections
import java.util.WeakHashMap

private const val BROWSER_LOG_TAG = "DualSubBrowser"
private val destroyedWebViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())

internal data class BrowseVideoSelection(val videoId: String, val canonicalUrl: String)

internal fun browseVideoSelection(url: String): BrowseVideoSelection? {
    val videoId = YouTubeUrlParser.extractVideoId(url) ?: return null
    return BrowseVideoSelection(videoId, "https://www.youtube.com/watch?v=$videoId")
}

/**
 * YouTube's mobile site is used only for discovery. Watch-page navigation is handed to the
 * dedicated learning player before the mobile site creates its own video element.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeBrowsePage(
    initialUrl: String,
    navigationRequestId: Long,
    onBrowseUrlChanged: (String) -> Unit,
    onVideoSelected: (videoId: String, canonicalUrl: String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBrowseUrlChanged by rememberUpdatedState(onBrowseUrlChanged)
    val currentOnVideoSelected by rememberUpdatedState(onVideoSelected)
    var canGoBack by remember { mutableStateOf(false) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var rendererRecoveryUsed by remember { mutableStateOf(false) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var lastBrowseUrl by remember { mutableStateOf(initialUrl) }
    var handledNavigationRequestId by remember { mutableLongStateOf(navigationRequestId) }

    val webView = remember(webViewGeneration) {
        if (BuildConfig.DEBUG) {
            val packageInfo = WebView.getCurrentWebViewPackage()
            Log.d(
                BROWSER_LOG_TAG,
                "WebView=${packageInfo?.packageName ?: "unknown"}/${packageInfo?.versionName ?: "unknown"}",
            )
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            var lastSelectedVideoId: String? = null
            webViewClient = object : WebViewClient() {
                private fun handleVideoNavigation(view: WebView, url: String?): Boolean {
                    val targetUrl = url?.takeIf(String::isNotBlank) ?: return false
                    val selection = browseVideoSelection(targetUrl) ?: return false
                    view.stopLoading()
                    if (selection.videoId != lastSelectedVideoId) {
                        currentOnVideoSelected(selection.videoId, selection.canonicalUrl)
                    }
                    lastSelectedVideoId = selection.videoId
                    return true
                }

                private fun reportBrowseNavigation(view: WebView, url: String?) {
                    val currentUrl = url?.takeIf(String::isNotBlank) ?: return
                    if (YouTubeUrlParser.extractVideoId(currentUrl) != null) return
                    lastSelectedVideoId = null
                    lastBrowseUrl = currentUrl
                    canGoBack = view.canGoBack()
                    currentOnBrowseUrlChanged(currentUrl)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    if (request.url.scheme !in setOf("http", "https")) return true
                    return handleVideoNavigation(view, request.url.toString())
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
                    if (scheme !in setOf("http", "https")) return true
                    return handleVideoNavigation(view, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!handleVideoNavigation(view, url)) reportBrowseNavigation(view, url)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageError = null
                    if (handleVideoNavigation(view, url)) return
                    reportBrowseNavigation(view, url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (handleVideoNavigation(view, url)) return
                    rendererRecoveryUsed = false
                    webViewUnavailable = false
                    reportBrowseNavigation(view, url)
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

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    val message = if (detail.didCrash()) {
                        "The Android WebView renderer crashed."
                    } else {
                        "Android stopped the WebView renderer to reclaim memory."
                    }
                    Log.e(BROWSER_LOG_TAG, message)
                    webViewUnavailable = true
                    view.post {
                        view.destroySafely()
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
            loadUrl(lastBrowseUrl)
        }
    }

    LaunchedEffect(navigationRequestId, initialUrl) {
        if (navigationRequestId == handledNavigationRequestId) return@LaunchedEffect
        handledNavigationRequestId = navigationRequestId
        val videoId = YouTubeUrlParser.extractVideoId(initialUrl)
        if (videoId != null) {
            webView.stopLoading()
            currentOnVideoSelected(videoId, "https://www.youtube.com/watch?v=$videoId")
        } else if (initialUrl.startsWith("https://") || initialUrl.startsWith("http://")) {
            webView.loadUrl(initialUrl)
        }
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

    DisposableEffect(webView) {
        onDispose {
            webView.destroySafely()
        }
    }

    BackHandler {
        if (canGoBack) webView.goBack() else (context as? Activity)?.finish()
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

internal fun WebView.destroySafely() {
    val firstDestroy = synchronized(destroyedWebViews) { destroyedWebViews.add(this) }
    if (!firstDestroy) return
    runCatching { stopLoading() }
    (parent as? ViewGroup)?.removeView(this)
    runCatching { removeAllViews() }
    runCatching { destroy() }
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
            Text("YouTube unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            Button(onClick = onReload, modifier = Modifier.align(Alignment.End)) {
                Text("Reload")
            }
        }
    }
}
