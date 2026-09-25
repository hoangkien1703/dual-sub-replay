package com.kienhoang.dualsubreplay.ui

import org.json.JSONObject
import org.json.JSONTokener

internal const val YOUTUBE_CAPTION_STYLE_ID = "dual-sub-hide-youtube-captions"
internal const val LIVE_CAPTION_CAPTURE_STATE_KEY = "__dualSubLiveCaptionV1"
internal const val MAX_LIVE_CAPTION_TEXT_LENGTH = 4_096
internal const val CAPTION_TRACK_SYNC_STATE_KEY = "__dualSubCaptionTrackV1"

internal data class WebPlaybackSnapshot(
    val url: String,
    val currentSecond: Float?,
    val controlsVisible: Boolean = false,
    val liveCaption: LiveCaptionSample? = null,
    val nativeDialogVisible: Boolean = false,
    val paused: Boolean = false,
    val sampledAtEpochMs: Long = 0,
    val playbackRate: Double = 1.0,
    val seeking: Boolean = false,
    val buffering: Boolean = false,
    val sessionId: String = "",
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
      let clock = window.__dualSubPlaybackClock;
      if (!clock) clock = window.__dualSubPlaybackClock = { next: 0, video: null, state: null };
      if (video && (clock.video !== video || clock.state.source !== video.currentSrc)) {
        clock.video = video;
        const state = { id: String(performance.timeOrigin) + ':' + (++clock.next), source: video.currentSrc, waiting: false };
        clock.state = state;
        // Listeners belong to this element; retired elements cannot mutate the active state.
        if (!video.__dualSubClockEvents) {
          video.__dualSubClockEvents = true;
          ['waiting', 'stalled', 'playing', 'seeked', 'seeking', 'emptied'].forEach(function(event) {
            video.addEventListener(event, function() {
              if (clock.video !== video) return;
              if (event === 'seeking' || event === 'emptied') clock.state.id = String(performance.timeOrigin) + ':' + (++clock.next);
              clock.state.waiting = event === 'waiting' || event === 'emptied';
            });
          });
        }
      }
      const sampledAtEpochMs = Date.now();
      const second = video && Number.isFinite(video.currentTime) ? video.currentTime : null;
      const trackSync = window['$CAPTION_TRACK_SYNC_STATE_KEY'];
      if (trackSync && typeof trackSync.run === 'function') {
        try { trackSync.run(); } catch (_) {}
      }
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
      // YouTube's mobile dialog wrapper can have zero bounds; its fixed scrim/layout is visible.
      const nativeDialogVisible = Array.from(document.querySelectorAll('[role="dialog"][aria-modal="true"]'))
        .some(function(dialog) {
          return isVisible(dialog) || Array.from(dialog.querySelectorAll('ytw-scrim, .ytSpecBottomSheetLayoutHost'))
            .some(isVisible);
        });
      return JSON.stringify({
        url: window.location.href,
        currentSecond: second,
        sampledAtEpochMs: sampledAtEpochMs,
        playbackRate: video ? video.playbackRate : 1,
        seeking: !!video && video.seeking,
        buffering: !video || video.readyState < 3 || !!(clock.state && clock.state.waiting),
        sessionId: video && clock.state ? clock.state.id : '',
        paused: !!video && video.paused === true,
        controlsVisible: controlsVisible,
        nativeDialogVisible: nativeDialogVisible,
        liveCaption: liveState ? {
          text: liveState.text || '',
          revision: liveState.revision,
          mediaSecond: liveState.captionMediaSecond,
          videoId: liveState.videoId || null,
          languageCode: liveState.languageCode || null,
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
            // Turn captions on directly in the spoken language rather than YouTube's last one.
            const trackSync = window['$CAPTION_TRACK_SYNC_STATE_KEY'];
            try {
              const choice = trackSync && player && trackSync.choose(player);
              if (choice) return trackSync.optionTrack(player, choice.track);
            } catch (_) {}
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
            const player = state.player();
            let response = null;
            try { response = player && player.getPlayerResponse ? player.getPlayerResponse() : null; } catch (_) {}
            const videoId = response && response.videoDetails ? response.videoDetails.videoId : null;
            const track = state.activeCaptionTrack(player);
            const languageCode = track && (track.translationLanguage ? track.translationLanguage.languageCode : track.languageCode);
            if (text === state.text && videoId === state.videoId && languageCode === state.languageCode) return;
            state.videoId = videoId;
            state.languageCode = languageCode;
            const video = state.activeVideo();
            // Android's fullscreen custom video surface can stop frame callbacks on
            // the page element. Timestamp new captions from the same media clock as
            // playback snapshots, never from the last (possibly frozen) frame.
            const mediaSecond = video && Number.isFinite(video.currentTime)
              ? video.currentTime
              : null;
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

/** The caption track the app loaded for [videoId]; YouTube's own captions follow the same track. */
internal data class CaptionTrackTarget(
    val videoId: String,
    val languageCode: String,
    val generated: Boolean,
)

/**
 * Keeps YouTube's own caption track in the spoken language. YouTube remembers the last caption
 * language across videos, so after an English video a Japanese one keeps English captions and the
 * live word highlight (which reads those captions) stops matching the app's transcript.
 *
 * With a [target] for the current video the page shows the app's track; without one (transcript
 * still loading or unavailable) it applies the provider's rule in JavaScript: the auto-generated
 * track's language, then the audio track's language; creator captions beat auto-generated ones.
 * Captions that are off stay off, and a language the user picks on the page is not overridden.
 * [WEB_PLAYBACK_SNAPSHOT_SCRIPT] calls `run()` on every poll so late-loading tracks are handled.
 */
internal fun webCaptionTrackSyncScript(target: CaptionTrackTarget?): String {
    // Fixed key order (Android's JSONObject keeps insertion order, the JVM's does not).
    val targetLiteral =
        target?.let {
            "{\"videoId\":${JSONObject.quote(it.videoId)},\"languageCode\":${JSONObject.quote(it.languageCode)}," +
                "\"generated\":${it.generated}}"
        } ?: "null"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          const key = '$CAPTION_TRACK_SYNC_STATE_KEY';
          const sync = window[key] || (window[key] = { key: null, matched: false, attempts: 0 });
          sync.target = $targetLiteral;
          $CAPTION_TRACK_SYNC_FUNCTIONS
          sync.run();
          return true;
        })();
        """.trimIndent()
}

/** `sync.*` helpers for [webCaptionTrackSyncScript]; `sync.run()` is also called by every snapshot poll. */
private val CAPTION_TRACK_SYNC_FUNCTIONS =
    """
    sync.trustedOrigin = function() {
      const currentHost = window.location.hostname.toLowerCase().replace(/\.$/, '');
      return window.location.protocol === 'https:' &&
        (currentHost === 'youtube.com' || currentHost.endsWith('.youtube.com'));
    };
    sync.player = function() {
      const player = document.getElementById('movie_player');
      return player && typeof player.getOption === 'function' &&
        typeof player.setOption === 'function' ? player : null;
    };
    sync.base = function(code) { return String(code || '').split('-')[0].toLowerCase(); };
    sync.isGenerated = function(track) {
      return !!track && (track.kind === 'asr' || String(track.vssId || track.vss_id || '').indexOf('a.') === 0);
    };
    sync.spokenLanguage = function(renderer, tracks) {
      const audioTracks = Array.isArray(renderer.audioTracks) ? renderer.audioTracks : [];
      const audio = audioTracks[renderer.defaultAudioTrackIndex || 0] || null;
      const indices = audio && Array.isArray(audio.captionTrackIndices) ? audio.captionTrackIndices : [];
      const generated = indices.map(function(index) { return tracks[index]; }).find(sync.isGenerated) ||
        tracks.find(sync.isGenerated);
      if (generated) return sync.base(generated.languageCode);
      const audioLanguage = audio && audio.audioTrackId ? sync.base(String(audio.audioTrackId).split('.')[0]) : '';
      if (audioLanguage) return audioLanguage;
      const fallback = audio ? tracks[audio.defaultCaptionTrackIndex] : null;
      return fallback ? sync.base(fallback.languageCode) : '';
    };
    sync.choose = function(player) {
      let response = null;
      try { response = player.getPlayerResponse ? player.getPlayerResponse() : null; } catch (_) {}
      const renderer = response && response.captions && response.captions.playerCaptionsTracklistRenderer;
      const tracks = renderer && Array.isArray(renderer.captionTracks) ? renderer.captionTracks.filter(Boolean) : [];
      const videoId = response && response.videoDetails ? response.videoDetails.videoId : null;
      if (!tracks.length || !videoId) return null;
      const target = sync.target && sync.target.videoId === videoId ? sync.target : null;
      const language = target ? target.languageCode : sync.spokenLanguage(renderer, tracks);
      if (!language) return null;
      const generated = target ? target.generated : false;
      const same = tracks.filter(function(track) { return sync.base(track.languageCode) === sync.base(language); });
      const track = same.find(function(item) { return item.languageCode === language && sync.isGenerated(item) === generated; }) ||
        same.find(function(item) { return sync.isGenerated(item) === generated; }) ||
        same.find(function(item) { return !sync.isGenerated(item); }) || same[0];
      return track ? { videoId: videoId, track: track } : null;
    };
    sync.optionTrack = function(player, track) {
      try {
        const list = player.getOption('captions', 'tracklist', { includeAsr: true });
        const match = Array.isArray(list) && list.find(function(item) {
          return item && item.languageCode === track.languageCode && sync.isGenerated(item) === sync.isGenerated(track);
        });
        if (match) return match;
      } catch (_) {}
      return track;
    };
    sync.run = function() {
      if (!sync.trustedOrigin()) return 'untrusted';
      const player = sync.player();
      if (!player) return 'pending';
      const choice = sync.choose(player);
      if (!choice) return 'none';
      let active = null;
      try { active = player.getOption('captions', 'track'); } catch (_) {}
      if (!active || typeof active !== 'object' || !Object.keys(active).length) return 'off';
      const wanted = choice.track;
      const choiceKey = choice.videoId + '|' + wanted.languageCode + '|' + sync.isGenerated(wanted);
      if (!active.translationLanguage && active.languageCode === wanted.languageCode &&
          sync.isGenerated(active) === sync.isGenerated(wanted)) {
        sync.key = choiceKey;
        sync.matched = true;
        return 'matched';
      }
      // Once the page showed our choice, a different track is the user's own pick.
      if (sync.key === choiceKey && (sync.matched || sync.attempts >= 5)) return 'kept';
      if (sync.key !== choiceKey) {
        sync.key = choiceKey;
        sync.matched = false;
        sync.attempts = 0;
      }
      sync.attempts += 1;
      try {
        if (typeof player.loadModule === 'function') player.loadModule('captions');
        player.setOption('captions', 'track', sync.optionTrack(player, wanted));
      } catch (_) {}
      return 'switched';
    };
    """.trim()

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
        if (!isYouTubeWebUrl(json.optString("url"))) return@runCatching null
        val liveCaption =
            json.optJSONObject("liveCaption")?.let { live ->
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
                        videoId = live.optString("videoId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) },
                        languageCode = live.optString("languageCode").takeIf { it.length in 2..35 && it != "null" },
                    )
                } else {
                    null
                }
            }
        WebPlaybackSnapshot(
            url = json.getString("url"),
            currentSecond =
                if (json.isNull("currentSecond")) {
                    null
                } else {
                    json.getDouble("currentSecond").toFloat()
                },
            paused = json.optBoolean("paused", false),
            sampledAtEpochMs = json.optLong("sampledAtEpochMs", 0),
            playbackRate = json.optDouble("playbackRate", 1.0),
            seeking = json.optBoolean("seeking", false),
            buffering = json.optBoolean("buffering", false),
            sessionId = json.optString("sessionId", ""),
            controlsVisible = json.optBoolean("controlsVisible", false),
            nativeDialogVisible = json.optBoolean("nativeDialogVisible", false),
            liveCaption = liveCaption,
        )
    }.getOrNull()
}

internal fun webPauseScript(): String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
      if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      clearInterval(window.__dualSubClipTimer);
      document.querySelectorAll('video').forEach(function(video) { video.pause(); });
      return true;
    })();
    """.trimIndent()

internal fun webClipReplayScript(
    videoId: String,
    startMs: Long,
    endMs: Long,
): String {
    require(
        com.kienhoang.dualsubreplay.data
            .validClipRange(videoId, startMs, endMs),
    )
    return """
        (function() {
          const expected = '$videoId';
          const valid = function() {
            const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
            const url = new URL(window.location.href);
            const id = url.searchParams.get('v') || url.pathname.split('/')[2];
            return window.location.protocol === 'https:' && (host === 'youtube.com' || host.endsWith('.youtube.com')) && id === expected;
          };
          if (!valid()) return false;
          clearInterval(window.__dualSubClipTimer);
          let started = false;
          const deadline = Date.now() + 30000;
          window.__dualSubClipTimer = setInterval(function() {
            if (!valid()) { clearInterval(window.__dualSubClipTimer); return; }
            const video = document.querySelector('video');
            if (!started && Date.now() > deadline) { clearInterval(window.__dualSubClipTimer); return; }
            if (!video || video.readyState < 1 || document.querySelector('.ad-showing')) return;
            if (!started) {
              started = true;
              video.currentTime = ${startMs / 1000.0};
              const promise = video.play();
              if (promise && promise.catch) promise.catch(function() { clearInterval(window.__dualSubClipTimer); });
            } else if (video.ended || video.currentTime >= ${endMs / 1000.0} || video.currentTime < ${startMs / 1000.0} - 1) {
              video.pause(); clearInterval(window.__dualSubClipTimer);
            }
          }, 50);
          return true;
        })();
        """.trimIndent()
}
