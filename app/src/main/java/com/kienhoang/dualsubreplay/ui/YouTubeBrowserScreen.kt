package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.json.JSONTokener

private const val BROWSER_LOG_TAG = "DualSubBrowser"
private const val PLAYBACK_POLL_INTERVAL_MS = 250L
private const val SIGN_IN_POLL_INTERVAL_MS = 500L
internal const val YOUTUBE_CAPTION_STYLE_ID = "dual-sub-hide-youtube-captions"
private val destroyedWebViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
private val trustedGoogleSignInHosts = setOf(
    "accounts.google.com",
    "accounts.googleusercontent.com",
    "accounts.youtube.com",
    "consent.google.com",
    "google.com",
    "myaccount.google.com",
)
private val authenticatedYouTubeCookieNames = setOf(
    "APISID",
    "HSID",
    "LOGIN_INFO",
    "SAPISID",
    "SID",
    "SSID",
    "__Secure-1PAPISID",
    "__Secure-1PSID",
    "__Secure-3PAPISID",
    "__Secure-3PSID",
)

internal data class BrowseVideoSelection(val videoId: String, val canonicalUrl: String)

internal enum class EmbeddedNavigationDecision {
    YOUTUBE_WEB,
    GOOGLE_SIGN_IN,
    OPEN_EXTERNAL,
    BLOCK,
}

internal fun browseVideoSelection(url: String): BrowseVideoSelection? {
    val videoId = YouTubeUrlParser.extractVideoId(url) ?: return null
    return BrowseVideoSelection(videoId, "https://www.youtube.com/watch?v=$videoId")
}

internal fun isYouTubeWebUrl(url: String): Boolean {
    return classifyMainFrameUrl(url) == EmbeddedNavigationDecision.YOUTUBE_WEB
}

internal fun classifyMainFrameUrl(url: String): EmbeddedNavigationDecision {
    val uri = runCatching { URI(url) }.getOrNull() ?: return EmbeddedNavigationDecision.BLOCK
    if (!uri.scheme.equals("https", ignoreCase = true)) return EmbeddedNavigationDecision.BLOCK
    if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
        return EmbeddedNavigationDecision.BLOCK
    }
    val host = uri.host?.lowercase()?.removeSuffix(".")
        ?: return EmbeddedNavigationDecision.BLOCK
    return when {
        host in trustedGoogleSignInHosts -> EmbeddedNavigationDecision.GOOGLE_SIGN_IN
        host == "youtube.com" || host.endsWith(".youtube.com") -> {
            EmbeddedNavigationDecision.YOUTUBE_WEB
        }
        else -> EmbeddedNavigationDecision.OPEN_EXTERNAL
    }
}

internal fun isSignOutNavigation(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
    val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
    if (host != "accounts.google.com" && host != "accounts.youtube.com") return false
    val path = uri.path?.trimEnd('/')?.lowercase() ?: return false
    return path == "/logout" || path == "/signoutoptions"
}

internal fun shouldOpenInsideApp(destination: EmbeddedNavigationDecision): Boolean =
    destination == EmbeddedNavigationDecision.YOUTUBE_WEB ||
        destination == EmbeddedNavigationDecision.GOOGLE_SIGN_IN

internal fun shouldOpenInsideApp(
    destination: EmbeddedNavigationDecision,
    signInEmbeddingActive: Boolean,
): Boolean = shouldOpenInsideApp(destination) ||
    (signInEmbeddingActive && destination == EmbeddedNavigationDecision.OPEN_EXTERNAL)

internal fun trustedEmbeddedUrlOrHome(url: String): String =
    url.takeIf { shouldOpenInsideApp(classifyMainFrameUrl(it)) } ?: YOUTUBE_HOME_URL

private fun openExternalHttpsUrl(context: android.content.Context, url: String) {
    if (classifyMainFrameUrl(url) != EmbeddedNavigationDecision.OPEN_EXTERNAL) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { error ->
        Log.w(BROWSER_LOG_TAG, "No activity could open external HTTPS navigation.", error)
    }
}

