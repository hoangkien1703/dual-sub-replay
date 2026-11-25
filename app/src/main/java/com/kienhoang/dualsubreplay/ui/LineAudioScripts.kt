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
    const state = { context: null, node: null, source: null, stream: null, track: null, video: null, videoId: '', blocks: [], samples: 0, last: null, pausedAt: 0, got: 0, heard: 0, lags: [], lateRun: 0, cut: null };
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
    // The audio clock starts again when the context runs again after a rest.
    const restart = function() { state.got = 0; state.lags = []; state.lateRun = 0; state.last = null; };
    const resume = function() {
      if (state.context && state.context.state !== 'running' && state.context.state !== 'closed') state.context.resume().then(restart).catch(function() {});
    };
    // Where the audio clock was when the page video paused, so a block that played before the pause
    // but arrived after it keeps its part before the pause (the line's tail with Pause after each line).
    const paused = function(event) {
      if (state.context && event && event.target === state.video) state.cut = state.context.currentTime;
    };
    // The page video started playing: wake the recorder now rather than on the app's next check. After
    // a minute of pause the context rests, and a line replayed for a card's sound starts less than a
    // second before the line, so waiting for the check missed the line's start (lion737, #81, 2026-11-14).
    const wake = function(event) {
      if (!state.context || !event || event.target !== pageVideo()) return;
      state.pausedAt = 0;
      state.cut = null;
      if (event.target !== state.video || !state.stream) attach(event.target);
      else follow();
      resume();
    };
    // A busy page runs this late, and on a phone a big YouTube page often is. So the node takes big
    // blocks (a small one lost pieces of sound: choppy clips, Kien 2026-11-17), and each block's time
    // comes from the audio clock (samples received against the context's time), not from when this ran.
    const listen = function(event) {
      const rate = event.inputBuffer.sampleRate;
      const input = event.inputBuffer.getChannelData(0);
      const length = input.length / rate;
      state.got += input.length;
      const lag = state.context.currentTime - state.got / rate;
      state.lags.push(lag);
      if (state.lags.length > 16) state.lags.shift();
      let floor = Math.min.apply(null, state.lags);
      let late = Math.max(0, lag - floor);
      // Late by a whole block again and again: sound was lost, so the clock starts again and the run breaks.
      state.lateRun = late > length * 0.9 ? state.lateRun + 1 : 0;
      if (state.lateRun >= 3) { state.lags = [lag]; floor = lag; late = 0; state.lateRun = 0; state.last = null; }
      // The audio clock's time of the newest sound that came in.
      state.heard = state.got / rate + floor;
      const video = state.video;
      if (!video || video.seeking || video.ended || adShowing() || pageVideoId() !== state.videoId) {
        state.last = null;
        return;
      }
      let keep = input.length;
      let start = 0;
      if (video.paused || video.readyState < 3) {
        const blockStart = state.got / rate + floor - length;
        keep = video.paused && state.cut !== null && state.last !== null ? Math.max(0, Math.min(input.length, Math.floor((state.cut - blockStart) * rate))) : 0;
        if (keep === 0) {
          state.last = null;
          return;
        }
        start = state.last;
      } else {
        start = video.currentTime - (late + length) * (video.playbackRate || 1);
        if (state.last !== null && Math.abs(start - state.last) < 0.25) start = state.last;
      }
      const span = keep / rate * (video.playbackRate || 1);
      const samples = new Int16Array(keep);
      for (let i = 0; i < keep; i++) {
        const value = Math.max(-1, Math.min(1, input[i]));
        samples[i] = value < 0 ? value * 32768 : value * 32767;
      }
      state.blocks.push({ start: start, end: start + span, samples: samples });
      state.samples += samples.length;
      state.last = keep === input.length ? start + span : null;
      const kept = rate * ${LINE_AUDIO_KEPT_MS / 1000};
      while (state.blocks.length > 1 && state.samples - state.blocks[0].samples.length > kept) {
        state.samples -= state.blocks.shift().samples.length;
      }
    };
    const connect = function(track) {
      if (state.source) { try { state.source.disconnect(); } catch (error) {} }
      state.source = null;
      state.track = track;
      if (!track) return;
      state.source = state.context.createMediaStreamSource(new MediaStream([track]));
      state.source.connect(state.node);
    };
    // When the video's source changes (an ad ends, another video opens in the same element), its
    // captured stream gains a new audio track; the old one stays "live" but only gives silence. So
    // the recorder always listens to the newest live track (lion737, #81, 2026-11-14).
    const follow = function() {
      const tracks = state.stream ? state.stream.getAudioTracks().filter(function(track) { return track.readyState === 'live'; }) : [];
      const newest = tracks.length ? tracks[tracks.length - 1] : null;
      if (newest !== state.track) connect(newest);
    };
    const attach = function(video) {
      if (state.stream) state.stream.removeEventListener('addtrack', follow);
      state.stream = null;
      state.video = video;
      connect(null);
      if (!video || typeof video.captureStream !== 'function') return;
      try { state.stream = video.captureStream(); } catch (error) { return; }
      state.stream.addEventListener('addtrack', follow);
      follow();
    };
    recorder = {
      ensure: function(videoId) {
        if (videoId !== state.videoId) { clear(); state.videoId = videoId; }
        // A context the system closed can't record again; start a new one.
        if (state.context && state.context.state === 'closed') { state.context = null; state.source = null; state.stream = null; state.track = null; state.video = null; }
        if (!state.context) {
          try { state.context = new AudioContext({ sampleRate: 24000 }); } catch (error) { state.context = new AudioContext(); }
          restart();
          // About 0.7 s at 24 kHz: room for a busy page (see listen).
          state.node = state.context.createScriptProcessor(16384, 1, 1);
          state.node.onaudioprocess = listen;
          state.node.connect(state.context.destination);
          // Media events don't bubble, so the document hears them while capturing.
          document.addEventListener('play', wake, true);
          document.addEventListener('playing', wake, true);
          document.addEventListener('pause', paused, true);
          document.addEventListener('pointerdown', resume, true);
          document.addEventListener('touchend', resume, true);
          document.addEventListener('keydown', resume, true);
        }
        const video = pageVideo();
        if (video !== state.video || !state.stream) attach(video);
        else follow();
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
        // A block comes in up to a block's length (0.7 s) after it played. So while the video plays
        // on, or the sound before its pause hasn't all come in, the rest of the line is on its way:
        // the newest block isn't where the line's sound ends, and cutting there lost the tail.
        const arriving = function() {
          const video = state.video;
          if (!video || video.ended) return false;
          return !video.paused || (state.cut !== null && state.heard < state.cut);
        };
        // The last block of an unbroken run of played sound from block [from] that reaches the line's end, or -1.
        const runEnd = function(from) {
          let at = from;
          while (blocks[at].end < endSecond) {
            const next = blocks[at + 1];
            if (!next || Math.abs(next.start - blocks[at].end) > 0.06) {
              if (!next && arriving()) return -1;
              // A video paused right at the line's end (Pause after each line) has the line without its tail.
              return blocks[at].end >= lineEnd - 0.15 ? at : -1;
            }
            at++;
          }
          return at;
        };
        // The newest run that holds the whole line: a line paused half way and then replayed (a card
        // sent mid-line) also has an older, broken run, which must not hide the replay.
        let first = -1;
        let last = -1;
        for (let i = blocks.length - 1; i >= 0 && last < 0; i--) {
          if (blocks[i].end > startSecond && blocks[i].start <= startSecond + 0.05) {
            last = runEnd(i);
            first = i;
          }
        }
        if (last < 0) return null;
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
      // The unbroken runs of kept sound as [start, end] seconds, for tests and debugging.
      runs: function() {
        const runs = [];
        state.blocks.forEach(function(block) {
          const run = runs[runs.length - 1];
          if (run && Math.abs(block.start - run[1]) <= 0.06) run[1] = block.end;
          else runs.push([block.start, block.end]);
        });
        return JSON.stringify(runs.map(function(run) { return [Math.round(run[0] * 100) / 100, Math.round(run[1] * 100) / 100]; }));
      },
      // Lets the audio context rest now, as a minute of pause does; for tests.
      rest: function() {
        state.pausedAt = Date.now() - 60001;
        return state.context ? state.context.suspend() : null;
      },
      stop: function() {
        document.removeEventListener('play', wake, true);
        document.removeEventListener('playing', wake, true);
        document.removeEventListener('pause', paused, true);
        document.removeEventListener('pointerdown', resume, true);
        document.removeEventListener('touchend', resume, true);
        document.removeEventListener('keydown', resume, true);
        if (state.stream) state.stream.removeEventListener('addtrack', follow);
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
