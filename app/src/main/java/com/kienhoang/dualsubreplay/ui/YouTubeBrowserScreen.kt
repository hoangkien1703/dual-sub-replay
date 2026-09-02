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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.json.JSONTokener

private const val BROWSER_LOG_TAG = "DualSubBrowser"
// Keep native karaoke tracking close to YouTube's own transcript cadence. 250 ms
// polling made spoken-word changes visibly trail fast speech by up to a quarter
// second; 100 ms keeps the highlight responsive without polling every frame.
private const val PLAYBACK_POLL_INTERVAL_MS = 100L
private const val SIGN_IN_POLL_INTERVAL_MS = 500L
internal const val YOUTUBE_CAPTION_STYLE_ID = "dual-sub-hide-youtube-captions"
internal const val LIVE_CAPTION_CAPTURE_STATE_KEY = "__dualSubLiveCaptionV1"
internal const val MAX_LIVE_CAPTION_TEXT_LENGTH = 4_096
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
    val controlsVisible: Boolean = false,
    val liveCaption: LiveCaptionSample? = null,
)

internal val youtubePlayerControlsVisible = MutableStateFlow(false)
internal val youtubeFullscreenActive = MutableStateFlow(false)

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
      const liveState = window['$LIVE_CAPTION_CAPTURE_STATE_KEY'];
      if (liveState && liveState.enabled) {
        try {
          liveState.ensureCaptions();
          liveState.recordCaption();
        } catch (_) {}
      }
      const player = document.querySelector('.html5-video-player');
      const chromeBottom = document.querySelector('.ytp-chrome-bottom');
      const progressBar = document.querySelector('.ytp-progress-bar-container, .ytp-progress-bar');
      const isVisible = function(element) {
        if (!element) return false;
        const style = window.getComputedStyle(element);
        const opacity = Number.parseFloat(style.opacity || '1');
        const rect = element.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' &&
          opacity > 0.05 && rect.width > 0 && rect.height > 0;
      };
      const controlsVisible = !!player && (
        player.classList.contains('ytp-paused') ||
        player.classList.contains('ytp-scrubbing') ||
        !player.classList.contains('ytp-autohide') ||
        isVisible(chromeBottom) ||
        isVisible(progressBar)
      );
      return JSON.stringify({
        url: window.location.href,
        currentSecond: second,
        controlsVisible: controlsVisible,
        liveCaption: liveState ? {
          text: liveState.text || '',
          revision: liveState.revision,
          mediaSecond: liveState.captionMediaSecond,
          present: !!liveState.text
        } : null
      });
    })();
    """.trimIndent()

/**
 * Installs or removes the live-caption observer without adding a JavaScript bridge.
 * The observer records only rendered YouTube caption text and the latest presented
 * media time; native code retrieves that state through [WEB_PLAYBACK_SNAPSHOT_SCRIPT].
 */
internal fun webLiveCaptionConfigurationScript(enabled: Boolean): String {
    val enabledLiteral = if (enabled) "true" else "false"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const key = '$LIVE_CAPTION_CAPTURE_STATE_KEY';
          const requested = $enabledLiteral;
          const existing = window[key];
          if (!requested) {
            if (existing) {
              existing.enabled = false;
              if (existing.observer) existing.observer.disconnect();
              if (typeof existing.restoreCaptions === 'function') existing.restoreCaptions();
              delete window[key];
            }
            return true;
          }
          if (existing) {
            existing.enabled = true;
            existing.ensureCaptions();
            existing.recordCaption();
            return true;
          }

          const state = {
            enabled: true,
            text: '',
            revision: 0,
            latestMediaSecond: null,
            captionMediaSecond: null,
            captionWasEnabled: null,
            enabledByApp: false,
            observer: null
          };
          state.trustedOrigin = function() {
            const currentHost = window.location.hostname.toLowerCase().replace(/\.$/, '');
            return window.location.protocol === 'https:' &&
              (currentHost === 'youtube.com' || currentHost.endsWith('.youtube.com'));
          };
          state.activeVideo = function() {
            const videos = Array.from(document.querySelectorAll('video'));
            return videos.find(function(item) { return !item.paused && !item.ended; })
              || videos.find(function(item) { return item.readyState > 0; })
              || videos[0] || null;
          };
          state.captionButton = function() {
            const direct = document.querySelector(
              '.ytp-subtitles-button, .ytmClosedCaptioningButtonButton, [class*="ClosedCaptioningButton"]'
            );
            if (direct) return direct;
            return Array.from(document.querySelectorAll('button[aria-label], [role="button"][aria-label]'))
              .find(function(item) {
                return /caption|subtitle/i.test(item.getAttribute('aria-label') || '');
              }) || null;
          };
          state.player = function() {
            const player = document.getElementById('movie_player');
            return player && typeof player.getOption === 'function' &&
              typeof player.setOption === 'function' ? player : null;
          };
          state.activeCaptionTrack = function(player) {
            try {
              const track = player && player.getOption('captions', 'track');
              return track && typeof track === 'object' && Object.keys(track).length ? track : null;
            } catch (_) {
              return null;
            }
          };
          state.availableCaptionTrack = function(player) {
            try {
              const tracklist = player && player.getOption('captions', 'tracklist');
              if (Array.isArray(tracklist) && tracklist.length) return tracklist[0];
            } catch (_) {}
            const response = window.ytInitialPlayerResponse;
            const renderer = response && response.captions &&
              response.captions.playerCaptionsTracklistRenderer;
            const tracks = renderer && renderer.captionTracks;
            return Array.isArray(tracks) && tracks.length ? tracks[0] : null;
          };
          state.ensureCaptions = function() {
            if (!state.enabled || !state.trustedOrigin()) return;
            const button = state.captionButton();
            const player = state.player();
            const pressed = !!button && button.getAttribute('aria-pressed') === 'true';
            const activeTrack = state.activeCaptionTrack(player);
            if (state.captionWasEnabled === null) {
              state.captionWasEnabled = pressed || !!activeTrack;
            }
            if (pressed || activeTrack) return;
            if (button) {
              try {
                button.click();
                state.enabledByApp = true;
              } catch (_) {}
            }
            const track = state.availableCaptionTrack(player);
            if (player && track) {
              try {
                if (typeof player.loadModule === 'function') player.loadModule('captions');
                player.setOption('captions', 'track', track);
                state.enabledByApp = true;
              } catch (_) {}
            }
          };
          state.restoreCaptions = function() {
            if (!state.trustedOrigin() || !state.enabledByApp || state.captionWasEnabled !== false) return;
            const button = state.captionButton();
            if (button && button.getAttribute('aria-pressed') === 'true') {
              try { button.click(); } catch (_) {}
            }
            const player = state.player();
            if (player && state.activeCaptionTrack(player)) {
              try { player.setOption('captions', 'track', {}); } catch (_) {}
            }
            state.enabledByApp = false;
          };
          state.visibleCaptionText = function() {
            if (!state.trustedOrigin()) return '';
            const segments = Array.from(document.querySelectorAll('.ytp-caption-segment'));
            if (segments.length) {
              return segments.map(function(item) { return item.textContent || ''; })
                .join(' ').replace(/\s+/g, ' ').trim();
            }
            const roots = Array.from(document.querySelectorAll('.ytp-caption-window-container, .caption-window'));
            for (const root of roots) {
              const text = (root.textContent || '').replace(/\s+/g, ' ').trim();
              if (text) return text;
            }
            return '';
          };
          state.recordCaption = function() {
            if (!state.enabled || !state.trustedOrigin()) return;
            const text = state.visibleCaptionText();
            if (text === state.text) return;
            const video = state.activeVideo();
            const mediaSecond = Number.isFinite(state.latestMediaSecond)
              ? state.latestMediaSecond
              : (video && Number.isFinite(video.currentTime) ? video.currentTime : null);
            state.text = text;
            state.captionMediaSecond = mediaSecond;
            state.revision += 1;
          };
          state.frameLoop = function() {
            if (!state.enabled || !state.trustedOrigin()) return;
            const video = state.activeVideo();
            if (!video) {
              setTimeout(state.frameLoop, 120);
              return;
            }
            const onFrame = function(_, metadata) {
              if (!state.enabled) return;
              state.latestMediaSecond = metadata && Number.isFinite(metadata.mediaTime)
                ? metadata.mediaTime
                : (Number.isFinite(video.currentTime) ? video.currentTime : null);
              state.frameLoop();
            };
            if (typeof video.requestVideoFrameCallback === 'function') {
              video.requestVideoFrameCallback(onFrame);
            } else {
              setTimeout(function() { onFrame(0, { mediaTime: video.currentTime }); }, 40);
            }
          };

          state.observer = new MutationObserver(function() { state.recordCaption(); });
          state.observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            characterData: true
          });
          window[key] = state;
          state.ensureCaptions();
          state.recordCaption();
          state.frameLoop();
          return true;
        })();
    """.trimIndent()
}

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

