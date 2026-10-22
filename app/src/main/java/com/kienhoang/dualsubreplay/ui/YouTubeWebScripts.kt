package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.MAX_PAGE_TRANSCRIPT_LENGTH
import com.kienhoang.dualsubreplay.data.PageCaptionTarget
import com.kienhoang.dualsubreplay.data.PageTranscriptReply
import com.kienhoang.dualsubreplay.data.PageTranslationReply
import com.kienhoang.dualsubreplay.data.PageTranslationTarget
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
            const player = state.player();
            const activeTrack = state.activeCaptionTrack(player);
            // Every snapshot poll lands here; once captions are on, skip the document-wide button search.
            if (activeTrack && state.captionWasEnabled !== null) return;
            const button = state.captionButton();
            const pressed = !!button && button.getAttribute('aria-pressed') === 'true';
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
            // Timestamp new captions from the same media clock as playback snapshots
            // (fullscreen custom video surfaces can freeze the page element's frames).
            const mediaSecond = video && Number.isFinite(video.currentTime)
              ? video.currentTime
              : null;
            state.text = text;
            state.captionMediaSecond = mediaSecond;
            state.revision += 1;
          };

          // YouTube rewrites its time display, feed and comments all the time; only caption
          // changes need a reading. Anything missed here is still read by the next snapshot poll.
          state.captionSelector = '.ytp-caption-segment, .ytp-caption-window-container, .caption-window';
          state.touchesCaptions = function(records) {
            const related = function(node) {
              return !!node && node.nodeType === 1 &&
                (node.matches(state.captionSelector) || !!node.querySelector(state.captionSelector));
            };
            return records.some(function(record) {
              const target = record.target.nodeType === 1 ? record.target : record.target.parentElement;
              return (!!target && !!target.closest(state.captionSelector)) ||
                Array.prototype.some.call(record.addedNodes, related) ||
                Array.prototype.some.call(record.removedNodes, related);
            });
          };
          state.observer = new MutationObserver(function(records) {
            if (state.touchesCaptions(records)) state.recordCaption();
          });
          state.observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
            characterData: true
          });
          window[key] = state;
          state.ensureCaptions();
          state.recordCaption();
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
    // Same order as spokenCaptionLanguage in YouTubeCaptionProvider.kt, without the learning language.
    sync.scripts = [['Hangul', ['ko']], ['Han', ['zh', 'ja']], ['Thai', ['th']], ['Arabic', ['ar', 'fa', 'ur']],
      ['Hebrew', ['he', 'iw']], ['Devanagari', ['hi', 'mr', 'ne']], ['Cyrillic', ['ru', 'uk', 'bg', 'sr']], ['Greek', ['el']]];
    sync.scriptLanguages = function(text, mustDominate) {
      const value = String(text || '');
      const count = function(script) {
        const found = value.match(new RegExp('\\p{Script=' + script + '}', 'gu'));
        return found ? found.length : 0;
      };
      const kana = count('Hiragana') + count('Katakana');
      let best = kana >= 2 ? ['ja'] : null;
      let letters = kana;
      if (!best) {
        sync.scripts.forEach(function(entry) {
          const found = count(entry[0]);
          if (found > letters) { letters = found; best = entry[1]; }
        });
      }
      if (!best || letters < 2 || (mustDominate && letters <= count('Latin'))) return [];
      return best;
    };
    sync.titleLanguage = function(details, tracks) {
      const available = tracks.map(function(track) { return sync.base(track.languageCode); });
      let candidates = sync.scriptLanguages(details && details.title, false);
      if (!candidates.length) candidates = sync.scriptLanguages(details && details.shortDescription, true);
      return candidates.find(function(language) { return available.indexOf(language) >= 0; }) || '';
    };
    sync.spokenLanguage = function(renderer, tracks, details) {
      const audioTracks = Array.isArray(renderer.audioTracks) ? renderer.audioTracks : [];
      const audio = audioTracks[renderer.defaultAudioTrackIndex || 0] || null;
      const indices = audio && Array.isArray(audio.captionTrackIndices) ? audio.captionTrackIndices : [];
      const generated = indices.map(function(index) { return tracks[index]; }).find(sync.isGenerated) ||
        tracks.find(sync.isGenerated);
      if (generated) return sync.base(generated.languageCode);
      const titled = sync.titleLanguage(details, tracks);
      if (titled) return titled;
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
      // run() comes with every snapshot poll; the spoken language scans the title and description
      // with a dozen script patterns, so work it out once per video and track list.
      const spokenKey = videoId + '|' + tracks.length;
      if (!target && sync.spokenFor !== spokenKey) {
        sync.spoken = sync.spokenLanguage(renderer, tracks, response.videoDetails);
        sync.spokenFor = spokenKey;
      }
      const language = target ? target.languageCode : sync.spoken;
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

/** Sets the page video's speed (0.25 to 2); the assistant's playback speed action. */
internal fun webPlaybackSpeedScript(rate: Float): String {
    val safeRate = rate.takeIf { it.isFinite() }?.coerceIn(MIN_WEB_PLAYBACK_SPEED, MAX_WEB_PLAYBACK_SPEED) ?: 1f
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          document.querySelectorAll('video').forEach(function(video) { video.playbackRate = $safeRate; });
          return true;
        })();
        """.trimIndent()
}

internal const val MIN_WEB_PLAYBACK_SPEED = 0.25f
internal const val MAX_WEB_PLAYBACK_SPEED = 2f

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

internal const val CAPTION_CAPTURE_STATE_KEY = "__dualSubCaptionCaptureV2"

/**
 * Keeps a copy of every caption track the YouTube page player downloads, so the app can build the
 * full transcript from the very document the player received: the player's request already carries
 * the page's cookies and proof-of-origin token (`pot`), which YouTube now demands and the app's own
 * lookup cannot supply. Added at document start (and again by [webPageTranscriptScript] where that
 * is unsupported). It only reads `/api/timedtext` replies from YouTube hosts, skips auto-translated
 * tracks and replies over 8 MiB, keeps the last eight tracks, and never changes a request or reply.
 * It also keeps the `/youtubei/v1/get_transcript` reply of YouTube's own transcript panel (the last
 * four videos), which [webPageTranscriptScript] opens when the player never downloads captions.
 */
internal val WEB_CAPTION_CAPTURE_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return;
      const key = '$CAPTION_CAPTURE_STATE_KEY';
      if (window[key]) return;
      const capture = window[key] = { tracks: {}, order: [], transcripts: {}, transcriptOrder: [], translations: {}, translationOrder: [] };
      capture.pageVideo = function() {
        try {
          const address = new URL(window.location.href);
          return address.searchParams.get('v') ||
            (address.pathname.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})/) || [])[1] || null;
        } catch (_) {
          return null;
        }
      };
      capture.panelReply = function(value) {
        try {
          const url = new URL(String(value), window.location.href);
          const urlHost = url.hostname.toLowerCase().replace(/\.$/, '');
          if (url.protocol !== 'https:' || !(urlHost === 'youtube.com' || urlHost.endsWith('.youtube.com')) ||
              url.pathname !== '/youtubei/v1/get_transcript') return null;
          const videoId = capture.pageVideo();
          return videoId ? { panel: videoId } : null;
        } catch (_) {
          return null;
        }
      };
      capture.watch = function(value) {
        const url = capture.timedText(value) || capture.timedText(value, true);
        return url ? { url: url } : capture.panelReply(value);
      };
      // An original track's address, or with [translated] one YouTube translates (it carries tlang).
      capture.timedText = function(value, translated) {
        try {
          const url = new URL(String(value), window.location.href);
          const urlHost = url.hostname.toLowerCase().replace(/\.$/, '');
          if (url.protocol !== 'https:' || !(urlHost === 'youtube.com' || urlHost.endsWith('.youtube.com')) ||
              url.pathname !== '/api/timedtext' || !url.searchParams.get('v') ||
              !url.searchParams.get('tlang') !== !translated) return null;
          return url;
        } catch (_) {
          return null;
        }
      };
      capture.keep = function(entry, text) {
        if (typeof text !== 'string' || !text.trim() || text.length > $MAX_PAGE_TRANSCRIPT_LENGTH) return;
        if (entry.panel) {
          if (!capture.transcripts[entry.panel]) capture.transcriptOrder.push(entry.panel);
          capture.transcripts[entry.panel] = text;
          while (capture.transcriptOrder.length > 4) delete capture.transcripts[capture.transcriptOrder.shift()];
          return;
        }
        const url = entry.url;
        const track = {
          videoId: url.searchParams.get('v'),
          languageCode: url.searchParams.get('lang') || '',
          generated: url.searchParams.get('kind') === 'asr',
          text: text
        };
        const id = track.videoId + '|' + track.languageCode + '|' + track.generated;
        const translatedTo = url.searchParams.get('tlang');
        if (translatedTo) {
          // YouTube's own translation of that track, kept apart so it is never taken for the original.
          const translation = id + '|' + translatedTo;
          if (!capture.translations[translation]) capture.translationOrder.push(translation);
          capture.translations[translation] = text;
          while (capture.translationOrder.length > 4) delete capture.translations[capture.translationOrder.shift()];
          return;
        }
        if (!capture.tracks[id]) capture.order.push(id);
        capture.tracks[id] = track;
        while (capture.order.length > 8) delete capture.tracks[capture.order.shift()];
      };
      try {
        const open = XMLHttpRequest.prototype.open;
        const send = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, address) {
          this.__dualSubTimedText = capture.watch(address);
          return open.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
          const request = this;
          const url = request.__dualSubTimedText;
          if (url) {
            request.addEventListener('load', function() {
              try {
                if (request.status < 200 || request.status >= 300) return;
                const type = request.responseType;
                if (type === '' || type === 'text') capture.keep(url, request.responseText);
                else if (type === 'json' && request.response) capture.keep(url, JSON.stringify(request.response));
                else if (type === 'arraybuffer' && request.response) capture.keep(url, new TextDecoder().decode(request.response));
              } catch (_) {}
            });
          }
          return send.apply(this, arguments);
        };
      } catch (_) {}
      try {
        const pageFetch = window.fetch;
        if (typeof pageFetch === 'function') {
          window.fetch = function(input) {
            const reply = pageFetch.apply(window, arguments);
            try {
              const url = capture.watch(input && typeof input === 'object' && 'url' in input ? input.url : input);
              if (url) {
                reply.then(function(response) {
                  if (response && response.ok) response.clone().text().then(function(text) { capture.keep(url, text); }, function() {});
                }, function() {});
              }
            } catch (_) {}
            return reply;
          };
        }
      } catch (_) {}
    })();
    """.trimIndent()

