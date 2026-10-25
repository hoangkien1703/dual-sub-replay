package com.kienhoang.dualsubreplay.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SponsorSegment
import com.kienhoang.dualsubreplay.data.SponsorSegmentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/*
 * Settings → Ads and sponsors (owner request 2026-10-25, spec
 * docs/specs/2026-10-25-skip-ads-and-sponsors.md). YouTube's ads still load: while one shows, the page
 * video is muted and fast, and YouTube's own Skip button is pressed. Sponsor parts come from
 * SponsorBlock and are jumped over once. Neither changes a request or reply of the page.
 */

/** The speed an ad plays at. Add-on authors report YouTube's ad-blocker banner at higher speeds. */
internal const val AD_PLAYBACK_RATE = 4

/** The open video's settings and segments are given to the page this often (it may reload or change video). */
private const val AD_SKIP_CHECK_MS = 2_000L

internal const val SKIP_NOTE_ELEMENT_ID = "dualsub-skip-note"

/**
 * YouTube's Skip buttons, old and new; the mobile site uses the same player. Only looked for inside the
 * player while it shows an ad, so the broad `skip` matches can't hit anything else.
 */
private const val SKIP_BUTTON_SELECTOR =
    ".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button, " +
        "[class*=\"ytp-ad-skip-button\"], [class*=\"ytp-skip-ad-button\"], [class*=\"skip-ad\"] button, " +
        "[class*=\"skip-button\"] button, button[class*=\"skip\"], [id^=\"skip-button\"] button"

/** The app taps YouTube's Skip button at most this often; YouTube ignores a press made by a page script. */
private const val SKIP_TAP_INTERVAL_MS = 1_500L

/** How often the app asks the page for the Skip button while an ad shows, and otherwise. */
private const val SKIP_TARGET_AD_MS = 300L
private const val SKIP_TARGET_IDLE_MS = 1_000L

/** The close button of a banner ad over the playing video. */
private const val BANNER_CLOSE_SELECTOR = ".ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container button"

/**
 * Installed once per page; does nothing until [webAdSkipConfigScript] turns it on. Every second
 * (every 0.25 s while an ad shows) it looks at the player: during an ad it mutes the page video,
 * plays it at [AD_PLAYBACK_RATE] and presses Skip once it shows, and afterwards gives the video its
 * sound and speed back. Banner ads are closed. The video's `timeupdate` jumps over a sponsor part
 * the video plays into; a seek that lands in one (the user chose to watch it) keeps it.
 */
