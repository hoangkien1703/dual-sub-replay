package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.util.Log
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
import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.delay

private const val PLAYER_LOG_TAG = "DualSubPlayer"

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
              const manager = window.__dualSubReplayManager;
              const video = manager && manager.currentVideo
                ? manager.currentVideo()
                : document.querySelector('video');
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
              const manager = window.__dualSubReplayManager;
              const video = manager && manager.currentVideo
                ? manager.currentVideo()
                : document.querySelector('video');
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
    displayMode: VideoDisplayMode,
    onUrlChanged: (String) -> Unit,
    onPlayerTelemetry: (PlayerTelemetry) -> Unit,
    onPlayerBottomFraction: (Float) -> Unit,
) {
    val context = LocalContext.current
    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentOnPlayerTelemetry by rememberUpdatedState(onPlayerTelemetry)
    val currentOnPlayerBottomFraction by rememberUpdatedState(onPlayerBottomFraction)
    var canGoBack by remember { mutableStateOf(false) }
    var lastTelemetryLogMs by remember { mutableStateOf(0L) }

    val webView = remember {
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

    LaunchedEffect(webView, watchPageActive, displayMode) {
        while (true) {
            val mode = if (displayMode == VideoDisplayMode.FOCUS) "'focus'" else "'learning'"
            val watchActive = watchPageActive.toString()
            webView.evaluateJavascript(playerLayoutScript(watchActive, mode)) { rawValue ->
                val telemetry = PlayerTelemetryParser.parse(rawValue) ?: return@evaluateJavascript
                currentOnPlayerTelemetry(telemetry)
                if (displayMode == VideoDisplayMode.LEARNING) {
                    currentOnPlayerBottomFraction(telemetry.playerBottomFraction)
                }
                if (BuildConfig.DEBUG && SystemClock.elapsedRealtime() - lastTelemetryLogMs >= 2_000L) {
                    lastTelemetryLogMs = SystemClock.elapsedRealtime()
                    Log.d(
                        PLAYER_LOG_TAG,
                        "mode=$displayMode viewport=${telemetry.viewportWidth}x${telemetry.viewportHeight} " +
                            "player=[${telemetry.playerLeft},${telemetry.playerTop}," +
                            "${telemetry.playerRight},${telemetry.playerBottom}] " +
                            "video=${telemetry.videoWidth}x${telemetry.videoHeight}",
                    )
                }
            }
            delay(400)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.evaluateJavascript(
                "window.__dualSubReplayManager && window.__dualSubReplayManager.restore();",
                null,
            )
            controller.detach(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = displayMode != VideoDisplayMode.FOCUS) {
        if (canGoBack) controller.goBack() else (context as? Activity)?.finish()
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun playerLayoutScript(watchActive: String, mode: String): String =
    """
    (function() {
      if (!window.__dualSubReplayManager) {
        window.__dualSubReplayManager = (function() {
          const originals = new Map();
          let activeRoot = null;
          let activeNodes = [];
          let resizeObserver = null;
          let scheduled = false;
          let requestedMode = 'learning';
          let requestedWatchActive = false;

          function save(node) {
            if (node && !originals.has(node)) originals.set(node, node.getAttribute('style'));
          }

          function setStyle(node, name, value) {
            if (!node) return;
            save(node);
            if (node.style.getPropertyValue(name) !== value ||
                node.style.getPropertyPriority(name) !== 'important') {
              node.style.setProperty(name, value, 'important');
            }
          }

          function restoreNode(node) {
            if (!node || !originals.has(node)) return;
            const original = originals.get(node);
            if (original === null) node.removeAttribute('style');
            else node.setAttribute('style', original);
            originals.delete(node);
          }

          function restoreActive() {
            if (resizeObserver) resizeObserver.disconnect();
            resizeObserver = null;
            activeNodes.forEach(restoreNode);
            activeNodes = [];
            activeRoot = null;
          }

          function currentVideo() {
            return Array.from(document.querySelectorAll('video'))
              .map(function(video) {
                const bounds = video.getBoundingClientRect();
                const visible = bounds.width >= 40 && bounds.height >= 40 &&
                  bounds.bottom > 0 && bounds.top < window.innerHeight;
                return { video: video, area: visible ? bounds.width * bounds.height : 0 };
              })
              .sort(function(left, right) { return right.area - left.area; })
              .map(function(item) { return item.video; })[0] || null;
          }

          function findPlayerNodes(video) {
            const viewportWidth = Math.max(window.innerWidth, document.documentElement.clientWidth, 1);
            const videoBounds = video.getBoundingClientRect();
            const nodes = [];
            let node = video.parentElement;

            for (let depth = 0; node && node !== document.body && depth < 14; depth++) {
              const bounds = node.getBoundingClientRect();
              const sharesTop = Math.abs(bounds.top - videoBounds.top) <= 140;
              const sharesBottom = Math.abs(bounds.bottom - videoBounds.bottom) <= 180;
              const overlapsVideo = bounds.right > videoBounds.left && bounds.left < videoBounds.right;
              const knownPlayer = node.matches && node.matches(
                'ytm-player, .html5-video-player, #player-container-id, #player-container, #player'
              );
              const playerSized = bounds.height >= 40 &&
                bounds.height <= Math.max(window.innerHeight * 0.9, videoBounds.height * 2.2);

              if ((sharesTop && sharesBottom && overlapsVideo && playerSized) || knownPlayer) {
                nodes.push(node);
              } else if (nodes.length > 0 && bounds.height > window.innerHeight * 0.95) {
                break;
              }
              node = node.parentElement;
            }

            const knownRoot = video.closest(
              'ytm-player, #player-container-id, #player-container, .html5-video-player'
            );
            if (knownRoot && !nodes.includes(knownRoot)) nodes.push(knownRoot);
            if (nodes.length === 0 && video.parentElement) nodes.push(video.parentElement);

            return nodes.sort(function(left, right) {
              const leftWidth = left.getBoundingClientRect().width;
              const rightWidth = right.getBoundingClientRect().width;
              const leftFullWidth = leftWidth >= viewportWidth * 0.8 ? 1 : 0;
              const rightFullWidth = rightWidth >= viewportWidth * 0.8 ? 1 : 0;
              return leftFullWidth - rightFullWidth || leftWidth - rightWidth;
            });
          }

          function apply() {
            scheduled = false;
            if (!requestedWatchActive) {
              restoreActive();
              return null;
            }

            const video = currentVideo();
            if (!video || !Number.isFinite(video.currentTime)) return null;

            const nodes = findPlayerNodes(video);
            const root = nodes[nodes.length - 1] || video.parentElement || video;
            if (root !== activeRoot || !activeNodes.includes(video)) {
              restoreActive();
              activeRoot = root;
              activeNodes = Array.from(new Set(nodes.concat([video])));
              resizeObserver = new ResizeObserver(scheduleApply);
              resizeObserver.observe(video);
              if (root !== video) resizeObserver.observe(root);
            }

            const focus = requestedMode === 'focus';
            const viewportWidth = Math.max(window.innerWidth, document.documentElement.clientWidth, 1);
            const viewportHeight = Math.max(window.innerHeight, document.documentElement.clientHeight, 1);
            const desiredHeight = Math.round(viewportWidth * 9 / 16);

            setStyle(root, 'box-sizing', 'border-box');
            setStyle(root, 'background', '#000');
            setStyle(root, 'overflow', 'hidden');

            if (focus) {
              setStyle(root, 'position', 'fixed');
              setStyle(root, 'inset', '0');
              setStyle(root, 'left', '0');
              setStyle(root, 'top', '0');
              setStyle(root, 'width', '100vw');
              setStyle(root, 'min-width', '100vw');
              setStyle(root, 'max-width', '100vw');
              setStyle(root, 'height', '100vh');
              setStyle(root, 'min-height', '100vh');
              setStyle(root, 'max-height', '100vh');
              setStyle(root, 'margin', '0');
              setStyle(root, 'transform', 'none');
              setStyle(root, 'z-index', '2147483000');
            } else {
              setStyle(root, 'position', 'relative');
              setStyle(root, 'inset', 'auto');
              setStyle(root, 'left', 'auto');
              setStyle(root, 'top', 'auto');
              setStyle(root, 'width', '100vw');
              setStyle(root, 'min-width', '100vw');
              setStyle(root, 'max-width', '100vw');
              setStyle(root, 'height', desiredHeight + 'px');
              setStyle(root, 'min-height', desiredHeight + 'px');
              setStyle(root, 'max-height', desiredHeight + 'px');
              setStyle(root, 'margin-left', 'calc(50% - 50vw)');
              setStyle(root, 'margin-right', 'calc(50% - 50vw)');
              setStyle(root, 'transform', 'none');
              setStyle(root, 'z-index', 'auto');
            }

            nodes.forEach(function(node) {
              if (node === root) return;
              setStyle(node, 'box-sizing', 'border-box');
              setStyle(node, 'width', '100%');
              setStyle(node, 'min-width', '100%');
              setStyle(node, 'max-width', '100%');
              setStyle(node, 'height', '100%');
              setStyle(node, 'min-height', '100%');
              setStyle(node, 'max-height', '100%');
              setStyle(node, 'margin', '0');
              setStyle(node, 'padding', '0');
              setStyle(node, 'transform', 'none');
              setStyle(node, 'overflow', 'hidden');
            });

            const videoParent = video.parentElement;
            if (videoParent) setStyle(videoParent, 'position', 'relative');
            setStyle(video, 'box-sizing', 'border-box');
            setStyle(video, 'position', 'absolute');
            setStyle(video, 'inset', '0');
            setStyle(video, 'width', '100%');
            setStyle(video, 'min-width', '100%');
            setStyle(video, 'max-width', '100%');
            setStyle(video, 'height', '100%');
            setStyle(video, 'min-height', '100%');
            setStyle(video, 'max-height', '100%');
            setStyle(video, 'margin', 'auto');
            setStyle(video, 'transform', 'none');
            setStyle(video, 'object-fit', 'contain');

            const bounds = root.getBoundingClientRect();
            return JSON.stringify({
              playbackSecond: video.currentTime,
              viewportWidth: viewportWidth,
              viewportHeight: viewportHeight,
              playerLeft: bounds.left,
              playerTop: bounds.top,
              playerRight: bounds.right,
              playerBottom: bounds.bottom,
              videoWidth: video.videoWidth || 0,
              videoHeight: video.videoHeight || 0
            });
          }

          function scheduleApply() {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(apply);
          }

          const observer = new MutationObserver(scheduleApply);
          if (document.documentElement) {
            observer.observe(document.documentElement, { childList: true, subtree: true });
          }
          window.addEventListener('resize', scheduleApply, { passive: true });

          return {
            update: function(watchActive, mode) {
              requestedWatchActive = watchActive;
              requestedMode = mode;
              return apply();
            },
            restore: restoreActive,
            currentVideo: currentVideo
          };
        })();
      }

      return window.__dualSubReplayManager.update($watchActive, $mode);
    })();
    """.trimIndent()