/**
 * Returns the caption part of the page player's response (tracks, audio tracks, title) for
 * `PageTranscriptSource`, or null before the player has one. When the player's details list no
 * track, which happens while YouTube's own player shows captions, the tracks come from the player's
 * captions menu and from the caption tracks the player already downloaded (kept by
 * [WEB_CAPTION_CAPTURE_SCRIPT], which this script also makes sure is installed). `found` counts each
 * source for the app's diagnostics. `ready` is false while the page's `#player` says it is still
 * loading or not playable, the signal LingoTube waits for before it reads the track list (a page
 * without those attributes counts as ready). It changes nothing on the page.
 */
internal val WEB_PAGE_CAPTION_TRACKS_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
      $WEB_CAPTION_CAPTURE_SCRIPT
      const capture = window['$CAPTION_CAPTURE_STATE_KEY'];
      if (!capture) return null;
      const player = document.getElementById('movie_player');
      const pageVideo = capture.pageVideo();
      const listedTracks = function(response) {
        const renderer = response.captions && response.captions.playerCaptionsTracklistRenderer;
        return renderer && Array.isArray(renderer.captionTracks) ? renderer.captionTracks : [];
      };
      const responses = [];
      try { if (player && typeof player.getPlayerResponse === 'function') responses.push(player.getPlayerResponse()); } catch (_) {}
      responses.push(window.ytInitialPlayerResponse || null);
      const known = responses.filter(function(item) { return item && item.videoDetails && item.videoDetails.videoId; });
      const matching = known.filter(function(item) { return item.videoDetails.videoId === pageVideo; });
      const response = matching.find(function(item) { return listedTracks(item).length > 0; }) || matching[0] || known[0];
      if (!response) return null;
      const details = response.videoDetails;
      const renderer = (response.captions && response.captions.playerCaptionsTracklistRenderer) || {};
      // The page resolves a relative track address against itself; the app only takes absolute ones.
      const listed = listedTracks(response).map(function(track) {
        const copy = Object.assign({}, track);
        try { if (copy.baseUrl) copy.baseUrl = new URL(String(copy.baseUrl), window.location.href).toString(); } catch (_) { copy.baseUrl = ''; }
        return copy;
      });
      let menu = [];
      try {
        const data = player && typeof player.getVideoData === 'function' ? player.getVideoData() : null;
        const list = data && data.video_id === details.videoId && typeof player.getOption === 'function' ?
          player.getOption('captions', 'tracklist', { includeAsr: true }) : null;
        if (Array.isArray(list)) menu = list.filter(function(item) { return item && item.languageCode; }).map(function(item) {
          return {
            languageCode: String(item.languageCode),
            kind: item.kind === 'asr' ? 'asr' : '',
            vssId: String(item.vss_id || item.vssId || ''),
            name: { simpleText: String(item.displayName || item.languageName || '') },
            baseUrl: ''
          };
        });
      } catch (_) {}
      const downloaded = capture.order.map(function(id) { return capture.tracks[id]; }).filter(function(track) {
        return track && track.videoId === details.videoId && track.languageCode;
      }).map(function(track) {
        return { languageCode: track.languageCode, kind: track.generated ? 'asr' : '', name: { simpleText: '' }, baseUrl: '' };
      });
      let tracks = listed;
      if (!tracks.length) {
        const seen = {};
        tracks = menu.concat(downloaded).filter(function(track) {
          const id = track.languageCode + '|' + (track.kind === 'asr' || String(track.vssId || '').indexOf('a.') === 0);
          if (seen[id]) return false;
          seen[id] = true;
          return true;
        });
      }
      const holder = document.getElementById('player');
      const loading = holder && holder.getAttribute('loading');
      const playable = holder && holder.getAttribute('playable');
      return JSON.stringify({
        ready: (!loading || loading === 'false') && (!playable || playable === 'true'),
        videoDetails: { videoId: details.videoId, title: details.title || '', shortDescription: details.shortDescription || '' },
        captions: { playerCaptionsTracklistRenderer: {
          captionTracks: tracks,
          audioTracks: renderer.audioTracks || [],
          defaultAudioTrackIndex: renderer.defaultAudioTrackIndex || 0
        } },
        found: {
          details: listed.length,
          menu: menu.length,
          downloaded: downloaded.length,
          playability: String((response.playabilityStatus && response.playabilityStatus.status) || '')
        }
      });
    })();
    """.trimIndent()

internal const val PAGE_TRANSCRIPT_STATE_KEY = "__dualSubPageTranscriptV2"

/** How long one attempt may switch the page's captions on to make the player download the track. */
internal const val PAGE_CAPTION_SWITCH_MS = 15_000

/**
 * Gets [target]'s caption track from the page and reports `working` (with the step it is on) or
 * `done` (with the document and its track) as JSON text. Each attempt is one job: a retry is a new
 * attempt and starts again, and older jobs are dropped. The job first fetches the track's own
 * address from the page, as `json3` and then exactly as YouTube gave it (LingoTube's request), which
 * works where YouTube does not demand a proof-of-origin token. Then it
 * waits for the page player to download the track (see [WEB_CAPTION_CAPTURE_SCRIPT]); for the first
 * [PAGE_CAPTION_SWITCH_MS] it switches the page's captions to that track so the player does so, and
 * switches them back if nobody changed them since. After that it keeps watching, so captions the
 * player loads later (when the video plays, or live subtitles turn them on) still finish the job,
 * and, as the last resort, opens YouTube's own transcript panel (unfolding the description first
 * when its button is hidden there) and takes the panel's reply or its shown lines. A
 * [PageCaptionTarget.panelOnly] target, for a page that listed no track, goes to the panel at once.
 */
internal fun webPageTranscriptScript(target: PageCaptionTarget): String {
    // Fixed key order (Android's JSONObject keeps insertion order, the JVM's does not).
    val targetLiteral =
        "{\"videoId\":${JSONObject.quote(target.videoId)},\"languageCode\":${JSONObject.quote(target.languageCode)}," +
            "\"generated\":${target.generated},\"baseUrl\":${JSONObject.quote(target.baseUrl)},\"attempt\":\"${target.attempt}\"," +
            "\"panelOnly\":${target.panelOnly},\"pageToken\":${target.pageToken},\"tokenFirst\":${target.tokenFirst}}"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
          $WEB_CAPTION_CAPTURE_SCRIPT
          const capture = window['$CAPTION_CAPTURE_STATE_KEY'];
          if (!capture) return null;
          const target = $targetLiteral;
          const key = '$PAGE_TRANSCRIPT_STATE_KEY';
          const state = window[key] || (window[key] = { jobs: {} });
          $PAGE_TRANSCRIPT_FUNCTIONS
          $PAGE_TRANSCRIPT_PANEL_FUNCTIONS
          Object.keys(state.jobs).forEach(function(id) {
            if (id === target.attempt) return;
            state.restoreTrack(state.jobs[id]);
            delete state.jobs[id];
          });
          let job = state.jobs[target.attempt];
          if (!job) {
            job = state.jobs[target.attempt] = { status: 'working', step: 'page-address', started: Date.now() };
            state.start(job, target);
          }
          state.check(job, target);
          return JSON.stringify({
            status: job.status,
            step: job.step,
            text: job.status === 'done' ? job.text : null,
            languageCode: job.languageCode || null,
            generated: !!job.generated,
            panel: !!job.panel,
            token: job.token || null,
            via: job.via || null
          });
        })();
        """.trimIndent()
}