internal fun WebSettings.applyEmbeddedSecurityPolicy() {
    allowFileAccess = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    safeBrowsingEnabled = true
}

internal fun hasAuthenticatedYouTubeCookie(cookieHeader: String?): Boolean {
    if (cookieHeader.isNullOrBlank()) return false
    return cookieHeader.split(';').any { cookie ->
        cookie.substringBefore('=').trim() in authenticatedYouTubeCookieNames
    }
}

internal fun shouldAutoReturnAfterSignIn(
    startedWithSessionCookies: Boolean,
    sessionCookiesPresent: Boolean,
): Boolean = !startedWithSessionCookies && sessionCookiesPresent

private fun hasStoredYouTubeSessionCookie(): Boolean =
    sequenceOf("https://www.youtube.com", "https://m.youtube.com").any { url ->
        hasAuthenticatedYouTubeCookie(CookieManager.getInstance().getCookie(url))
    }

internal data class WebPlaybackSnapshot(
    val url: String,
    val currentSecond: Float?,
)

private data class WebFullscreenSession(
    val view: View,
    val callback: WebChromeClient.CustomViewCallback,
)

internal val WEB_PLAYBACK_SNAPSHOT_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
      const videos = Array.from(document.querySelectorAll('video'));
      const video = videos.find(function(item) { return !item.paused && !item.ended; })
        || videos.find(function(item) { return item.readyState > 0; })
        || videos[0];
      const second = video && Number.isFinite(video.currentTime) ? video.currentTime : null;
      return JSON.stringify({ url: window.location.href, currentSecond: second });
    })();
    """.trimIndent()

internal fun webReplayScript(second: Float): String {
    val safeSecond = second.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const videos = Array.from(document.querySelectorAll('video'));
          const video = videos.find(function(item) { return !item.paused && !item.ended; })
            || videos.find(function(item) { return item.readyState > 0; })
            || videos[0];
          if (!video) return false;
          video.currentTime = $safeSecond;
          const playRequest = video.play();
          if (playRequest && playRequest.catch) playRequest.catch(function() {});
          return true;
        })();
    """.trimIndent()
}

/**
 * Hides only YouTube's rendered player captions while our bilingual learning overlay is active.
 * The user's YouTube caption preference is left untouched and becomes visible again when the style
 * element is removed.
 */
