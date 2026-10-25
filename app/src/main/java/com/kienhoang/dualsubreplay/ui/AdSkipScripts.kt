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
import kotlinx.coroutines.flow.MutableStateFlow
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

/** How often the app asks the page for the Skip button while an ad shows. */
private const val SKIP_TARGET_AD_MS = 300L

/** The close button of a banner ad over the playing video. */
private const val BANNER_CLOSE_SELECTOR = ".ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container button"

/**
 * Installed once per page; does nothing until [webAdSkipConfigScript] turns it on. Every second
 * (every 0.25 s while an ad shows) it looks at the player: during an ad it mutes the page video,
 * plays it at [AD_PLAYBACK_RATE] and presses Skip once it shows, and afterwards gives the video its
 * sound and speed back. Banner ads are closed. The video's `timeupdate` (and the timer, in case it
 * is missed) jumps over a sponsor part the video plays into; the user's own jump into one (the user
 * chose to watch it) keeps it, but the player's own seeks do not.
 */
internal val WEB_AD_SKIP_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      if (window.__dualSubAdSkip) return true;
      const RATE = $AD_PLAYBACK_RATE;
      const state = {
        config: { ads: false, always: false, videoId: null, segments: [], note: '' },
        kept: {}, adVideo: null, adButton: null, skipSince: 0, lastTime: null, skippedAt: 0
      };
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
      // A short name of an element for the check line in Settings, such as button.ytp-skip-ad-button.
      const describe = function(element) {
        if (!element || !element.tagName) return 'nothing';
        const name = (element.getAttribute('class') || '').trim().split(/\s+/)[0];
        return element.tagName.toLowerCase() + (name ? '.' + name : '');
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
      // A new button (the next ad of several in a row) must show for 0.3 s again before it is tapped.
      const trackButton = function(button) {
        if (button === state.adButton) return;
        state.adButton = button;
        state.skipSince = button ? Date.now() : 0;
      };
      const skipAd = function() {
        const root = player();
        if (!state.config.ads || !adShowing(root)) {
          restore();
          state.adButton = null;
          return false;
        }
        state.lastTime = null;
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
        trackButton(button);
        if (button) button.click();
        return true;
      };
      state.adShowing = function() { return adShowing(player()); };
      // During an ad, a point of the shown Skip button in screen pixels once it has shown for 0.3 s, with
      // `info` on what was found for the check line in Settings; null without an ad.
      state.skipTarget = function() {
        const root = player();
        if (!state.config.ads || !adShowing(root)) return null;
        const button = skipButton(root);
        trackButton(button);
        if (!button) {
          const hidden = Array.from(root.querySelectorAll('[class*="skip"]')).slice(0, 3).map(describe);
          return { info: hidden.length ? 'Skip not shown yet (' + hidden.join(', ') + ')' : 'no Skip button' };
        }
        if (!state.skipSince || Date.now() - state.skipSince < 300) return { info: 'Skip shown: ' + describe(button) };
        const rect = button.getBoundingClientRect();
        const zoom = window.devicePixelRatio || 1;
        const points = [[0.5, 0.5], [0.3, 0.5], [0.7, 0.5], [0.5, 0.3], [0.5, 0.7]];
        let covered = null;
        for (let i = 0; i < points.length; i++) {
          const x = rect.left + rect.width * points[i][0];
          const y = rect.top + rect.height * points[i][1];
          if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) continue;
          // Only a tap that lands on the button or its Skip box, never on the ad's video (which opens the advertiser).
          const hit = document.elementFromPoint(x, y);
          const box = hit && hit.closest('[class*="skip"]');
          if (hit && (button.contains(hit) || (box && box.contains(button)))) {
            return { x: x * zoom, y: y * zoom, info: 'Skip tapped: ' + describe(button) };
          }
          covered = covered || hit;
        }
        return { info: describe(button) + ' covered by ' + describe(covered) };
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
      const skipSponsor = function(video, segment) {
        state.skippedAt = Date.now();
        state.lastTime = segment.end;
        video.currentTime = segment.end;
        note();
      };
      // Playing into a sponsor part jumps over it.
      const checkSponsor = function(video) {
        if (adShowing(player())) { state.lastTime = null; return; }
        if (video.seeking) return;
        const segment = sponsorAt(video);
        if (segment) skipSponsor(video, segment);
        else state.lastTime = video.currentTime;
      };
      window.addEventListener('timeupdate', function(event) {
        const video = event.target;
        if (video instanceof HTMLVideoElement && video === pageVideo()) checkSponsor(video);
      }, true);
      window.addEventListener('seeking', function(event) {
        const video = event.target;
        if (!(video instanceof HTMLVideoElement) || video !== pageVideo()) return;
        const segment = sponsorAt(video);
        if (!segment) return;
        // The user's own jump into a sponsor part keeps it, unless Always skip sponsors is on. The player's own
        // seeks (small ones, its return after an ad, undoing a skip) are not the user's: they are skipped too.
        const jumped = state.lastTime !== null && Math.abs(video.currentTime - state.lastTime) >= 1.5 &&
          Date.now() - state.skippedAt > 3000;
        if (state.config.always || !jumped) skipSponsor(video, segment);
        else state.kept[segment.id] = true;
      }, true);
      const tick = function() {
        let fast = false;
        try {
          fast = skipAd();
          closeBanner();
          const video = pageVideo();
          if (video && !video.paused) checkSponsor(video);
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
          if (state.config.videoId !== $id) { state.kept = {}; state.lastTime = null; }
          state.config = { ads: $skipAds, always: $always, videoId: $id, segments: $list, note: ${JSONObject.quote(note)} };
          return !!(state.config.ads && state.adShowing && state.adShowing());
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

/** The page's `info` on the last ad's Skip button, for the check line in Settings. */
internal fun parseSkipInfo(reply: String?): String? =
    runCatching { JSONObject(reply ?: return null) }
        .getOrNull()
        ?.optString("info")
        ?.takeIf { it.isNotBlank() }
        ?.take(MAX_SKIP_INFO_LENGTH)

private const val MAX_SKIP_INFO_LENGTH = 160

/**
 * What the app last saw of an ad and of a video's sponsor parts, shown under Settings → Ads & sponsors so a
 * phone test can tell why a Skip was not pressed (owner test of build 505, 2026-10-25).
 */
internal object AdSkipReport {
    val lastAd = MutableStateFlow<String?>(null)
    val sponsorParts = MutableStateFlow<Int?>(null)
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

/** Runs [script] on the page and returns its JSON reply ("null" when it returned nothing). */
private suspend fun WebView.evaluate(script: String): String? {
    val reply = CompletableDeferred<String?>()
    evaluateJavascript(script) { reply.complete(it) }
    return reply.await()
}

/**
 * Keeps the page's ad and sponsor skipping in step with the settings and the open video. One script
 * call every 2 s, which also says whether an ad shows; only then the app asks for the Skip button
 * every 0.3 s and taps it ([PageCpuUseTest] keeps the idle cost within budget).
 */
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
    val currentPageView by rememberUpdatedState(pageView)
    LaunchedEffect(webView, settings, note, active) {
        if (!active) return@LaunchedEffect
        var videoId: String? = null
        var segments = emptyList<SponsorSegment>()
        var lastTap = 0L
        var taps = 0
        val on = settings.skipAds || settings.skipSponsors
        val always = settings.skipSponsors && settings.alwaysSkipSponsors
        val install = if (on) WEB_AD_SKIP_SCRIPT + "\n" else ""
        // WebView calls must stay on the main thread, also after the SponsorBlock request resumes.
        withContext(Dispatchers.Main.immediate) {
            while (isActive) {
                val url = webView.url.orEmpty()
                var adShowing = false
                if (isYouTubeWebUrl(url)) {
                    val id = browseVideoSelection(url)?.videoId?.takeIf { settings.skipSponsors }
                    if (id != videoId) {
                        videoId = id
                        segments = emptyList()
                        webView.evaluate(install + webAdSkipConfigScript(settings.skipAds, id, segments, note, always))
                        if (id != null) {
                            segments = source.segments(id)
                            AdSkipReport.sponsorParts.value = segments.size
                        }
                    }
                    adShowing =
                        webView.evaluate(install + webAdSkipConfigScript(settings.skipAds, videoId, segments, note, always)) == "true"
                    if (adShowing) {
                        val reply = webView.evaluate(WEB_SKIP_TARGET_SCRIPT)
                        val target = parseSkipTarget(reply)
                        val now = SystemClock.uptimeMillis()
                        if (target != null && now - lastTap >= SKIP_TAP_INTERVAL_MS) {
                            lastTap = now
                            taps++
                            tap(currentPageView(), target.first, target.second)
                        }
                        parseSkipInfo(reply)?.let { AdSkipReport.lastAd.value = "$it ($taps taps)" }
                    } else {
                        taps = 0
                    }
                }
                if (!on) break
                delay(if (adShowing) SKIP_TARGET_AD_MS else AD_SKIP_CHECK_MS)
            }
        }
    }
}