/** `state.*` helpers for [webPageTranscriptScript]. */
private val PAGE_TRANSCRIPT_FUNCTIONS =
    """
    state.player = function() {
      const player = document.getElementById('movie_player');
      return player && typeof player.getOption === 'function' &&
        typeof player.setOption === 'function' ? player : null;
    };
    state.usable = function(text) {
      return typeof text === 'string' && text.trim().length > 0 && text.length <= $MAX_PAGE_TRANSCRIPT_LENGTH;
    };
    state.cues = function(text) {
      return state.usable(text) && /"segs"|<text[\s>]|<p[\s>]/.test(text);
    };
    state.download = function(address, target, asGiven, token) {
      const url = capture.timedText(address);
      if (!url || url.searchParams.get('v') !== target.videoId) return Promise.resolve('');
      let options = {};
      if (!asGiven) {
        // The page's own host keeps the request same-origin, so the page's cookies go with it.
        url.host = window.location.host;
        url.searchParams.set('lang', target.languageCode);
        if (target.generated) url.searchParams.set('kind', 'asr'); else url.searchParams.delete('kind');
        url.searchParams.set('fmt', 'json3');
        options = { credentials: 'include' };
      }
      // YouTube's own translation of the track, as LingoTube asks for it: the same address with tlang.
      if (target.translateTo) url.searchParams.set('tlang', target.translateTo);
      // The page's own proof-of-origin token (Study tools, beta), as the web player sends it.
      if (token) {
        url.searchParams.set('c', 'WEB');
        url.searchParams.set('pot', token);
      }
      // As given, the address goes out exactly as the page player (and LingoTube) would send it.
      return fetch(url.toString(), options)
        .then(function(response) { return response.ok ? response.text() : ''; })
        .catch(function() { return ''; });
    };
    state.captured = function(target, sameLanguage) {
      const exact = capture.tracks[target.videoId + '|' + target.languageCode + '|' + target.generated];
      if (exact && state.usable(exact.text)) return exact;
      if (!sameLanguage) return null;
      const base = function(code) { return String(code || '').split('-')[0].toLowerCase(); };
      for (let index = capture.order.length - 1; index >= 0; index--) {
        const track = capture.tracks[capture.order[index]];
        if (track && track.videoId === target.videoId && state.usable(track.text) &&
            (target.panelOnly || base(track.languageCode) === base(target.languageCode))) return track;
      }
      return null;
    };
    state.finish = function(job, track) {
      state.restoreTrack(job);
      job.status = 'done';
      job.text = track.text;
      job.languageCode = track.languageCode;
      job.generated = track.generated;
      job.panel = !!track.panel;
      if (!job.via) job.via = track.panel ? 'panel' : 'player';
    };
    state.showTrack = function(job, target) {
      const player = state.player();
      if (!player || job.switched) return;
      let active = null;
      try { active = player.getOption('captions', 'track'); } catch (_) {}
      job.previous = active && typeof active === 'object' && Object.keys(active).length ? active : null;
      let track = { languageCode: target.languageCode };
      if (target.generated) track.kind = 'asr';
      try {
        if (typeof player.loadModule === 'function') player.loadModule('captions');
        const list = player.getOption('captions', 'tracklist', { includeAsr: true });
        const match = Array.isArray(list) && list.find(function(item) {
          return item && item.languageCode === target.languageCode && (item.kind === 'asr') === target.generated;
        });
        if (match) track = match;
      } catch (_) {}
      if (target.translateTo) {
        track = Object.assign({}, track, { translationLanguage: { languageCode: target.translateTo, languageName: target.translateTo } });
      }
      try {
        player.setOption('captions', 'track', track);
        job.switched = true;
        job.shownLanguage = target.languageCode;
      } catch (_) {}
    };
    state.restoreTrack = function(job) {
      const player = state.player();
      if (!player || !job.switched) return;
      job.switched = false;
      let active = null;
      try { active = player.getOption('captions', 'track'); } catch (_) {}
      // Captions changed since (by live subtitles or the user) stay as they are.
      if (!active || active.languageCode !== job.shownLanguage) return;
      try { player.setOption('captions', 'track', job.previous || {}); } catch (_) {}
    };
    state.start = function(job, target) {
      // On, or Automatic once a token was needed: the token first, then the plain address.
      if (target.tokenFirst) {
        state.withToken(job, target, function() { state.plain(job, target, null); });
      } else {
        state.plain(job, target, function() { state.withToken(job, target, null); });
      }
    };
    state.plain = function(job, target, otherwise) {
      state.download(target.baseUrl, target, false).then(function(text) {
        // json3 first, for its word timings; then the address as YouTube gave it.
        return state.cues(text) ? text : state.download(target.baseUrl, target, true);
      }).then(function(text) {
        job.addressTried = true;
        if (job.status === 'working' && state.cues(text)) {
          job.via = 'address';
          state.finish(job, { text: text, languageCode: target.languageCode, generated: target.generated });
        } else if (otherwise) {
          otherwise();
        }
      });
    };
    state.withToken = function(job, target, otherwise) {
      let gaveUp = false;
      const giveUp = function() {
        if (gaveUp || job.status !== 'working') return;
        gaveUp = true;
        if (otherwise) otherwise();
      };
      if (!target.pageToken || job.status !== 'working') return giveUp();
      const tokens = window['$PAGE_TOKEN_STATE_KEY'];
      if (!tokens || typeof tokens.mint !== 'function') {
        job.token = 'not-ready';
        return giveUp();
      }
      job.token = 'minting';
      let tries = 0;
      const attempt = function() {
        if (job.status !== 'working' || tries >= $PAGE_TOKEN_TRIES) return;
        tries++;
        const last = tries >= $PAGE_TOKEN_TRIES;
        tokens.mint(target.videoId).then(function(token) {
          if (job.status !== 'working') return '';
          if (job.token === 'minting') job.token = 'sent';
          return state.download(target.baseUrl, target, false, token);
        }).then(function(text) {
          if (job.status !== 'working') return;
          if (state.cues(text)) {
            job.token = 'worked';
            job.via = 'token';
            state.finish(job, { text: text, languageCode: target.languageCode, generated: target.generated });
          } else if (last) {
            job.token = 'empty';
            giveUp();
          }
        }, function() {
          // No token could be made: the next tries would only ask BotGuard again.
          job.token = tokens.problem || 'mint-failed';
          tries = $PAGE_TOKEN_TRIES;
          giveUp();
        });
        // Like the web player, a slow answer does not hold up the next request.
        setTimeout(attempt, $PAGE_TOKEN_RETRY_MS);
      };
      attempt();
    };
    state.check = function(job, target) {
      if (job.status !== 'working') return;
      const elapsed = Date.now() - job.started;
      const track = state.captured(target, target.panelOnly || elapsed > $PAGE_CAPTION_SWITCH_MS);
      if (track) {
        state.closePanel(job);
        state.finish(job, track);
        return;
      }
      if (target.panelOnly || elapsed > $PAGE_CAPTION_SWITCH_MS) {
        state.restoreTrack(job);
        state.panel(job, target);
        return;
      }
      if (!job.addressTried && elapsed < 5000) return;
      if (state.player()) state.showTrack(job, target);
      job.step = state.player() ? 'waiting-for-captions' : 'no-player';
    };
    """.trim()