internal fun webCaptionVisibilityScript(hidden: Boolean): String {
    val hiddenLiteral = if (hidden) "true" else "false"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const styleId = '$YOUTUBE_CAPTION_STYLE_ID';
          const existing = document.getElementById(styleId);
          if ($hiddenLiteral) {
            const style = existing || document.createElement('style');
            style.id = styleId;
            style.textContent = '.ytp-caption-window-container, .caption-window, .ytp-caption-segment { visibility: hidden !important; opacity: 0 !important; }';
            if (!existing) (document.head || document.documentElement).appendChild(style);
          } else if (existing) {
            existing.remove();
          }
          return true;
        })();
    """.trimIndent()
}

internal fun parseWebPlaybackSnapshot(rawValue: String?): WebPlaybackSnapshot? {
    if (rawValue.isNullOrBlank() || rawValue == "null") return null
    return runCatching {
        val decoded = JSONTokener(rawValue).nextValue() as? String ?: return@runCatching null
        val json = JSONObject(decoded)
        WebPlaybackSnapshot(
            url = json.getString("url"),
            currentSecond = if (json.isNull("currentSecond")) {
                null
            } else {
                json.getDouble("currentSecond").toFloat()
            },
        )
    }.getOrNull()
}

internal class YouTubeWebController {
    private var bindingToken: Any? = null
    private var replayAction: ((Float) -> Unit)? = null

    fun bind(replayFrom: (Float) -> Unit): Any {
        val token = Any()
        bindingToken = token
        replayAction = replayFrom
        return token
    }

    fun unbind(token: Any) {
        if (bindingToken !== token) return
        bindingToken = null
        replayAction = null
    }

    fun replayFrom(second: Float) {
        replayAction?.invoke(second.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f)
    }
}

@Composable
internal fun rememberYouTubeWebController(): YouTubeWebController = remember { YouTubeWebController() }

/**
 * Hosts the complete YouTube experience in one persistent WebView. The same native page video
 * supplies playback, controls, comments, recommendations, subtitle timing, and replay seeking.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SingleYouTubePage(
    initialUrl: String,
    navigationRequestId: Long,
    controller: YouTubeWebController,
    onPageChanged: (String) -> Unit,
    onPlaybackSecond: (videoId: String, second: Float) -> Unit,
    suppressPageCaptions: Boolean = false,
    fullscreenOverlay: (@Composable BoxScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnPlaybackSecond by rememberUpdatedState(onPlaybackSecond)
    val currentSuppressPageCaptions by rememberUpdatedState(suppressPageCaptions)
    var canGoBack by remember { mutableStateOf(false) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var rendererRecoveryUsed by remember { mutableStateOf(false) }
    var webViewUnavailable by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fullscreenSession by remember { mutableStateOf<WebFullscreenSession?>(null) }
    var googleSignInInProgress by remember { mutableStateOf(false) }
    var signInStartedWithCookies by remember { mutableStateOf(false) }
    var signInEmbedUntilYouTubeLands by remember { mutableStateOf(false) }
    var signInReturnUrl by remember { mutableStateOf<String?>(null) }
    var lastKnownUrl by remember { mutableStateOf(trustedEmbeddedUrlOrHome(initialUrl)) }
    var handledNavigationRequestId by remember { mutableLongStateOf(navigationRequestId) }

    fun reportNavigation(view: WebView, url: String?) {
        val currentUrl = url?.takeIf(String::isNotBlank) ?: return
        canGoBack = view.canGoBack()
        if (classifyMainFrameUrl(currentUrl) != EmbeddedNavigationDecision.YOUTUBE_WEB) return
        lastKnownUrl = currentUrl
        currentOnPageChanged(currentUrl)
        if (googleSignInInProgress || signInEmbedUntilYouTubeLands) {
            googleSignInInProgress = false
            signInEmbedUntilYouTubeLands = false
            signInStartedWithCookies = false
            signInReturnUrl = null
            CookieManager.getInstance().flush()
        }
    }

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
            settings.applyEmbeddedSecurityPolicy()
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(
                    view: View,
                    callback: CustomViewCallback,
                ) {
                    fullscreenSession?.callback?.onCustomViewHidden()
                    (view.parent as? ViewGroup)?.removeView(view)
                    fullscreenSession = WebFullscreenSession(view, callback)
                }

                override fun onHideCustomView() {
                    val session = fullscreenSession ?: return
                    fullscreenSession = null
                    (session.view.parent as? ViewGroup)?.removeView(session.view)
                    runCatching { session.callback.onCustomViewHidden() }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                private fun handleMainFrameUrl(view: WebView, url: String): Boolean {
                    val destination = classifyMainFrameUrl(url)
                    if (BuildConfig.DEBUG) {
                        val sanitized = runCatching { URI(url) }.getOrNull()
                            ?.let { "${it.scheme}://${it.host}${it.path}" }
                            ?: "unparsed"
                        Log.d(BROWSER_LOG_TAG, "Main frame $destination: $sanitized")
                    }
                    val signInEmbeddingActive = googleSignInInProgress || signInEmbedUntilYouTubeLands
                    if (shouldOpenInsideApp(destination, signInEmbeddingActive)) {
                        if (destination == EmbeddedNavigationDecision.YOUTUBE_WEB) {
                            reportNavigation(view, url)
                        } else if (!googleSignInInProgress && !isSignOutNavigation(url)) {
                            signInReturnUrl = lastKnownUrl
                            signInStartedWithCookies = hasStoredYouTubeSessionCookie()
                            googleSignInInProgress = true
                        }
                        return false
                    }
                    return when (destination) {
                        EmbeddedNavigationDecision.OPEN_EXTERNAL -> {
                            view.stopLoading()
                            openExternalHttpsUrl(context, url)
                            true
                        }

                        EmbeddedNavigationDecision.BLOCK -> {
                            view.stopLoading()
                            true
                        }
                        EmbeddedNavigationDecision.YOUTUBE_WEB,
                        EmbeddedNavigationDecision.GOOGLE_SIGN_IN -> false
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    return handleMainFrameUrl(view, request.url.toString())
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    handleMainFrameUrl(view, url)

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    reportNavigation(view, url)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageError = null
                    if (url != null && handleMainFrameUrl(view, url)) return
                    reportNavigation(view, url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && handleMainFrameUrl(view, url)) return
                    rendererRecoveryUsed = false
                    webViewUnavailable = false
                    reportNavigation(view, url)
                    if (url?.let(::isYouTubeWebUrl) == true) {
                        CookieManager.getInstance().flush()
                        view.evaluateJavascript(
                            webCaptionVisibilityScript(currentSuppressPageCaptions),
                            null,
                        )
                    }
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
            loadUrl(trustedEmbeddedUrlOrHome(lastKnownUrl))
        }
    }

    LaunchedEffect(navigationRequestId, initialUrl, webView) {
        if (navigationRequestId == handledNavigationRequestId) return@LaunchedEffect
        handledNavigationRequestId = navigationRequestId
        if (isYouTubeWebUrl(initialUrl)) webView.loadUrl(initialUrl)
    }

    LaunchedEffect(webView, suppressPageCaptions) {
        if (webView.url.orEmpty().let(::isYouTubeWebUrl)) {
            webView.evaluateJavascript(webCaptionVisibilityScript(suppressPageCaptions), null)
        }
    }

    LaunchedEffect(webView, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        while (isActive) {
            if (!webView.url.orEmpty().let(::isYouTubeWebUrl)) {
                delay(PLAYBACK_POLL_INTERVAL_MS)
                continue
            }
            webView.evaluateJavascript(WEB_PLAYBACK_SNAPSHOT_SCRIPT) { rawValue ->
                val snapshot = parseWebPlaybackSnapshot(rawValue) ?: return@evaluateJavascript
                reportNavigation(webView, snapshot.url)
                val selection = browseVideoSelection(snapshot.url) ?: return@evaluateJavascript
                val second = snapshot.currentSecond ?: return@evaluateJavascript
                currentOnPlaybackSecond(selection.videoId, second)
            }
            delay(PLAYBACK_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(webView, lifecycleStarted, googleSignInInProgress) {
        if (!lifecycleStarted || !googleSignInInProgress) return@LaunchedEffect
        val cookieManager = CookieManager.getInstance()
        while (isActive && googleSignInInProgress) {
            val signedIn = hasStoredYouTubeSessionCookie()
            if (!signedIn) {
                signInStartedWithCookies = false
            } else if (shouldAutoReturnAfterSignIn(signInStartedWithCookies, signedIn)) {
                val returnUrl = signInReturnUrl ?: lastKnownUrl
                googleSignInInProgress = false
                signInStartedWithCookies = false
                signInReturnUrl = null
                signInEmbedUntilYouTubeLands = true
                cookieManager.flush()
                webView.loadUrl(returnUrl)
                return@LaunchedEffect
            }
            delay(SIGN_IN_POLL_INTERVAL_MS)
        }
    }

    DisposableEffect(controller, webView) {
        val token = controller.bind { second ->
            if (webView.url.orEmpty().let(::isYouTubeWebUrl)) {
                webView.evaluateJavascript(webReplayScript(second), null)
            }
        }
        onDispose { controller.unbind(token) }
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
            webView.webChromeClient?.onHideCustomView()
            webView.destroySafely()
        }
    }

    BackHandler {
        if (canGoBack) webView.goBack() else (context as? Activity)?.finish()
    }

    Box(modifier) {
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

    fullscreenSession?.let { session ->
        Dialog(
            onDismissRequest = { webView.webChromeClient?.onHideCustomView() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = {
                        (session.view.parent as? ViewGroup)?.removeView(session.view)
                        session.view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                fullscreenOverlay?.invoke(this)
            }
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
