package com.kienhoang.dualsubreplay.ui

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// The YouTube page with a TV remote (spec 2026-10-30-android-tv-layout.md). m.youtube.com is a
// touch site: arrow keys only scroll it. In the TV layout this script moves focus between its links
// and buttons instead, and OK opens the focused one. On a video the app takes the arrows itself.

/** How often the script is installed again, since a YouTube page can replace its document. */
private const val TV_FOCUS_CHECK_MS = 2_000L

/**
 * Installs (or, when [enabled] is false, removes) arrow-key focus moving on a YouTube page: an arrow
 * focuses the nearest link or button that way, or scrolls when there is none; OK presses it.
 */
internal fun webTvFocusScript(enabled: Boolean): String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      const old = window.__dualSubTvFocus;
      if (!$enabled) {
        if (old) {
          document.removeEventListener('keydown', old.listener, true);
          if (old.style && old.style.parentNode) old.style.parentNode.removeChild(old.style);
          window.__dualSubTvFocus = null;
        }
        return true;
      }
      if (old && old.style && old.style.isConnected) return true;
      if (old) document.removeEventListener('keydown', old.listener, true);
      const style = document.createElement('style');
      style.textContent = '*:focus{outline:4px solid #00C5E5 !important;outline-offset:2px !important;}';
      (document.head || document.documentElement).appendChild(style);
      const selector = 'a[href],button,input,textarea,select,[role="button"],[role="link"],[role="tab"],' +
        '[tabindex]:not([tabindex="-1"])';
      const directions = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
      const shown = function(element) {
        if (element.disabled || element.getAttribute('aria-hidden') === 'true') return false;
        const box = element.getBoundingClientRect();
        if (box.width < 4 || box.height < 4) return false;
        const look = window.getComputedStyle(element);
        return look.visibility !== 'hidden' && look.display !== 'none';
      };
      const centre = function(box) { return { x: box.left + box.width / 2, y: box.top + box.height / 2 }; };
      const nearest = function(from, way) {
        const start = from ? from.getBoundingClientRect() : null;
        const origin = start ? centre(start) : { x: 0, y: 0 };
        let best = null;
        let bestScore = Infinity;
        document.querySelectorAll(selector).forEach(function(element) {
          if (element === from || (from && (from.contains(element) || element.contains(from))) || !shown(element)) return;
          const box = element.getBoundingClientRect();
          if (!start && (box.bottom < 0 || box.top > window.innerHeight)) return;
          const point = centre(box);
          const along = (point.x - origin.x) * way[0] + (point.y - origin.y) * way[1];
          if (start && along <= 4) return;
          const across = Math.abs((point.x - origin.x) * way[1]) + Math.abs((point.y - origin.y) * way[0]);
          const score = (start ? along : Math.abs(point.y) + Math.abs(point.x)) + across * 2;
          if (score < bestScore) { bestScore = score; best = element; }
        });
        return best;
      };
      const listener = function(event) {
        const active = document.activeElement && document.activeElement !== document.body ? document.activeElement : null;
        if (event.key === 'Enter' && active && active.tagName !== 'A' && active.tagName !== 'BUTTON' &&
            active.tagName !== 'INPUT' && active.tagName !== 'TEXTAREA') {
          active.click();
          event.preventDefault();
          return;
        }
        const way = directions[event.key];
        if (!way) return;
        if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA') && way[1] === 0) return;
        const next = nearest(active, way);
        event.preventDefault();
        event.stopPropagation();
        if (next) {
          next.focus({ preventScroll: true });
          next.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
        } else if (way[1] !== 0) {
          window.scrollBy({ top: way[1] * window.innerHeight * 0.6, behavior: 'smooth' });
        }
      };
      document.addEventListener('keydown', listener, true);
      window.__dualSubTvFocus = { listener: listener, style: style };
      return true;
    })();
    """.trimIndent()

/** Keeps the arrow-key script on every YouTube page while the TV layout is on, and removes it after. */
@Composable
internal fun TvPageScripts(
    webView: WebView,
    tvLayout: Boolean,
    active: Boolean,
) {
    LaunchedEffect(webView, tvLayout) {
        // m.youtube.com serves its touch site only to a browser that says it is mobile.
        val default = WebSettings.getDefaultUserAgent(webView.context)
        val wanted = if (tvLayout) tvUserAgent(default) else default
        if (webView.settings.userAgentString != wanted) webView.settings.userAgentString = wanted
    }
    var installed by remember(webView) { mutableStateOf(false) }
    LaunchedEffect(webView, tvLayout, active) {
        if (!active) return@LaunchedEffect
        if (!tvLayout) {
            if (installed) webView.evaluateJavascript(webTvFocusScript(enabled = false), null)
            installed = false
            return@LaunchedEffect
        }
        installed = true
        val script = webTvFocusScript(enabled = true)
        while (isActive) {
            if (isYouTubeWebUrl(webView.url.orEmpty())) webView.evaluateJavascript(script, null)
            delay(TV_FOCUS_CHECK_MS)
        }
    }
}

/**
 * The WebView's own user agent, marked mobile when a TV's WebView does not say so, so YouTube sends
 * the same m.youtube.com page the app reads on phones.
 */
internal fun tvUserAgent(default: String): String =
    when {
        default.contains(" Mobile") -> default
        default.contains(" Safari/") -> default.replaceFirst(" Safari/", " Mobile Safari/")
        else -> "$default Mobile"
    }