/** `state.*` helpers for [webPageTranscriptScript] that use YouTube's own transcript panel. */
private val PAGE_TRANSCRIPT_PANEL_FUNCTIONS =
    """
    state.first = function(selectors) {
      for (let index = 0; index < selectors.length; index++) {
        const element = document.querySelector(selectors[index]);
        if (element) return element;
      }
      return null;
    };
    state.panelLines = function() {
      const segments = document.querySelectorAll($PANEL_SEGMENT_SELECTORS);
      const lines = [];
      segments.forEach(function(segment) {
        const stamp = segment.querySelector('.segment-timestamp, [class*="timestamp"]');
        const all = (segment.textContent || '').replace(/\s+/g, ' ').trim();
        const found = ((stamp ? stamp.textContent : all) || '').match(/\d{1,2}(?::\d{2}){1,2}/);
        if (!found) return;
        const startMs = found[0].split(':').reduce(function(total, part) { return total * 60 + Number(part); }, 0) * 1000;
        const textElement = segment.querySelector('.segment-text, [class*="segment-text"]');
        let text = textElement ? textElement.textContent : all;
        if (!textElement) text = String(text).replace(stamp ? stamp.textContent : found[0], '');
        text = String(text || '').replace(/\s+/g, ' ').trim();
        if (text) lines.push({ startMs: startMs, text: text });
      });
      return lines;
    };
    state.closePanel = function(job) {
      if (!job.panelOpened) return;
      job.panelOpened = false;
      // Only a close button inside the transcript's own panel; anything else is left as it is.
      const segment = document.querySelector($PANEL_SEGMENT_SELECTORS);
      const panel = segment && segment.closest('[class*="engagement-panel"], ytm-engagement-panel-section-list-renderer, ytd-engagement-panel-section-list-renderer');
      const close = panel && panel.querySelector('#visibility-button button, button[class*="close"], [class*="close-button"] button');
      try { if (close) close.click(); } catch (_) {}
    };
    state.panel = function(job, target) {
      const kept = capture.transcripts[target.videoId];
      if (state.usable(kept)) {
        state.closePanel(job);
        state.finish(job, { text: kept, languageCode: target.languageCode, generated: target.generated, panel: true });
        return;
      }
      const lines = state.panelLines();
      // Shown lines are taken once their count stops growing between two polls.
      if (lines.length && lines.length === job.panelLines) {
        state.closePanel(job);
        state.finish(job, {
          text: JSON.stringify({ panelLines: lines }),
          languageCode: target.languageCode,
          generated: target.generated,
          panel: true
        });
        return;
      }
      job.panelLines = lines.length;
      if (lines.length) {
        job.step = 'panel-reading';
        return;
      }
      const now = Date.now();
      if ((job.panelTries || 0) >= $PANEL_OPEN_TRIES) {
        job.step = 'panel-missing';
        return;
      }
      // An opened panel gets time to load before the button is pressed again (it may toggle).
      if (job.panelTried && now - job.panelTried < (job.panelOpened ? 8000 : 2500)) return;
      job.panelTried = now;
      job.panelTries = (job.panelTries || 0) + 1;
      const button = state.first([
        'ytm-video-description-transcript-section-renderer button',
        'ytd-video-description-transcript-section-renderer button',
        '[class*="transcript-section"] button'
      ]);
      if (button) {
        button.click();
        job.panelOpened = true;
        job.step = 'panel-opening';
        return;
      }
      // The panel's button sits in the video's description, which may still be folded.
      const more = state.first([
        'ytm-slim-video-information-renderer .slim-video-information-show-more',
        '.slim-video-information-show-more',
        'ytm-slim-video-information-renderer [role="button"]',
        'ytm-slim-video-information-renderer button',
        '#description-inline-expander #expand',
        'tp-yt-paper-button#expand'
      ]);
      if (more) {
        more.click();
        job.step = 'panel-description';
        return;
      }
      job.step = 'panel-missing';
    };
    """.trim()