/**
 * In-player playback settings overlay and touch interceptor.
 * Resolves issue #39 where YouTube's mobile web sticky topbar blocks touch input and
 * YouTube's fullscreen script drops the settings bottom sheet.
 * Fully compliant with YouTube Trusted Types (pure DOM element construction).
 */
internal val WEB_PLAYBACK_SETTINGS_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;

      const STYLE_ID = 'dualsub-settings-overlay-style';
      const OVERLAY_ID = 'dualsub-settings-overlay';

      let style = document.getElementById(STYLE_ID);
      if (!style) {
        style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = `
          #dualsub-settings-overlay {
            position: fixed;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            z-index: 2147483647;
            display: flex;
            justify-content: flex-end;
            align-items: flex-start;
            box-sizing: border-box;
          }
          #dualsub-settings-scrim {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.45);
          }
          #dualsub-settings-panel {
            position: relative;
            background: rgba(28, 28, 28, 0.96);
            backdrop-filter: blur(20px);
            -webkit-backdrop-filter: blur(20px);
            color: #f1f1f1;
            border-radius: 12px;
            margin: 48px 24px 16px 16px;
            min-width: 260px;
            max-width: 320px;
            max-height: calc(100vh - 64px);
            overflow-y: auto;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.7);
            font-family: Roboto, -apple-system, sans-serif;
            font-size: 14px;
            border: 1px solid rgba(255, 255, 255, 0.12);
            animation: dualsub-pop 0.15s ease-out;
            z-index: 1;
          }
          @keyframes dualsub-pop {
            from { opacity: 0; transform: scale(0.96) translateY(-6px); }
            to { opacity: 1; transform: scale(1) translateY(0); }
          }
          .dualsub-settings-header {
            display: flex;
            align-items: center;
            padding: 12px 16px;
            font-weight: 600;
            font-size: 15px;
            border-bottom: 1px solid rgba(255, 255, 255, 0.1);
          }
          .dualsub-settings-back {
            background: none;
            border: none;
            color: #fff;
            font-size: 22px;
            line-height: 1;
            margin-right: 12px;
            cursor: pointer;
            padding: 0 4px;
            display: flex;
            align-items: center;
          }
          .dualsub-settings-list {
            list-style: none;
            margin: 0;
            padding: 6px 0;
          }
          .dualsub-settings-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 12px 16px;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
            transition: background 0.1s;
          }
          .dualsub-settings-item:active {
            background: rgba(255, 255, 255, 0.15);
          }
          .dualsub-settings-item-label {
            display: flex;
            align-items: center;
            gap: 12px;
          }
          .dualsub-settings-item-value {
            color: #aaa;
            font-size: 13px;
            display: flex;
            align-items: center;
            gap: 4px;
          }
          .dualsub-check {
            color: #3ea6ff;
            font-weight: bold;
            width: 16px;
            display: inline-block;
          }
        `;
        (document.head || document.documentElement).appendChild(style);
      }

      if (window.__dualsubSettingsInstalled) return true;
      window.__dualsubSettingsInstalled = true;

      let activeSubmenu = null;
      let lastOpenTime = 0;

      function getPlayer() {
        return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      }

      function closeOverlay() {
        const el = document.getElementById(OVERLAY_ID);
        if (el) el.remove();
        activeSubmenu = null;
      }

      function renderOverlay() {
        const mp = getPlayer();
        if (!mp) return;
        lastOpenTime = Date.now();

        let container = document.getElementById(OVERLAY_ID);
        if (!container) {
          container = document.createElement('div');
          container.id = OVERLAY_ID;
          const parent = document.getElementById('player-container-id') ||
                         document.getElementById('player') ||
                         document.body;
          parent.appendChild(container);
        }
        while (container.firstChild) container.removeChild(container.firstChild);

        const scrim = document.createElement('div');
        scrim.id = 'dualsub-settings-scrim';
        scrim.onclick = function(e) {
          e.stopPropagation();
          if (Date.now() - lastOpenTime < 350) return;
          closeOverlay();
        };
        container.appendChild(scrim);

        const panel = document.createElement('div');
        panel.id = 'dualsub-settings-panel';

        if (activeSubmenu === 'quality') {
          renderQualitySubmenu(panel, mp);
        } else if (activeSubmenu === 'speed') {
          renderSpeedSubmenu(panel, mp);
        } else {
          renderMainMenu(panel, mp);
        }

        container.appendChild(panel);
      }

      function createHeader(title, onBack) {
        const header = document.createElement('div');
        header.className = 'dualsub-settings-header';
        if (onBack) {
          const back = document.createElement('button');
          back.className = 'dualsub-settings-back';
          back.textContent = '‹';
          back.onclick = function(e) {
            e.stopPropagation();
            onBack();
          };
          header.appendChild(back);
        }
        const titleSpan = document.createElement('span');
        titleSpan.textContent = title;
        header.appendChild(titleSpan);
        return header;
      }

      function createSvgIcon(pathD) {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '20');
        svg.setAttribute('height', '20');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'currentColor');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', pathD);
        svg.appendChild(path);
        return svg;
      }

      function renderMainMenu(panel, mp) {
        panel.appendChild(createHeader('Playback settings'));
        const list = document.createElement('ul');
        list.className = 'dualsub-settings-list';

        const currentQuality = (mp.getPlaybackQualityLabel && mp.getPlaybackQualityLabel()) ||
                              (mp.getPlaybackQuality && mp.getPlaybackQuality()) || 'Auto';
        const qualityItem = createMenuItem(
          createSvgIcon('M15 17h6v-2h-6v2zm-4 0h2V7h-2v10zm-8 0h6v-4H3v4zM3 7v4h6V7H3zm12 6h6V7h-6v6z'),
          'Quality',
          currentQuality + ' ›',
          function() {
            activeSubmenu = 'quality';
            renderOverlay();
          }
        );
        list.appendChild(qualityItem);

        const currentRate = (mp.getPlaybackRate && mp.getPlaybackRate()) || 1;
        const rateText = currentRate === 1 ? 'Normal' : currentRate + 'x';
        const speedItem = createMenuItem(
          createSvgIcon('M10 8v8l6-4-6-4zm9-5H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V5h14v14z'),
          'Playback speed',
          rateText + ' ›',
          function() {
            activeSubmenu = 'speed';
            renderOverlay();
          }
        );
        list.appendChild(speedItem);

        const isSubsOn = (mp.isSubtitlesOn && mp.isSubtitlesOn()) || false;
        const captionsItem = createMenuItem(
          createSvgIcon('M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1c0 .55-.45 1-1 1h-3c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1z'),
          'Captions',
          isSubsOn ? 'On' : 'Off',
          function() {
            if (mp.toggleSubtitles) mp.toggleSubtitles();
            closeOverlay();
          }
        );
        list.appendChild(captionsItem);

        panel.appendChild(list);
      }

      function createMenuItem(icon, labelText, valueText, onClick) {
        const li = document.createElement('li');
        li.className = 'dualsub-settings-item';
        li.onclick = function(e) {
          e.stopPropagation();
          onClick();
        };

        const labelDiv = document.createElement('span');
        labelDiv.className = 'dualsub-settings-item-label';
        if (icon) labelDiv.appendChild(icon);
        const labelSpan = document.createElement('span');
        labelSpan.textContent = labelText;
        labelDiv.appendChild(labelSpan);
        li.appendChild(labelDiv);

        if (valueText) {
          const valSpan = document.createElement('span');
          valSpan.className = 'dualsub-settings-item-value';
          valSpan.textContent = valueText;
          li.appendChild(valSpan);
        }

        return li;
      }

      function renderQualitySubmenu(panel, mp) {
        panel.appendChild(createHeader('Quality', function() {
          activeSubmenu = null;
          renderOverlay();
        }));

        const list = document.createElement('ul');
        list.className = 'dualsub-settings-list';

        const rawLevels = (mp.getAvailableQualityLevels && mp.getAvailableQualityLevels()) || [];
        const rawData = (mp.getAvailableQualityData && mp.getAvailableQualityData()) || [];
        const preferred = (mp.getPreferredQuality && mp.getPreferredQuality()) || '';
        const currentQuality = (mp.getPlaybackQuality && mp.getPlaybackQuality()) || '';

        const options = [];
        if (rawData.length > 0) {
          for (let i = 0; i < rawData.length; i++) {
            const q = rawData[i];
            const level = q.quality || q.qualityLevel;
            const label = q.qualityLabel || level;
            options.push({ level: level, label: label });
          }
          options.push({ level: 'auto', label: 'Auto' });
        } else if (rawLevels.length > 0) {
          for (let i = 0; i < rawLevels.length; i++) {
            const q = rawLevels[i];
            const num = q.replace(/[^0-9]/g, '');
            const label = q === 'auto' ? 'Auto' : (num ? num + 'p' : q);
            options.push({ level: q, label: label });
          }
        } else {
          options.push({ level: 'auto', label: 'Auto' });
        }

        for (let i = 0; i < options.length; i++) {
          const opt = options[i];
          const isSelected = opt.level === preferred || opt.level === currentQuality ||
                             ((preferred === '' || preferred === 'auto') && opt.level === 'auto');
          const li = document.createElement('li');
          li.className = 'dualsub-settings-item';
          li.onclick = function(e) {
            e.stopPropagation();
            if (mp.setPlaybackQualityRange) mp.setPlaybackQualityRange(opt.level, opt.level);
            else if (mp.setPlaybackQuality) mp.setPlaybackQuality(opt.level);
            closeOverlay();
          };

          const labelDiv = document.createElement('span');
          labelDiv.className = 'dualsub-settings-item-label';

          const check = document.createElement('span');
          check.className = 'dualsub-check';
          check.textContent = isSelected ? '✓' : '';
          labelDiv.appendChild(check);

          const labelSpan = document.createElement('span');
          labelSpan.textContent = opt.label;
          labelDiv.appendChild(labelSpan);

          li.appendChild(labelDiv);
          list.appendChild(li);
        }

        panel.appendChild(list);
      }

      function renderSpeedSubmenu(panel, mp) {
        panel.appendChild(createHeader('Playback speed', function() {
          activeSubmenu = null;
          renderOverlay();
        }));

        const list = document.createElement('ul');
        list.className = 'dualsub-settings-list';

        const rates = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
        const currentRate = (mp.getPlaybackRate && mp.getPlaybackRate()) || 1;

        for (let i = 0; i < rates.length; i++) {
          const r = rates[i];
          const isSelected = Math.abs(currentRate - r) < 0.01;
          const label = r === 1 ? 'Normal' : r + 'x';
          const li = document.createElement('li');
          li.className = 'dualsub-settings-item';
          li.onclick = function(e) {
            e.stopPropagation();
            if (mp.setPlaybackRate) mp.setPlaybackRate(r);
            closeOverlay();
          };

          const labelDiv = document.createElement('span');
          labelDiv.className = 'dualsub-settings-item-label';

          const check = document.createElement('span');
          check.className = 'dualsub-check';
          check.textContent = isSelected ? '✓' : '';
          labelDiv.appendChild(check);

          const labelSpan = document.createElement('span');
          labelSpan.textContent = label;
          labelDiv.appendChild(labelSpan);

          li.appendChild(labelDiv);
          list.appendChild(li);
        }

        panel.appendChild(list);
      }

      function handleSettingsCapture(e) {
        const target = e.target;
        if (!target) return;
        const btn = (target.closest && target.closest('.player-settings-icon, [aria-label="Playback Settings"], ytm-mobile-topbar-renderer [aria-label="More options"]')) ||
                    (target.classList && target.classList.contains('player-settings-icon') ? target : null);
        if (btn) {
          const mp = document.getElementById('movie_player');
          if (mp) {
            e.stopPropagation();
            e.preventDefault();
            renderOverlay();
          }
        }
      }

      window.addEventListener('click', handleSettingsCapture, true);
      window.addEventListener('touchend', handleSettingsCapture, true);
      document.addEventListener('click', handleSettingsCapture, true);
      document.addEventListener('touchend', handleSettingsCapture, true);

      return true;
    })();
    """.trimIndent()

internal fun parseWebPlaybackSnapshot(rawValue: String?): WebPlaybackSnapshot? {
    if (rawValue.isNullOrBlank() || rawValue == "null") return null
    return runCatching {
        val decoded = JSONTokener(rawValue).nextValue() as? String ?: return@runCatching null
        val json = JSONObject(decoded)
        val liveCaption = json.optJSONObject("liveCaption")?.let { live ->
            val text = live.optString("text").replace(Regex("\\s+"), " ").trim()
            val revision = live.optLong("revision", -1L)
            val mediaSecond = live.optDouble("mediaSecond", Double.NaN)
            if (
                text.length <= MAX_LIVE_CAPTION_TEXT_LENGTH &&
                revision >= 0L &&
                mediaSecond.isFinite() &&
                mediaSecond >= 0.0
            ) {
                LiveCaptionSample(
                    text = text,
                    revision = revision,
                    mediaTimeMs = (mediaSecond * 1_000.0).toLong(),
                    present = live.optBoolean("present", text.isNotBlank()) && text.isNotBlank(),
                )
            } else {
                null
            }
        }
        WebPlaybackSnapshot(
            url = json.getString("url"),
            currentSecond = if (json.isNull("currentSecond")) {
                null
            } else {
                json.getDouble("currentSecond").toFloat()
            },
            controlsVisible = json.optBoolean("controlsVisible", false),
            liveCaption = liveCaption,
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
    onPlaybackSecond: (videoId: String, second: Float, liveCaption: LiveCaptionSample?) -> Unit,
    liveCaptionCaptureEnabled: Boolean = false,
    suppressPageCaptions: Boolean = false,
    fullscreenOverlay: (@Composable BoxScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnPlaybackSecond by rememberUpdatedState(onPlaybackSecond)
    val currentLiveCaptionCaptureEnabled by rememberUpdatedState(liveCaptionCaptureEnabled)
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
                    youtubeFullscreenActive.value = true
                }

                override fun onHideCustomView() {
                    youtubeFullscreenActive.value = false
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
                            webLiveCaptionConfigurationScript(currentLiveCaptionCaptureEnabled),
                            null,
                        )
                        view.evaluateJavascript(
                            webCaptionVisibilityScript(currentSuppressPageCaptions),
                            null,
                        )
                        view.evaluateJavascript(WEB_PLAYBACK_SETTINGS_SCRIPT, null)
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

    LaunchedEffect(webView, liveCaptionCaptureEnabled) {
        if (webView.url.orEmpty().let(::isYouTubeWebUrl)) {
            webView.evaluateJavascript(
                webLiveCaptionConfigurationScript(liveCaptionCaptureEnabled),
                null,
            )
        }
    }

    LaunchedEffect(webView, lifecycleStarted) {
        if (!lifecycleStarted) return@LaunchedEffect
        while (isActive) {
            if (!webView.url.orEmpty().let(::isYouTubeWebUrl)) {
                youtubePlayerControlsVisible.value = false
                delay(PLAYBACK_POLL_INTERVAL_MS)
                continue
            }
            webView.evaluateJavascript(WEB_PLAYBACK_SNAPSHOT_SCRIPT) { rawValue ->
                val snapshot = parseWebPlaybackSnapshot(rawValue) ?: return@evaluateJavascript
                youtubePlayerControlsVisible.value = snapshot.controlsVisible
                reportNavigation(webView, snapshot.url)
                val selection = browseVideoSelection(snapshot.url) ?: return@evaluateJavascript
                val second = snapshot.currentSecond ?: return@evaluateJavascript
                currentOnPlaybackSecond(selection.videoId, second, snapshot.liveCaption)
            }
            webView.evaluateJavascript(WEB_PLAYBACK_SETTINGS_SCRIPT, null)
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
            youtubePlayerControlsVisible.value = false
            youtubeFullscreenActive.value = false
            webView.webChromeClient?.onHideCustomView()
            if (webView.url.orEmpty().let(::isYouTubeWebUrl)) {
                webView.evaluateJavascript(webLiveCaptionConfigurationScript(enabled = false), null)
            }
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
