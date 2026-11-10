package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AudioLine
import com.kienhoang.dualsubreplay.data.LINE_AUDIO_KEPT_MS
import com.kienhoang.dualsubreplay.data.LINE_AUDIO_MAX_MS
import com.kienhoang.dualsubreplay.data.LINE_PICTURE_MAX_WIDTH

private val PAGE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * Keeps (or, with [on] false, stops) the page's line recorder (owner pick 2026-11-09, spec
 * docs/specs/2026-11-09-anki-word-and-sentence-audio.md): see [WEB_LINE_AUDIO_RECORDER].
 * A video other than [videoId] clears what it kept.
 */
internal fun webLineAudioScript(
    on: Boolean,
    videoId: String?,
): String {
    val safeId = videoId?.takeIf { it.matches(PAGE_VIDEO_ID) }
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
          let recorder = window.__dualSubLineAudio;
          if (!${on && safeId != null}) {
            if (recorder) recorder.stop();
            return 'off';
          }
          if (!recorder) {
          $WEB_LINE_AUDIO_RECORDER
          }
          return recorder.ensure('${safeId.orEmpty()}');
        })();
        """.trimIndent()
}

/**
 * The page's line recorder. It only listens to the page video: `captureStream()` gives its sound,
 * a Web Audio script node copies the last [LINE_AUDIO_KEPT_MS] of played sound into memory,
 * stamped with the video's time, and writes silence. Paused, seeking, buffering and ad time is
 * skipped. It never changes the video, its volume, its speed or any request. Under WebView's
 * "user gesture" rule an audio context may wait for a tap in the page, so the recorder also
 * starts on the page's next touch.
 */
private val WEB_LINE_AUDIO_RECORDER =
    """
    const state = { context: null, node: null, source: null, track: null, video: null, videoId: '', blocks: [], samples: 0, last: null, pausedAt: 0 };
    const pageVideo = function() {
      return document.querySelector('#movie_player video.html5-main-video') || document.querySelector('video.html5-main-video') || document.querySelector('video');
    };
    const pageVideoId = function() {
      const query = new URLSearchParams(window.location.search).get('v');
      if (query) return query;
      const path = window.location.pathname.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})/);
      return path ? path[1] : '';
    };
    const adShowing = function() { return !!document.querySelector('.ad-showing, .ad-interrupting'); };
    const clear = function() { state.blocks = []; state.samples = 0; state.last = null; };
    const resume = function() {
      if (state.context && state.context.state === 'suspended') state.context.resume().catch(function() {});
    };
    const listen = function(event) {
      const video = state.video;
      if (!video || video.paused || video.seeking || video.ended || video.readyState < 3 || adShowing() || pageVideoId() !== state.videoId) {
        state.last = null;
        return;
      }
      const input = event.inputBuffer.getChannelData(0);
      const span = input.length / event.inputBuffer.sampleRate * (video.playbackRate || 1);
      let start = video.currentTime - span;
      if (state.last !== null && Math.abs(start - state.last) < 0.25) start = state.last;
      const samples = new Int16Array(input.length);
      for (let i = 0; i < input.length; i++) {
        const value = Math.max(-1, Math.min(1, input[i]));
        samples[i] = value < 0 ? value * 32768 : value * 32767;
      }
      state.blocks.push({ start: start, end: start + span, samples: samples });
      state.samples += samples.length;
      state.last = start + span;
      const keep = event.inputBuffer.sampleRate * ${LINE_AUDIO_KEPT_MS / 1000};
      while (state.blocks.length > 1 && state.samples - state.blocks[0].samples.length > keep) {
        state.samples -= state.blocks.shift().samples.length;
      }
    };
    const attach = function(video) {
      if (state.source) { try { state.source.disconnect(); } catch (error) {} }
      state.source = null;
      state.track = null;
      state.video = video;
      if (!video || typeof video.captureStream !== 'function') return;
      let tracks = [];
      try { tracks = video.captureStream().getAudioTracks(); } catch (error) { return; }
      if (!tracks.length) return;
      state.track = tracks[0];
      state.source = state.context.createMediaStreamSource(new MediaStream([state.track]));
      state.source.connect(state.node);
    };
    recorder = {
      ensure: function(videoId) {
        if (videoId !== state.videoId) { clear(); state.videoId = videoId; }
        if (!state.context) {
          try { state.context = new AudioContext({ sampleRate: 24000 }); } catch (error) { state.context = new AudioContext(); }
          state.node = state.context.createScriptProcessor(2048, 1, 1);
          state.node.onaudioprocess = listen;
          state.node.connect(state.context.destination);
          document.addEventListener('pointerdown', resume, true);
          document.addEventListener('touchend', resume, true);
          document.addEventListener('keydown', resume, true);
        }
        const video = pageVideo();
        if (video !== state.video || !state.track || state.track.readyState !== 'live') attach(video);
        // A paused video lets the audio context rest after a minute; playing wakes it again.
        if (video && video.paused) {
          if (!state.pausedAt) state.pausedAt = Date.now();
          if (Date.now() - state.pausedAt > 60000 && state.context.state === 'running') state.context.suspend().catch(function() {});
        } else {
          state.pausedAt = 0;
          resume();
        }
        return JSON.stringify({ state: state.context.state, rate: state.context.sampleRate, track: !!state.track, seconds: state.samples / state.context.sampleRate });
      },
      clip: function(videoId, startSecond, endSecond, lineEnd) {
        if (videoId !== state.videoId || !state.context || !(endSecond > startSecond)) return null;
        const blocks = state.blocks;
        let first = -1;
        for (let i = 0; i < blocks.length; i++) { if (blocks[i].end > startSecond) { first = i; break; } }
        if (first < 0 || blocks[first].start > startSecond + 0.05) return null;
        let last = first;
        while (blocks[last].end < endSecond) {
          const next = blocks[last + 1];
          if (!next || Math.abs(next.start - blocks[last].end) > 0.06) {
            // A video paused right at the line's end (Pause after each line) has the line without its tail.
            if (blocks[last].end >= lineEnd - 0.15) break;
            return null;
          }
          last++;
        }
        const pieces = [];
        let total = 0;
        for (let i = first; i <= last; i++) {
          const block = blocks[i];
          const perSecond = block.samples.length / (block.end - block.start);
          const from = i === first ? Math.max(0, Math.floor((startSecond - block.start) * perSecond)) : 0;
          const to = i === last ? Math.min(block.samples.length, Math.ceil((endSecond - block.start) * perSecond)) : block.samples.length;
          if (to > from) { pieces.push(block.samples.subarray(from, to)); total += to - from; }
        }
        const joined = new Int16Array(total);
        let at = 0;
        pieces.forEach(function(piece) { joined.set(piece, at); at += piece.length; });
        const bytes = new Uint8Array(joined.buffer);
        let binary = '';
        for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        return JSON.stringify({ rate: state.context.sampleRate, pcm: btoa(binary) });
      },
      stop: function() {
        document.removeEventListener('pointerdown', resume, true);
        document.removeEventListener('touchend', resume, true);
        document.removeEventListener('keydown', resume, true);
        if (state.source) { try { state.source.disconnect(); } catch (error) {} }
        if (state.node) { try { state.node.disconnect(); } catch (error) {} }
        if (state.context) state.context.close().catch(function() {});
        clear();
        delete window.__dualSubLineAudio;
      },
    };
    window.__dualSubLineAudio = recorder;
    """.trimIndent()

/**
 * Asks the page recorder for [line]'s sound with its lead and tail (or up to the line's end, when the
 * tail hasn't played); the page answers null when it doesn't hold all of the line.
 */
internal fun webLineAudioClipScript(line: AudioLine): String {
    val safeId = line.videoId.takeIf { it.matches(PAGE_VIDEO_ID) }.orEmpty()
    val start = line.windowStartMs / 1000.0
    val end = minOf(line.windowEndMs, line.windowStartMs + LINE_AUDIO_MAX_MS + 1_000) / 1000.0
    val lineEnd = line.endMs / 1000.0
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
          const recorder = window.__dualSubLineAudio;
          return recorder ? recorder.clip('$safeId', $start, $end, $lineEnd) : null;
        })();
        """.trimIndent()
}

