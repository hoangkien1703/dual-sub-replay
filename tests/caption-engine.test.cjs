const test = require('node:test');
const assert = require('node:assert/strict');
const engine = require('../app/src/main/assets/youtube-caption-engine.js');

test('prefers English auto-generated caption track', () => {
  const tracks = [
    { languageCode: 'vi', kind: 'asr', baseUrl: '/vi' },
    { languageCode: 'en', baseUrl: '/manual-en' },
    { languageCode: 'en-US', kind: 'asr', baseUrl: '/asr-en' },
  ];
  assert.equal(engine.pickCaptionTrack(tracks, 'en').baseUrl, '/asr-en');
});

test('reads caption tracks from YouTube player response', () => {
  const tracks = [{ languageCode: 'en', kind: 'asr', baseUrl: '/api/timedtext?x=1' }];
  const response = { captions: { playerCaptionsTracklistRenderer: { captionTracks: tracks } } };
  assert.deepEqual(engine.captionTracksFromPlayerResponse(response), tracks);
});

test('parses JSON3 chunk offsets into advancing word timing', () => {
  const cues = engine.parseJson3({
    events: [{
      tStartMs: 1000,
      dDurationMs: 2000,
      segs: [
        { utf8: 'Hello ', tOffsetMs: 0 },
        { utf8: 'world', tOffsetMs: 900 },
      ],
    }],
  });
  assert.equal(cues.length, 1);
  assert.equal(cues[0].text, 'Hello world');
  assert.equal(cues[0].words.length, 2);
  assert.equal(cues[0].words[0].startSec, 1);
  assert.equal(cues[0].words[1].startSec, 1.9);
  assert.equal(engine.findActiveCaption(cues, 1.2, 0).activeWordIndex, 0);
  assert.equal(engine.findActiveCaption(cues, 2.0, 0).activeWordIndex, 1);
});

test('splits a multi-word YouTube timing chunk without losing the cue', () => {
  const cues = engine.parseJson3({
    events: [{
      tStartMs: 5000,
      dDurationMs: 1500,
      segs: [{ utf8: 'really like this', tOffsetMs: 0 }],
    }],
  });
  assert.equal(cues[0].text, 'really like this');
  assert.deepEqual(cues[0].words.map(word => word.text), ['really', 'like', 'this']);
  assert.ok(cues[0].words[0].startSec < cues[0].words[1].startSec);
  assert.ok(cues[0].words[1].startSec < cues[0].words[2].startSec);
});

test('resolves m.youtube.com relative timed-text URL and forces JSON3', () => {
  const urls = engine.buildTimedTextUrls('/api/timedtext?v=abc&lang=en', 'https://m.youtube.com');
  assert.equal(urls.length, 1);
  const parsed = new URL(urls[0]);
  assert.equal(parsed.origin, 'https://m.youtube.com');
  assert.equal(parsed.pathname, '/api/timedtext');
  assert.equal(parsed.searchParams.get('fmt'), 'json3');
});

test('adds a same-origin mobile fallback for www.youtube.com timed-text', () => {
  const urls = engine.buildTimedTextUrls(
    'https://www.youtube.com/api/timedtext?v=abc',
    'https://m.youtube.com'
  );
  assert.equal(urls.length, 2);
  assert.equal(new URL(urls[1]).origin, 'https://m.youtube.com');
  assert.equal(new URL(urls[1]).searchParams.get('fmt'), 'json3');
});

test('full rendered caption advances even when the DOM text never changes', () => {
  let state = engine.reconcileDomState(null, 'one two three four', 10.0);
  assert.equal(state.activeWordIndex, 0);
  state = engine.reconcileDomState(state, 'one two three four', 10.33);
  assert.equal(state.activeWordIndex, 1);
  state = engine.reconcileDomState(state, 'one two three four', 10.66);
  assert.equal(state.activeWordIndex, 2);
  state = engine.reconcileDomState(state, 'one two three four', 11.0);
  assert.equal(state.activeWordIndex, 3);
});

test('rolling auto captions keep progress instead of flashing back to word one', () => {
  let state = engine.reconcileDomState(null, 'you explain it kind of', 20.0);
  state = engine.reconcileDomState(state, 'you explain it kind of', 21.0);
  assert.equal(state.activeWordIndex, 3);
  state = engine.reconcileDomState(state, 'explain it kind of in', 21.1);
  assert.equal(state.activeWordIndex, 4);
});

test('shrinking a rolling caption maps the old active word instead of resetting', () => {
  let state = engine.reconcileDomState(null, 'right okay the ship', 30.0);
  state = engine.reconcileDomState(state, 'right okay the ship', 31.1);
  assert.equal(state.activeWordIndex, 3);
  state = engine.reconcileDomState(state, 'okay the ship', 31.2);
  assert.equal(state.activeWordIndex, 2);
});

test('incremental auto caption growth immediately highlights the newest spoken word', () => {
  let state = engine.reconcileDomState(null, 'you explain', 40.0);
  state = engine.reconcileDomState(state, 'you explain it', 40.2);
  assert.equal(state.activeWordIndex, 2);
  state = engine.reconcileDomState(state, 'you explain it kind', 40.5);
  assert.equal(state.activeWordIndex, 3);
});

test('a truly new unrelated caption starts at its first word', () => {
  let state = engine.reconcileDomState(null, 'old caption finished', 50.0);
  state = engine.reconcileDomState(state, 'completely new sentence', 51.0);
  assert.equal(state.activeWordIndex, 0);
});

test('provisional DOM state at 0s rebases on the first real media timestamp', () => {
  let state = engine.reconcileDomState(null, 'full caption appears here', 0);
  state = engine.reconcileDomState(state, 'full caption appears here', 126.0);
  assert.equal(state.activeWordIndex, 0);
  state = engine.reconcileDomState(state, 'full caption appears here', 126.33);
  assert.equal(state.activeWordIndex, 1);
});
