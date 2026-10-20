package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The page's blur box is put back this often, as YouTube may rebuild its player. */
private const val BLUR_BOX_CHECK_MS = 3_000L

/** The highest speed Skip silence asks for. */
private const val MAX_GAP_SPEED = 4f

internal const val BLUR_BOX_ELEMENT_ID = "dualsub-blur-box"

/** Pauses the page video with it at [second] (Read first stops at the next line this way). */
internal fun webSeekPausedScript(second: Float): String {
    val safeSecond = second.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const videos = Array.from(document.querySelectorAll('video'));
          const video = videos.find(function(item) { return !item.paused && !item.ended; })
            || videos.find(function(item) { return item.readyState > 0; })
            || videos[0];
          if (!video) return false;
          clearInterval(window.__dualSubClipTimer);
          video.pause();
          video.currentTime = $safeSecond;
          return true;
        })();
        """.trimIndent()
}

/**
 * Skip silence: plays the page video at [rate] (keeping the speed it had), or, with a null [rate],
 * brings that speed back unless the user changed it meanwhile. The page brings it back by itself
 * after [safetyMs], in case the app stops asking (it went to the background, or the page changed).
 */
internal fun webGapSpeedScript(
    rate: Float?,
    safetyMs: Long,
): String {
    val safeRate = rate?.takeIf { it.isFinite() }?.coerceIn(1f, MAX_GAP_SPEED)
    val safeDelay = safetyMs.coerceIn(0L, 600_000L)
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const restore = function() {
            clearTimeout(window.__dualSubGapTimer);
            window.__dualSubGapTimer = null;
            document.querySelectorAll('video').forEach(function(video) {
              if (typeof video.__dualSubGapRate !== 'number') return;
              if (video.playbackRate === video.__dualSubGapFast) video.playbackRate = video.__dualSubGapRate;
              delete video.__dualSubGapRate;
              delete video.__dualSubGapFast;
            });
            return true;
          };
          const rate = ${safeRate ?: "null"};
          if (rate === null) return restore();
          const videos = Array.from(document.querySelectorAll('video'));
          const video = videos.find(function(item) { return !item.paused && !item.ended; }) || videos[0];
          if (!video) return false;
          if (typeof video.__dualSubGapRate !== 'number') video.__dualSubGapRate = video.playbackRate;
          video.__dualSubGapFast = rate;
          video.playbackRate = rate;
          clearTimeout(window.__dualSubGapTimer);
          window.__dualSubGapTimer = setTimeout(restore, $safeDelay);
          return true;
        })();
        """.trimIndent()
}

/**
 * Settings → Study tools → Blur box: a blurred, dimmed box on the page's player that hides
 * subtitles burned into the picture. Dragging moves it and dragging its corner resizes it; its place
 * (as fractions of the player) is kept in the page's storage. Its touches stay with the box, so
 * they neither scroll the page nor reach YouTube's player. With [enabled] false the box is removed.
 */
internal fun webBlurBoxScript(enabled: Boolean): String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      let box = document.getElementById('$BLUR_BOX_ELEMENT_ID');
      if (!$enabled) {
        if (box) box.remove();
        return true;
      }
      const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (!player) return false;
      if (box && box.parentElement === player) return true;
      if (box) box.remove();
      const key = 'dualSubBlurBoxV1';
      let place = { x: 0.1, y: 0.7, w: 0.8, h: 0.18 };
      try {
        const saved = JSON.parse(window.localStorage.getItem(key) || 'null');
        if (saved && [saved.x, saved.y, saved.w, saved.h].every(function(value) {
          return typeof value === 'number' && Number.isFinite(value);
        })) place = saved;
      } catch (_) {}
      const clamp = function(value, low, high) { return Math.min(high, Math.max(low, value)); };
      box = document.createElement('div');
      box.id = '$BLUR_BOX_ELEMENT_ID';
      box.style.cssText = 'position:absolute;z-index:40;box-sizing:border-box;border-radius:8px;' +
        'border:1px dashed rgba(255,255,255,0.5);background:rgba(0,0,0,0.3);' +
        'backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);touch-action:none;';
      const handle = document.createElement('div');
      handle.style.cssText = 'position:absolute;right:0;bottom:0;width:28px;height:28px;box-sizing:border-box;' +
        'border-right:3px solid rgba(255,255,255,0.85);border-bottom:3px solid rgba(255,255,255,0.85);' +
        'border-radius:0 0 8px 0;touch-action:none;';
      box.appendChild(handle);
      const show = function() {
        place.w = clamp(place.w, 0.08, 1);
        place.h = clamp(place.h, 0.05, 1);
        place.x = clamp(place.x, 0, 1 - place.w);
        place.y = clamp(place.y, 0, 1 - place.h);
        box.style.left = (place.x * 100) + '%';
        box.style.top = (place.y * 100) + '%';
        box.style.width = (place.w * 100) + '%';
        box.style.height = (place.h * 100) + '%';
      };
      show();
      const keep = function(event) { event.stopPropagation(); };
      ['touchstart', 'touchmove', 'touchend', 'click', 'dblclick', 'mousedown', 'mouseup'].forEach(function(type) {
        box.addEventListener(type, keep);
      });
      let drag = null;
      box.addEventListener('pointerdown', function(event) {
        event.stopPropagation();
        const rect = player.getBoundingClientRect();
        drag = {
          id: event.pointerId, resize: event.target === handle, x: event.clientX, y: event.clientY,
          from: { x: place.x, y: place.y, w: place.w, h: place.h },
          width: rect.width || 1, height: rect.height || 1
        };
        try { box.setPointerCapture(event.pointerId); } catch (_) {}
      });
      box.addEventListener('pointermove', function(event) {
        if (!drag || event.pointerId !== drag.id) return;
        event.stopPropagation();
        const dx = (event.clientX - drag.x) / drag.width;
        const dy = (event.clientY - drag.y) / drag.height;
        place = drag.resize
          ? { x: drag.from.x, y: drag.from.y, w: drag.from.w + dx, h: drag.from.h + dy }
          : { x: drag.from.x + dx, y: drag.from.y + dy, w: drag.from.w, h: drag.from.h };
        show();
      });
      const finish = function(event) {
        if (!drag) return;
        event.stopPropagation();
        drag = null;
        try { window.localStorage.setItem(key, JSON.stringify(place)); } catch (_) {}
      };
      box.addEventListener('pointerup', finish);
      box.addEventListener('pointercancel', finish);
      player.appendChild(box);
      return true;
    })();
    """.trimIndent()

/** Keeps the blur box on the page while [enabled], and removes it when it is turned off. */
@Composable
internal fun StudyPageScripts(
    webView: WebView,
    enabled: Boolean,
    active: Boolean,
) {
    LaunchedEffect(webView, enabled, active) {
        if (!active) return@LaunchedEffect
        val script = webBlurBoxScript(enabled)
        do {
            if (isYouTubeWebUrl(webView.url.orEmpty())) webView.evaluateJavascript(script, null)
            if (!enabled) break
            delay(BLUR_BOX_CHECK_MS)
        } while (isActive)
    }
}