/**
 * Takes a still of the page video for [line] (owner pick 2026-11-10, spec
 * docs/specs/2026-11-10-anki-card-picture-and-front.md): a JPEG data URL at most
 * [LINE_PICTURE_MAX_WIDTH] wide, or null when the page shows another video, an ad, a time outside
 * the line, or a frame it can't read (a black frame counts as unreadable). It only draws the video
 * into a canvas of its own; it never changes the video or the page.
 */
internal fun webLinePictureScript(line: AudioLine): String {
    val safeId = line.videoId.takeIf { it.matches(PAGE_VIDEO_ID) }.orEmpty()
    val from = line.startMs / 1000.0
    val to = line.windowEndMs / 1000.0
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.${'$'}/, '');
          if (window.location.protocol !== 'https:' || !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
          const path = window.location.pathname.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})/);
          const pageId = new URLSearchParams(window.location.search).get('v') || (path ? path[1] : '');
          if ('$safeId' === '' || pageId !== '$safeId') return null;
          if (document.querySelector('.ad-showing, .ad-interrupting')) return null;
          const video = document.querySelector('#movie_player video.html5-main-video') || document.querySelector('video.html5-main-video') || document.querySelector('video');
          if (!video || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return null;
          if (video.currentTime < $from || video.currentTime > $to) return null;
          const width = Math.min($LINE_PICTURE_MAX_WIDTH, video.videoWidth);
          const height = Math.max(1, Math.round(video.videoHeight * width / video.videoWidth));
          try {
            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;
            const context = canvas.getContext('2d');
            context.drawImage(video, 0, 0, width, height);
            const pixels = context.getImageData(0, 0, width, height).data;
            let brightest = 0;
            for (let i = 0; i < pixels.length && brightest < 24; i += 4 * 97) brightest = Math.max(brightest, pixels[i], pixels[i + 1], pixels[i + 2]);
            if (brightest < 24) return null;
            return canvas.toDataURL('image/jpeg', 0.8);
          } catch (error) {
            return null;
          }
        })();
        """.trimIndent()
}
