package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SponsorSegment
import com.kienhoang.dualsubreplay.data.SponsorSegmentSource
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

/** YouTube's Skip buttons, old and new; the mobile site uses the same player. */
private const val SKIP_BUTTON_SELECTOR =
    ".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button, " +
        "[class*=\"ytp-ad-skip-button\"], [class*=\"ytp-skip-ad-button\"]"

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
      const state = { config: { ads: false, videoId: null, segments: [], note: '' }, kept: {}, adVideo: null };
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
      const shown = function(element) {
        return !!element && !element.disabled && element.getClientRects().length > 0;
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
        // The broad selectors also match the button's box; a real button is pressed first.
        const found = Array.from(root.querySelectorAll('$SKIP_BUTTON_SELECTOR')).filter(shown);
        const button = found.find(function(item) { return item.tagName === 'BUTTON'; }) || found[0];
        if (button) button.click();
        return true;
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
          if (state.kept[segment.id]) return false;
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
        if (segment) state.kept[segment.id] = true;
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
 * parts are skipped again even if the user kept them in the last one.
 */
internal fun webAdSkipConfigScript(
    skipAds: Boolean,
    videoId: String?,
    segments: List<SponsorSegment>,
    note: String,
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
          state.config = { ads: $skipAds, videoId: $id, segments: $list, note: ${JSONObject.quote(note)} };
          return true;
        })();
        """.trimIndent()
}

/** Keeps the page's ad and sponsor skipping in step with the settings and the open video. */
@Composable
internal fun AdSkipPageScripts(
    webView: WebView,
    active: Boolean,
) {
    val settings by rememberAdSkipSettings()
    val note = stringResource(R.string.ads_skipped_sponsor_note)
    val source = remember { SponsorSegmentSource() }
    LaunchedEffect(webView, settings, note, active) {
        if (!active) return@LaunchedEffect
        var videoId: String? = null
        var segments = emptyList<SponsorSegment>()
        val on = settings.skipAds || settings.skipSponsors
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
                        webView.evaluateJavascript(webAdSkipConfigScript(settings.skipAds, id, segments, note), null)
                        if (id != null) segments = source.segments(id)
                    }
                    webView.evaluateJavascript(webAdSkipConfigScript(settings.skipAds, videoId, segments, note), null)
                }
                if (!on) break
                delay(AD_SKIP_CHECK_MS)
            }
        }
    }
}