/** Transcript line elements of YouTube's transcript panel (mobile, desktop and the newer view). */
private const val PANEL_SEGMENT_SELECTORS =
    "'transcript-segment-view-model, ytm-transcript-segment-renderer, ytd-transcript-segment-renderer'"

/** How often a job tries to open the transcript panel (about 2.5 s apart) before giving up. */
internal const val PANEL_OPEN_TRIES = 4

internal const val PAGE_TRANSLATION_STATE_KEY = "__dualSubPageTranslationV1"

/**
 * Gets YouTube's own translation of [target]'s caption track from the page, as LingoTube does, and
 * reports `working` (with its step), `done` (with the document) or `unavailable` (YouTube does not
 * translate this track into that language) as JSON text. The job finds the track in the page
 * player's details, fetches its address with `tlang` from the page (as `json3`, then as YouTube gave
 * it), and otherwise, for the first [PAGE_CAPTION_SWITCH_MS], switches the page's captions to the
 * translated track so the player downloads it (kept by [WEB_CAPTION_CAPTURE_SCRIPT]) and switches
 * them back. One job per attempt, apart from the transcript's jobs.
 */
internal fun webPageTranslationScript(target: PageTranslationTarget): String {
    // Fixed key order (Android's JSONObject keeps insertion order, the JVM's does not).
    val targetLiteral =
        "{\"videoId\":${JSONObject.quote(target.videoId)},\"languageCode\":${JSONObject.quote(target.languageCode)}," +
            "\"generated\":${target.generated},\"translateTo\":${JSONObject.quote(target.translateTo)},\"attempt\":\"${target.attempt}\"}"
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
          $WEB_CAPTION_CAPTURE_SCRIPT
          const capture = window['$CAPTION_CAPTURE_STATE_KEY'];
          if (!capture) return null;
          const target = $targetLiteral;
          const key = '$PAGE_TRANSLATION_STATE_KEY';
          const state = window[key] || (window[key] = { jobs: {} });
          $PAGE_TRANSCRIPT_FUNCTIONS
          $PAGE_TRANSLATION_FUNCTIONS
          Object.keys(state.jobs).forEach(function(id) {
            if (id === target.attempt) return;
            state.restoreTrack(state.jobs[id]);
            delete state.jobs[id];
          });
          let job = state.jobs[target.attempt];
          if (!job) job = state.jobs[target.attempt] = { status: 'working', step: 'no-track', started: Date.now() };
          state.translationCheck(job, target);
          return JSON.stringify({ status: job.status, step: job.step, text: job.status === 'done' ? job.text : null });
        })();
        """.trimIndent()
}

/** `state.*` helpers for [webPageTranslationScript], on top of [PAGE_TRANSCRIPT_FUNCTIONS]. */
private val PAGE_TRANSLATION_FUNCTIONS =
    """
    state.base = function(code) { return String(code || '').split('-')[0].toLowerCase(); };
    state.translationTrack = function(target) {
      const player = document.getElementById('movie_player');
      const responses = [];
      try { if (player && typeof player.getPlayerResponse === 'function') responses.push(player.getPlayerResponse()); } catch (_) {}
      responses.push(window.ytInitialPlayerResponse || null);
      for (let index = 0; index < responses.length; index++) {
        const response = responses[index];
        if (!response || !response.videoDetails || response.videoDetails.videoId !== target.videoId) continue;
        const renderer = response.captions && response.captions.playerCaptionsTracklistRenderer;
        const tracks = renderer && Array.isArray(renderer.captionTracks) ? renderer.captionTracks : [];
        const track = tracks.find(function(item) {
          return item && item.languageCode === target.languageCode && (item.kind === 'asr') === target.generated;
        }) || tracks.find(function(item) { return item && state.base(item.languageCode) === state.base(target.languageCode); });
        if (!track) continue;
        const languages = Array.isArray(renderer.translationLanguages) ? renderer.translationLanguages : [];
        const language = languages.find(function(item) { return item && item.languageCode === target.translateTo; }) ||
          languages.find(function(item) { return item && state.base(item.languageCode) === state.base(target.translateTo); });
        let address = '';
        try { if (track.baseUrl) address = new URL(String(track.baseUrl), window.location.href).toString(); } catch (_) {}
        return {
          address: address,
          translatable: track.isTranslatable !== false && (!languages.length || !!language),
          request: {
            videoId: target.videoId,
            languageCode: String(track.languageCode),
            generated: track.kind === 'asr',
            translateTo: language ? String(language.languageCode) : target.translateTo
          }
        };
      }
      return null;
    };
    state.translationStart = function(job, target) {
      const found = state.translationTrack(target);
      if (!found) return;
      if (!found.translatable) {
        job.status = 'unavailable';
        job.step = 'not-translatable';
        return;
      }
      job.request = found.request;
      job.step = 'page-address';
      state.download(found.address, found.request, false).then(function(text) {
        return state.cues(text) ? text : state.download(found.address, found.request, true);
      }).then(function(text) {
        job.addressTried = true;
        if (job.status === 'working' && state.cues(text)) {
          job.status = 'done';
          job.text = text;
        }
      });
    };
    state.translationCheck = function(job, target) {
      if (job.status !== 'working') return;
      if (!job.request) {
        state.translationStart(job, target);
        return;
      }
      const request = job.request;
      const kept = capture.translations[request.videoId + '|' + request.languageCode + '|' + request.generated + '|' + request.translateTo];
      if (state.cues(kept)) {
        state.restoreTrack(job);
        job.status = 'done';
        job.text = kept;
        return;
      }
      const elapsed = Date.now() - job.started;
      if (!job.addressTried && elapsed < 5000) return;
      if (elapsed > $PAGE_CAPTION_SWITCH_MS) {
        state.restoreTrack(job);
      } else if (state.player()) {
        state.showTrack(job, request);
      }
      job.step = state.player() ? 'waiting-for-captions' : 'no-player';
    };
    """.trim()

/** Reads what [webPageTranslationScript] returned through `evaluateJavascript`. */
internal fun parsePageTranslationReply(rawValue: String?): PageTranslationReply {
    val json = decodeScriptString(rawValue)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return PageTranslationReply.Working()
    val step = json.optString("step").takeIf { it.matches(Regex("[a-z-]{1,40}")) }
    val text = json.optString("text")
    return when {
        json.optString("status") == "unavailable" -> PageTranslationReply.Unavailable
        json.optString("status") == "done" && text.isNotBlank() -> PageTranslationReply.Done(text)
        else -> PageTranslationReply.Working(step)
    }
}

/** Reads what [webPageTranscriptScript] returned through `evaluateJavascript`. */
internal fun parsePageTranscriptReply(rawValue: String?): PageTranscriptReply {
    val json = decodeScriptString(rawValue)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return PageTranscriptReply.Working()
    val step = json.optString("step").takeIf { it.matches(Regex("[a-z-]{1,40}")) }
    // Android's org.json reads a JSON null as "null" through optString.
    val token = (json.opt("token") as? String)?.takeIf { it.matches(Regex("[a-z0-9-]{1,40}")) }
    val text = json.optString("text")
    if (json.optString("status") != "done" || text.isBlank()) return PageTranscriptReply.Working(step, token)
    return PageTranscriptReply.Done(
        text,
        json.optString("languageCode").takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*")) },
        json.optBoolean("generated"),
        panel = json.optBoolean("panel"),
        via = (json.opt("via") as? String)?.takeIf { it in PAGE_TRANSCRIPT_VIAS },
    )
}

/** How [webPageTranscriptScript] may say it got the document. */
private val PAGE_TRANSCRIPT_VIAS = setOf("address", "token", "player", "panel")

/** The string a page script returned through `evaluateJavascript` (which JSON-quotes it), or null. */
internal fun decodeScriptString(rawValue: String?): String? {
    if (rawValue.isNullOrBlank() || rawValue == "null") return null
    return runCatching { JSONTokener(rawValue).nextValue() as? String }.getOrNull()?.takeIf(String::isNotBlank)
}