internal val WEB_AD_SKIP_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      if (window.__dualSubAdSkip) return true;
      const RATE = $AD_PLAYBACK_RATE;
      const state = { config: { ads: false, always: false, videoId: null, segments: [], note: '' }, kept: {}, adVideo: null };
      window.__dualSubAdSkip = state;
      const player = function() {
        return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      };
      const pageVideo = function() {
        const root = player();
        return (root && root.querySelector('video')) || document.querySelector('video');
      };
      const adShowing = function(root) {
        return !!root && (root.classList.contains('ad-showing') || root.classList.contains('ad-interrupting'));
      };
      // Shown means on screen and not hidden, see-through or switched off by any box around it.
      const shown = function(element) {
        if (!element || element.disabled || element.getClientRects().length === 0) return false;
        const rect = element.getBoundingClientRect();
        if (rect.width < 4 || rect.height < 4) return false;
        for (let node = element; node && node.nodeType === 1; node = node.parentElement) {
          const style = window.getComputedStyle(node);
          if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) < 0.1 ||
              style.pointerEvents === 'none') return false;
        }
        return true;
      };
      const skipButton = function(root) {
        const found = Array.from(root.querySelectorAll('$SKIP_BUTTON_SELECTOR')).filter(shown);
        return found.find(function(item) { return item.tagName === 'BUTTON'; }) || found[0] || null;
      };
      const pageVideoId = function() {
        try {
          const url = new URL(window.location.href);
          const id = url.searchParams.get('v');
          if (id) return id;
          const parts = url.pathname.split('/');
          return ['shorts', 'live', 'embed'].indexOf(parts[1]) >= 0 ? (parts[2] || null) : null;
        } catch (_) { return null; }
      };
      const restore = function() {
        const video = state.adVideo;
        state.adVideo = null;
        if (!video) return;
        if (video.playbackRate === RATE && typeof video.__dualSubAdRate === 'number') video.playbackRate = video.__dualSubAdRate;
        if (typeof video.__dualSubAdMuted === 'boolean') video.muted = video.__dualSubAdMuted;
        delete video.__dualSubAdRate;
        delete video.__dualSubAdMuted;
      };
      const skipAd = function() {
        const root = player();
        if (!state.config.ads || !adShowing(root)) { restore(); return false; }
        const video = pageVideo();
        if (video && state.adVideo !== video) {
          restore();
          state.adVideo = video;
          video.__dualSubAdRate = video.playbackRate === RATE ? 1 : video.playbackRate;
          video.__dualSubAdMuted = video.muted;
        }
        if (video) {
          if (!video.muted) video.muted = true;
          if (video.playbackRate !== RATE) video.playbackRate = RATE;
        }
        // A press from a script works on some players; on the rest the app taps it (WEB_SKIP_TARGET_SCRIPT).
        const button = skipButton(root);
        if (button) {
          state.skipSince = state.skipSince || Date.now();
          button.click();
        } else {
          state.skipSince = 0;
        }
        return true;
      };
      // During an ad, the middle of the shown Skip button in screen pixels once it has shown for 0.3 s
      // (else {}); null without an ad.
      state.skipTarget = function() {
        const root = player();
        if (!state.config.ads || !adShowing(root)) return null;
        const button = skipButton(root);
        if (!button || !state.skipSince || Date.now() - state.skipSince < 300) return {};
        const rect = button.getBoundingClientRect();
        const x = rect.left + rect.width / 2;
        const y = rect.top + rect.height / 2;
        if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) return {};
        // Only a tap that lands on the button itself, never on the ad's video (which opens the advertiser).
        const hit = document.elementFromPoint(x, y);
        if (!hit || !(button.contains(hit) || (hit.contains(button) && /skip/i.test(hit.getAttribute('class') || '')))) return {};
        const zoom = window.devicePixelRatio || 1;
        return { x: x * zoom, y: y * zoom };
      };
      const closeBanner = function() {
        if (!state.config.ads) return;
        const root = player();
        const close = root && root.querySelector('$BANNER_CLOSE_SELECTOR');
        if (shown(close)) close.click();
      };
      const note = function() {
        const root = player();
        if (!root || !state.config.note) return;
        let element = document.getElementById('$SKIP_NOTE_ELEMENT_ID');
        if (!element) {
          element = document.createElement('div');
          element.id = '$SKIP_NOTE_ELEMENT_ID';
          element.style.cssText = 'position:absolute;left:12px;top:12px;z-index:60;padding:6px 10px;border-radius:8px;' +
            'background:rgba(0,0,0,0.72);color:#fff;font:500 13px sans-serif;pointer-events:none;transition:opacity 0.4s;';
        }
        if (element.parentElement !== root) root.appendChild(element);
        element.textContent = state.config.note;
        element.style.opacity = '1';
        clearTimeout(state.noteTimer);
        state.noteTimer = setTimeout(function() { element.style.opacity = '0'; }, 2500);
      };
      const sponsorAt = function(video) {
        const config = state.config;
        if (!config.segments.length || config.videoId !== pageVideoId() || adShowing(player())) return null;
        const time = video.currentTime;
        return config.segments.find(function(segment) {
          if (!config.always && state.kept[segment.id]) return false;
          if (segment.duration > 0 && Number.isFinite(video.duration) && Math.abs(video.duration - segment.duration) > 3) return false;
          return time >= segment.start && time < segment.end - 0.5;
        }) || null;
      };
      document.addEventListener('timeupdate', function(event) {
        const video = event.target;
        if (!(video instanceof HTMLVideoElement) || video !== pageVideo() || video.seeking) return;
        const segment = sponsorAt(video);
        if (!segment) return;
        state.kept[segment.id] = true;
        video.currentTime = segment.end;
        note();
      }, true);
      document.addEventListener('seeking', function(event) {
        const video = event.target;
        if (!(video instanceof HTMLVideoElement) || video !== pageVideo()) return;
        const segment = sponsorAt(video);
        if (!segment) return;
        // Always skip sponsors: a seek into a sponsor part lands at its end instead.
        if (state.config.always) {
          video.currentTime = segment.end;
          note();
        } else {
          state.kept[segment.id] = true;
        }
      }, true);
      const tick = function() {
        let fast = false;
        try {
          fast = skipAd();
          closeBanner();
        } catch (_) {}
        setTimeout(tick, fast ? 250 : 1000);
      };
      tick();
      return true;
    })();
    """.trimIndent()

private fun jsNumber(value: Double): String = if (value.isFinite()) String.format(Locale.ROOT, "%.3f", value) else "0"

/**
 * Gives the installed [WEB_AD_SKIP_SCRIPT] its settings and the sponsor parts of [videoId] (none
 * while Skip sponsors is off); [note] is the "Skipped sponsor" text. A new video starts over, so its
 * parts are skipped again even if the user kept them in the last one. With [always] (Always skip
 * sponsors, owner request 2026-10-25) a seek into a sponsor part is skipped too.
 */
internal fun webAdSkipConfigScript(
    skipAds: Boolean,
    videoId: String?,
    segments: List<SponsorSegment>,
    note: String,
    always: Boolean = false,
): String {
    val list =
        segments.joinToString(",", "[", "]") { segment ->
            "{id:${JSONObject.quote(segment.id)},start:${jsNumber(segment.start)},end:${jsNumber(segment.end)}," +
                "duration:${jsNumber(segment.videoDuration)}}"
        }
    val id = videoId?.let(JSONObject::quote) ?: "null"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const state = window.__dualSubAdSkip;
          if (!state) return false;
          if (state.config.videoId !== $id) state.kept = {};
          state.config = { ads: $skipAds, always: $always, videoId: $id, segments: $list, note: ${JSONObject.quote(note)} };
          return true;
        })();
        """.trimIndent()
}

