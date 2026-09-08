// Ported from highlight-dual-sub-app preview-16 (ecceeffbaeb52e495f3cf82c1d71e6916d61d872).
// Word-selection algorithms are retained; transport, language and lifecycle are adapted.
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.HighlightCaptionEngine = api;
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    try { api.install(window.DualSubCaptionBridge); } catch (_) {}
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const WORD_RE = /[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu;
  const DOM_WORD_SECONDS = 0.32;

  function normalizeText(value) {
    return String(value || '').replace(/\s+/g, ' ').trim();
  }

  function tokens(value) {
    return normalizeText(value).match(WORD_RE) || [];
  }

  function lowerTokens(value) {
    return tokens(value).map(word => word.toLocaleLowerCase());
  }

  function pickCaptionTrack(tracks, preferredLanguage) {
    const list = Array.isArray(tracks) ? tracks.filter(Boolean) : [];
    if (!list.length) return null;
    const preferred = String(preferredLanguage || 'en').toLowerCase();
    const languageMatches = list.filter(track => {
      const code = String(track.languageCode || '').toLowerCase();
      return code === preferred || code.startsWith(preferred + '-');
    });
    return languageMatches.find(track => track.kind === 'asr') ||
      languageMatches[0] ||
      list.find(track => track.kind === 'asr') ||
      list[0];
  }

  function parsePlayerResponse(value) {
    if (!value) return null;
    if (typeof value === 'object') return value;
    if (typeof value === 'string') {
      try { return JSON.parse(value); } catch (_) { return null; }
    }
    return null;
  }

  function captionTracksFromPlayerResponse(response) {
    return response && response.captions &&
      response.captions.playerCaptionsTracklistRenderer &&
      Array.isArray(response.captions.playerCaptionsTracklistRenderer.captionTracks)
      ? response.captions.playerCaptionsTracklistRenderer.captionTracks
      : [];
  }

  function parseJson3(payload) {
    const events = payload && Array.isArray(payload.events) ? payload.events : [];
    const cues = [];

    for (const event of events) {
      const startMs = Number(event && event.tStartMs);
      if (!Number.isFinite(startMs)) continue;
      const rawSegs = Array.isArray(event.segs) ? event.segs : [];
      const text = normalizeText(rawSegs.map(seg => seg && seg.utf8 || '').join(''));
      const cueTokens = tokens(text);
      if (!text || !cueTokens.length) continue;

      const durationMsRaw = Number(event.dDurationMs);
      const durationMs = Number.isFinite(durationMsRaw) && durationMsRaw > 0
        ? durationMsRaw
        : Math.max(1200, cueTokens.length * 320);
      const cueStart = startMs / 1000;
      const cueEnd = (startMs + durationMs) / 1000;
      const tokenSegments = [];

      for (const seg of rawSegs) {
        const segTokens = tokens(seg && seg.utf8 || '');
        if (!segTokens.length) continue;
        const offsetMs = Number(seg && seg.tOffsetMs);
        tokenSegments.push({
          tokens: segTokens,
          offsetMs: Number.isFinite(offsetMs) && offsetMs >= 0 ? offsetMs : null,
        });
      }

      const everyChunkAnchored = tokenSegments.length > 0 &&
        tokenSegments.every(segment => segment.offsetMs !== null);
      const words = [];

      if (everyChunkAnchored) {
        for (let i = 0; i < tokenSegments.length; i += 1) {
          const segment = tokenSegments[i];
          const next = tokenSegments[i + 1];
          const segmentStart = cueStart + segment.offsetMs / 1000;
          const segmentEnd = next && next.offsetMs !== null
            ? Math.min(cueEnd, cueStart + next.offsetMs / 1000)
            : cueEnd;
          const usableEnd = Math.max(segmentStart + 0.04 * segment.tokens.length, segmentEnd);
          const slice = Math.max(0.04, (usableEnd - segmentStart) / segment.tokens.length);
          segment.tokens.forEach((word, wordIndex) => {
            words.push({
              text: word,
              startSec: segmentStart + slice * wordIndex,
              endSec: wordIndex === segment.tokens.length - 1
                ? usableEnd
                : segmentStart + slice * (wordIndex + 1),
              exactChunkStart: wordIndex === 0,
            });
          });
        }
      } else {
        const slice = Math.max(0.06, (cueEnd - cueStart) / cueTokens.length);
        cueTokens.forEach((word, index) => {
          words.push({
            text: word,
            startSec: cueStart + slice * index,
            endSec: index === cueTokens.length - 1 ? cueEnd : cueStart + slice * (index + 1),
            exactChunkStart: index === 0,
          });
        });
      }

      if (words.length !== cueTokens.length) {
        words.length = 0;
        const slice = Math.max(0.06, (cueEnd - cueStart) / cueTokens.length);
        cueTokens.forEach((word, index) => {
          words.push({
            text: word,
            startSec: cueStart + slice * index,
            endSec: index === cueTokens.length - 1 ? cueEnd : cueStart + slice * (index + 1),
            exactChunkStart: index === 0,
          });
        });
      }

      cues.push({ text, startSec: cueStart, endSec: cueEnd, words });
    }

    cues.sort((a, b) => a.startSec - b.startSec);
    return cues;
  }

  function findActiveCaption(cues, currentSecond, leadSeconds) {
    if (!Array.isArray(cues) || !cues.length || !Number.isFinite(currentSecond)) return null;
    const t = currentSecond + (Number.isFinite(leadSeconds) ? leadSeconds : 0.06);
    let cue = null;
    for (let i = 0; i < cues.length; i += 1) {
      const candidate = cues[i];
      if (candidate.startSec <= t && t <= candidate.endSec + 0.3) cue = candidate;
      if (candidate.startSec > t) break;
    }
    if (!cue) return null;

    let activeWordIndex = 0;
    for (let i = 0; i < cue.words.length; i += 1) {
      if (cue.words[i].startSec <= t) activeWordIndex = i;
      else break;
    }
    return { cue, activeWordIndex };
  }

  function buildTimedTextUrls(baseUrl, pageOrigin) {
    if (!baseUrl) return [];
    const origin = pageOrigin || 'https://www.youtube.com';
    let parsed;
    try { parsed = new URL(baseUrl, origin); } catch (_) { return []; }
    parsed.searchParams.set('fmt', 'json3');
    const urls = [parsed.toString()];

    let currentOrigin;
    try { currentOrigin = new URL(origin); } catch (_) { currentOrigin = null; }
    if (currentOrigin && parsed.hostname.endsWith('youtube.com') &&
        currentOrigin.hostname.endsWith('youtube.com') && parsed.origin !== currentOrigin.origin) {
      const sameOrigin = new URL(parsed.toString());
      sameOrigin.protocol = currentOrigin.protocol;
      sameOrigin.host = currentOrigin.host;
      const candidate = sameOrigin.toString();
      if (!urls.includes(candidate)) urls.push(candidate);
    }
    return urls;
  }

  function longestSuffixPrefixOverlap(previousTokens, currentTokens) {
    const before = Array.isArray(previousTokens) ? previousTokens : [];
    const current = Array.isArray(currentTokens) ? currentTokens : [];
    const limit = Math.min(before.length, current.length);
    for (let size = limit; size > 0; size -= 1) {
      let matches = true;
      for (let i = 0; i < size; i += 1) {
        if (before[before.length - size + i] !== current[i]) {
          matches = false;
          break;
        }
      }
      if (matches) return size;
    }
    return 0;
  }

  function createDomState(text, currentSecond) {
    const clean = normalizeText(text);
    const words = lowerTokens(clean);
    if (!clean || !words.length) return null;
    const second = Number.isFinite(currentSecond) ? currentSecond : 0;
    return {
      text: clean,
      tokens: words,
      activeWordIndex: 0,
      anchorSec: second,
      lastSecond: second,
    };
  }

  function advanceDomState(state, currentSecond, secondsPerWord) {
    if (!state || !Array.isArray(state.tokens) || !state.tokens.length) return state;
    const second = Number.isFinite(currentSecond) ? currentSecond : state.lastSecond;
    const step = Number.isFinite(secondsPerWord) && secondsPerWord > 0
      ? secondsPerWord
      : DOM_WORD_SECONDS;

    if (state.lastSecond === 0 && state.anchorSec === 0 && second > 1) {
      return {
        ...state,
        anchorSec: second,
        lastSecond: second,
      };
    }

    if (Number.isFinite(state.lastSecond) && second + 0.45 < state.lastSecond) {
      return { ...state, activeWordIndex: 0, anchorSec: second, lastSecond: second };
    }

    const elapsed = Math.max(0, second - state.anchorSec);
    const estimatedIndex = Math.min(state.tokens.length - 1, Math.floor(elapsed / step));
    return {
      ...state,
      activeWordIndex: Math.max(state.activeWordIndex, estimatedIndex),
      lastSecond: second,
    };
  }

  function reconcileDomState(previousState, text, currentSecond, secondsPerWord) {
    const clean = normalizeText(text);
    const currentTokens = lowerTokens(clean);
    if (!clean || !currentTokens.length) return previousState || null;
    const second = Number.isFinite(currentSecond)
      ? currentSecond
      : (previousState && previousState.lastSecond) || 0;

    if (!previousState || !Array.isArray(previousState.tokens) || !previousState.tokens.length) {
      return createDomState(clean, second);
    }

    const previousTokens = previousState.tokens;
    if (clean === previousState.text &&
        currentTokens.length === previousTokens.length &&
        currentTokens.every((word, index) => word === previousTokens[index])) {
      return advanceDomState(previousState, second, secondsPerWord);
    }

    const isPrefixGrowth = currentTokens.length > previousTokens.length &&
      previousTokens.every((word, index) => word === currentTokens[index]);
    if (isPrefixGrowth) {
      const activeWordIndex = currentTokens.length - 1;
      const step = Number.isFinite(secondsPerWord) && secondsPerWord > 0
        ? secondsPerWord : DOM_WORD_SECONDS;
      return {
        text: clean,
        tokens: currentTokens,
        activeWordIndex,
        anchorSec: second - activeWordIndex * step,
        lastSecond: second,
      };
    }

    const overlap = longestSuffixPrefixOverlap(previousTokens, currentTokens);
    if (overlap > 0) {
      const dropped = previousTokens.length - overlap;
      const mappedOldIndex = Math.max(0, previousState.activeWordIndex - dropped);
      const appendedCount = currentTokens.length - overlap;
      const activeWordIndex = appendedCount > 0
        ? currentTokens.length - 1
        : Math.min(currentTokens.length - 1, mappedOldIndex);
      const step = Number.isFinite(secondsPerWord) && secondsPerWord > 0
        ? secondsPerWord : DOM_WORD_SECONDS;
      return {
        text: clean,
        tokens: currentTokens,
        activeWordIndex,
        anchorSec: second - activeWordIndex * step,
        lastSecond: second,
      };
    }

    const activeToken = previousTokens[previousState.activeWordIndex];
    if (activeToken) {
      for (let i = currentTokens.length - 1; i >= 0; i -= 1) {
        if (currentTokens[i] === activeToken) {
          const step = Number.isFinite(secondsPerWord) && secondsPerWord > 0
            ? secondsPerWord : DOM_WORD_SECONDS;
          return {
            text: clean,
            tokens: currentTokens,
            activeWordIndex: i,
            anchorSec: second - i * step,
            lastSecond: second,
          };
        }
      }
    }

    return createDomState(clean, second);
  }

  function install(bridge) {
    if (typeof window === 'undefined' || typeof document === 'undefined') return;
    const host = String(location.hostname || '').toLowerCase().replace(/\.$/, '');
    if (location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return;
    if (window.__highlightDualSubLabEngineV2Installed) return;
    window.__highlightDualSubLabEngineV2Installed = true;
    if (!bridge || typeof bridge.postMessage !== 'function') return;

    let cues = [];
    let loadedVideoId = '';
    let loadingVideoId = '';
    let lastCaptionKey = '';
    let lastStatus = '';
    let lastMediaTime = null;
    let lastTimedTextFailureAt = 0;
    let domState = null;
    let lastDomEmitKey = '';
    let timedLanguage = '';
    let sessionVideoId = '';
    let sessionLanguage = '';
    let generation = 0;
    let revision = 0;
    let lastEmitAt = 0;
    let lastFrameAt = 0;
    let eventVideo = null;

    function options() { return window.__dualSubLabOptions || {}; }
    function trusted() {
      const host = location.hostname.toLowerCase().replace(/\.$/, '');
      return location.protocol === 'https:' && (host === 'youtube.com' || host.endsWith('.youtube.com'));
    }
    function reset() {
      generation += 1;
      cues = [];
      timedLanguage = '';
      loadedVideoId = '';
      loadingVideoId = '';
      domState = null;
      lastCaptionKey = '';
      lastDomEmitKey = '';
      lastMediaTime = null;
    }
    function resetWords() {
      domState = null;
      lastCaptionKey = '';
      lastDomEmitKey = '';
      lastMediaTime = null;
      post({ type: 'caption', text: '', activeWordIndex: -1, currentSecond: activeVideo()?.currentTime || 0, source: 'clear' });
    }
    function ready() {
      if (!trusted() || !options().enabled) { reset(); return false; }
      const id = currentVideoId();
      const language = options().language || 'en';
      if (id !== sessionVideoId || language !== sessionLanguage) {
        reset();
        sessionVideoId = id;
        sessionLanguage = language;
        resetWords();
      }
      return !!id;
    }

    function post(payload) {
      if (!trusted() || !options().enabled || payload.type !== 'caption') return;
      const text = normalizeText(payload.text);
      if (text.length > 4096) return;
      try {
        bridge.postMessage(JSON.stringify({ ...payload, text, videoId: currentVideoId(),
          languageCode: payload.languageCode || timedLanguage || sessionLanguage, revision: ++revision, sampledAtEpochMs: Date.now(), url: location.href }));
        lastEmitAt = Date.now();
      } catch (_) {}
    }

    function status(message) {
      if (!message || message === lastStatus) return;
      lastStatus = message;
      post({ type: 'status', message });
    }

    function currentPlayer() {
      return document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
    }

    function activeVideo() {
      const videos = Array.from(document.querySelectorAll('video'));
      return videos.find(video => !video.paused && !video.ended) ||
        videos.find(video => video.readyState > 0) || videos[0] || null;
    }

    function currentVideoId() {
      const player = currentPlayer();
      try {
        const data = player && typeof player.getVideoData === 'function' ? player.getVideoData() : null;
        if (data && data.video_id) return String(data.video_id);
      } catch (_) {}
      try {
        const url = new URL(location.href);
        return url.searchParams.get('v') || '';
      } catch (_) { return ''; }
    }

    function getPlayerResponse() {
      const player = currentPlayer();
      try {
        if (player && typeof player.getPlayerResponse === 'function') {
          const response = parsePlayerResponse(player.getPlayerResponse());
          if (response) return response;
        }
      } catch (_) {}
      const initial = parsePlayerResponse(window.ytInitialPlayerResponse);
      if (initial) return initial;
      try {
        const configured = parsePlayerResponse(window.ytplayer && window.ytplayer.config &&
          window.ytplayer.config.args && window.ytplayer.config.args.player_response);
        if (configured) return configured;
      } catch (_) {}
      return null;
    }

    async function fetchJson3(track) {
      const urls = buildTimedTextUrls(track && track.baseUrl, location.origin);
      let lastError = null;
      for (const url of urls) {
        try {
          const parsedUrl = new URL(url);
          if (parsedUrl.protocol !== 'https:' || !(parsedUrl.hostname === 'youtube.com' || parsedUrl.hostname.endsWith('.youtube.com'))) continue;
          const response = await fetch(url, { credentials: 'include', cache: 'no-store' });
          if (!response.ok) throw new Error('HTTP ' + response.status);
          const body = await response.text();
          if (body.length > 8 * 1024 * 1024) throw new Error('Caption response too large');
          const json = JSON.parse(body);
          if (json && Array.isArray(json.events)) return json;
          throw new Error('JSON3 response has no events');
        } catch (error) {
          lastError = error;
        }
      }
      throw lastError || new Error('No timed-text URL');
    }

    async function loadTrackForCurrentVideo() {
      if (!ready()) return;
      const ticket = generation;
      const videoId = currentVideoId();
      if (!videoId || videoId === loadedVideoId || videoId === loadingVideoId) return;
      const response = getPlayerResponse();
      if (!response) {
        status('Waiting for YouTube player response…');
        return;
      }
      const responseId = response.videoDetails && response.videoDetails.videoId
        ? String(response.videoDetails.videoId) : videoId;
      if (responseId && responseId !== videoId) {
        status('Waiting for current video caption metadata…');
        return;
      }
      const tracks = captionTracksFromPlayerResponse(response);
      if (!tracks.length) {
        status('No caption track found yet • smart DOM fallback active');
        return;
      }
      const track = pickCaptionTrack(tracks, sessionLanguage);
      if (!track || !track.baseUrl) {
        status('Caption metadata found, but no timed-text URL');
        return;
      }

      loadingVideoId = videoId;
      status('Loading YouTube timed-text (' + (track.languageCode || 'unknown') +
        (track.kind === 'asr' ? ', auto-generated' : '') + ')…');
      try {
        const json = await fetchJson3(track);
        if (ticket !== generation || !ready() || ticket !== generation) return;
        const parsed = parseJson3(json);
        if (!parsed.length) throw new Error('No usable caption cues');
        timedLanguage = track.languageCode || sessionLanguage;
        cues = parsed;
        loadedVideoId = videoId;
        lastCaptionKey = '';
        domState = null;
        lastDomEmitKey = '';
        status('YouTube JSON3 ready • ' + parsed.length + ' timed caption cues');
      } catch (_) {
        if (ticket !== generation) return;
        cues = [];
        lastTimedTextFailureAt = Date.now();
        status('Timed-text failed • smart DOM clock fallback active');
      } finally {
        if (ticket === generation) loadingVideoId = '';
      }
    }

    function emitTimedCaption(currentSecond) {
      const active = findActiveCaption(cues, currentSecond, 0.06);
      if (!active) return false;
      const key = active.cue.startSec.toFixed(3) + ':' + active.activeWordIndex;
      if (key === lastCaptionKey && Date.now() - lastEmitAt < 100) return true;
      lastCaptionKey = key;
      post({
        type: 'caption',
        text: active.cue.text,
        activeWordIndex: active.activeWordIndex,
        currentSecond,
        source: 'json3',
        url: location.href,
      });
      return true;
    }

    function visibleDomCaptionText() {
      const segmentNodes = Array.from(document.querySelectorAll('.ytp-caption-segment'));
      if (segmentNodes.length) {
        return normalizeText(segmentNodes.map(node => node.textContent || '').join(' '));
      }
      const roots = Array.from(document.querySelectorAll('.ytp-caption-window-container, .caption-window'));
      for (const root of roots) {
        const text = normalizeText(root && root.textContent || '');
        if (text) return text;
      }
      return '';
    }

    function emitDomFallback(currentSecond) {
      const text = visibleDomCaptionText();
      let languageCode = sessionLanguage;
      try {
        const player = currentPlayer();
        const track = player && player.getOption && player.getOption('captions', 'track');
        if (track) languageCode = (track.translationLanguage && track.translationLanguage.languageCode) || track.languageCode || sessionLanguage;
      } catch (_) {}
      if (!text) { resetWords(); return false; }
      domState = reconcileDomState(domState, text, currentSecond, DOM_WORD_SECONDS);
      if (!domState) return false;
      const key = domState.text + ':' + domState.activeWordIndex;
      if (key === lastDomEmitKey && Date.now() - lastEmitAt < 100) return true;
      lastDomEmitKey = key;
      post({
        type: 'caption',
        text: domState.text,
        activeWordIndex: domState.activeWordIndex,
        currentSecond,
        source: 'dom-clock',
        languageCode,
        url: location.href,
      });
      return true;
    }

    function ensureCaptionsEnabled() {
      if (!ready()) return;
      const candidates = [
        document.querySelector('.ytp-subtitles-button'),
        ...Array.from(document.querySelectorAll('button[aria-label], [role="button"][aria-label]'))
          .filter(node => /caption|subtitle/i.test(node.getAttribute('aria-label') || '')),
      ].filter(Boolean);
      const button = candidates[0];
      if (button && button.getAttribute('aria-pressed') === 'false') {
        try { button.click(); } catch (_) {}
      }
    }

    function tick(second) {
      if (!ready()) return;
      const video = activeVideo();
      if (!video || video.seeking) { resetWords(); return; }
      if (video !== eventVideo) {
        eventVideo = video;
        resetWords();
        ['seeking', 'emptied'].forEach(name => video.addEventListener(name, function () {
          if (video === activeVideo()) resetWords();
        }));
      }
      // Frame callbacks drive playback, while the watchdog handles pause and
      // fullscreen surfaces that no longer deliver frame callbacks to the page.
      lastMediaTime = second;
      if (!emitTimedCaption(second)) emitDomFallback(second);
    }

    function frameLoop() {
      if (!trusted()) return;
      const video = activeVideo();
      if (!video) { setTimeout(frameLoop, 120); return; }
      const onFrame = function (_, metadata) {
        lastFrameAt = Date.now();
        if (video === activeVideo()) {
          tick(metadata && Number.isFinite(metadata.mediaTime) ? metadata.mediaTime : video.currentTime);
        }
        frameLoop();
      };
      if (typeof video.requestVideoFrameCallback === 'function') video.requestVideoFrameCallback(onFrame);
      else setTimeout(function () { onFrame(0, { mediaTime: video.currentTime }); }, 40);
    }

    const domObserver = new MutationObserver(function () {
      if (!ready() || cues.length) return;
      const video = activeVideo();
      if (video && !video.seeking) emitDomFallback(video.currentTime);
    });
    domObserver.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    setInterval(function () {
      if (!ready()) return;
      const video = activeVideo();
      if (video && (video.paused || Date.now() - lastFrameAt > 120)) tick(video.currentTime);
    }, 40);

    status('Caption engine v2 installed • waiting for a video');
    frameLoop();
    setInterval(function () {
      ensureCaptionsEnabled();
      loadTrackForCurrentVideo();
      if (!cues.length && lastTimedTextFailureAt && Date.now() - lastTimedTextFailureAt > 4000) {
        loadedVideoId = '';
        lastTimedTextFailureAt = 0;
      }
    }, 700);
  }

  return {
    normalizeText,
    tokens,
    pickCaptionTrack,
    parsePlayerResponse,
    captionTracksFromPlayerResponse,
    parseJson3,
    findActiveCaption,
    buildTimedTextUrls,
    longestSuffixPrefixOverlap,
    createDomState,
    advanceDomState,
    reconcileDomState,
    install,
  };
});