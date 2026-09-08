const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('app/src/main/assets/youtube-caption-engine.js', 'utf8');

function fixture(fetchImpl) {
  const messages = [], frames = [], intervals = [], listeners = {};
  let now = 1000, caption = 'one two three four';
  const location = new URL('https://m.youtube.com/watch?v=obQgWiSX8tY');
  const video = { currentTime: 1, paused: false, ended: false, readyState: 4, seeking: false,
    requestVideoFrameCallback: cb => frames.push(cb),
    addEventListener: (name, cb) => { (listeners[name] ||= []).push(cb); } };
  const player = {
    getPlayerResponse: () => ({ videoDetails: { videoId: location.searchParams.get('v') },
      captions: { playerCaptionsTracklistRenderer: { captionTracks: [{languageCode:'en',kind:'asr',baseUrl:'https://m.youtube.com/api/timedtext'}] } } }),
    getOption: () => ({ languageCode: 'en' }),
  };
  const context = { URL, Date: class extends Date { static now() { return now; } }, location,
    __dualSubLabOptions: { enabled: true, language: 'en' },
    DualSubCaptionBridge: {postMessage: value => messages.push(JSON.parse(value))},
    document: {documentElement:{}, querySelector: q => q === '#movie_player' ? player : null,
      querySelectorAll: q => q === 'video' ? [video] : q === '.ytp-caption-segment' ? [{textContent:caption}] : []},
    MutationObserver: class { constructor(cb) { context.mutation = cb; } observe() {} },
    setInterval: (cb, ms) => intervals.push({cb, ms}), setTimeout: () => {},
    fetch: fetchImpl || (() => new Promise(() => {})),
  };
  context.window = context;
  vm.runInNewContext(source, context);
  return {messages, context, video, location, intervals,
    frame(second, elapsed=40) { now += elapsed; video.currentTime=second; frames.shift()(0,{mediaTime:second}); },
    watchdog() { now += 160; intervals.find(x=>x.ms===40).cb(); },
    mutate(text) {caption=text; context.mutation();},
    event(name) {(listeners[name]||[]).forEach(cb=>cb());},
  };
}

test('installed engine sends the lab DOM clock directly while timed-text is loading', () => {
  const f=fixture();
  f.frame(1);
  assert.equal(f.messages.at(-1).activeWordIndex,0);
  f.frame(1.34);
  assert.equal(f.messages.at(-1).activeWordIndex,1);
  assert.equal(f.messages.at(-1).source,'dom-clock');
  f.frame(2.0,20); // Faster playback uses media time, not elapsed wall time.
  assert.equal(f.messages.at(-1).activeWordIndex,3);
  f.video.paused=true;
  f.watchdog(); f.watchdog();
  assert.equal(f.messages.at(-1).activeWordIndex,3);
});

test('fullscreen watchdog and rolling DOM growth retain the same engine', () => {
  const f=fixture(); f.frame(1);
  f.video.currentTime=1.65; f.watchdog();
  assert.equal(f.messages.at(-1).activeWordIndex,2);
  f.mutate('one two three four five');
  assert.equal(f.messages.at(-1).activeWordIndex,4);
  assert.equal(f.messages.at(-1).currentSecond,1.65);
});

test('seek clears prior words and restarts from the new media position', () => {
  const f=fixture(); f.frame(1); f.frame(2);
  f.video.seeking=true; f.event('seeking');
  assert.equal(f.messages.at(-1).activeWordIndex,-1);
  f.video.seeking=false; f.frame(0.2);
  assert.equal(f.messages.at(-1).activeWordIndex,0);
});

test('disabled highlighting and external navigation cannot publish caption events', () => {
  const f=fixture(); f.frame(1);
  const count=f.messages.length;
  f.context.__dualSubLabOptions.enabled=false; f.frame(2); f.mutate('new words');
  assert.equal(f.messages.length,count);
  f.context.__dualSubLabOptions.enabled=true;
  f.location.hostname='example.com'; f.frame(3);
  assert.equal(f.messages.length,count);
});

test('a pending timed-text response from the previous video is discarded', async () => {
  let finish;
  const f=fixture(() => new Promise(resolve => {finish=resolve;}));
  f.frame(1);
  f.intervals.find(x=>x.ms===700).cb();
  f.location.search='?v=abcdefghijk'; f.frame(2);
  finish({ok:true,text:async()=>JSON.stringify({events:[{tStartMs:0,dDurationMs:10000,segs:[{utf8:'obsolete caption',tOffsetMs:0}]}]})});
  await new Promise(resolve=>setImmediate(resolve));
  f.frame(2.4);
  assert.equal(f.messages.at(-1).videoId,'abcdefghijk');
  assert.equal(f.messages.some(x=>x.text==='obsolete caption'),false);
  assert.equal(f.messages.at(-1).source,'dom-clock');
});