/**
 * Asks the installed [WEB_AD_SKIP_SCRIPT] where YouTube's shown Skip button is: `{x, y}`, `{}` during an
 * ad without one, or null.
 */
internal val WEB_SKIP_TARGET_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
      const state = window.__dualSubAdSkip;
      return state && state.skipTarget ? state.skipTarget() : null;
    })();
    """.trimIndent()

/** Where to tap, in pixels of the view showing the page, from [WEB_SKIP_TARGET_SCRIPT]'s reply. */
internal fun parseSkipTarget(reply: String?): Pair<Float, Float>? {
    val target = runCatching { JSONObject(reply ?: return null) }.getOrNull() ?: return null
    if (!target.has("x") || !target.has("y")) return null
    val x = target.optDouble("x").toFloat()
    val y = target.optDouble("y").toFloat()
    return (x to y).takeIf { x.isFinite() && y.isFinite() && x >= 0f && y >= 0f }
}

/** A real tap: YouTube ignores a Skip press made by a page script, but takes the user's own touch. */
private fun tap(
    view: View,
    x: Float,
    y: Float,
) {
    val time = SystemClock.uptimeMillis()
    listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + TAP_LENGTH_MS).forEach { (action, at) ->
        val event = MotionEvent.obtain(time, at, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }
}

private const val TAP_LENGTH_MS = 60L

/** Keeps the page's ad and sponsor skipping in step with the settings and the open video. */
@Composable
internal fun AdSkipPageScripts(
    webView: WebView,
    active: Boolean,
    /** The view the page shows in: the full-screen view while the video is full screen. */
    pageView: () -> View = { webView },
) {
    val settings by rememberAdSkipSettings()
    val note = stringResource(R.string.ads_skipped_sponsor_note)
    val source = remember { SponsorSegmentSource() }
    LaunchedEffect(webView, settings, note, active) {
        if (!active) return@LaunchedEffect
        var videoId: String? = null
        var segments = emptyList<SponsorSegment>()
        val on = settings.skipAds || settings.skipSponsors
        val always = settings.skipSponsors && settings.alwaysSkipSponsors
        // WebView calls must stay on the main thread, also after the SponsorBlock request resumes.
        withContext(Dispatchers.Main.immediate) {
            while (isActive) {
                val url = webView.url.orEmpty()
                if (isYouTubeWebUrl(url)) {
                    if (on) webView.evaluateJavascript(WEB_AD_SKIP_SCRIPT, null)
                    val id = browseVideoSelection(url)?.videoId?.takeIf { settings.skipSponsors }
                    if (id != videoId) {
                        videoId = id
                        segments = emptyList()
                        webView.evaluateJavascript(webAdSkipConfigScript(settings.skipAds, id, segments, note, always), null)
                        if (id != null) segments = source.segments(id)
                    }
                    webView.evaluateJavascript(webAdSkipConfigScript(settings.skipAds, videoId, segments, note, always), null)
                }
                if (!on) break
                delay(AD_SKIP_CHECK_MS)
            }
        }
    }
    val currentPageView by rememberUpdatedState(pageView)
    LaunchedEffect(webView, settings.skipAds, active) {
        if (!active || !settings.skipAds) return@LaunchedEffect
        var lastTap = 0L
        withContext(Dispatchers.Main.immediate) {
            while (isActive) {
                val reply = CompletableDeferred<String?>()
                if (isYouTubeWebUrl(webView.url.orEmpty())) {
                    webView.evaluateJavascript(WEB_SKIP_TARGET_SCRIPT) { reply.complete(it) }
                } else {
                    reply.complete(null)
                }
                val answer = reply.await()
                val target = parseSkipTarget(answer)
                val now = SystemClock.uptimeMillis()
                if (target != null && now - lastTap >= SKIP_TAP_INTERVAL_MS) {
                    lastTap = now
                    tap(currentPageView(), target.first, target.second)
                }
                val adShowing = answer != null && answer != "null"
                delay(if (adShowing) SKIP_TARGET_AD_MS else SKIP_TARGET_IDLE_MS)
            }
        }
    }
}
