// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
require('../translation/ai.js');
const { aiSystemPrompt, aiRequest, aiBatches, parseAiReply } = globalThis.DualSub;

// The prompt names both languages as Android's does.
const prompt = aiSystemPrompt('en', 'fa');
assert.ok(prompt.startsWith('You translate video subtitles from English (en) to Persian (fa) for someone learning English who reads Persian.'));
assert.ok(prompt.includes('Answer with JSON only'));
// Version 3: a line's meaning stays in its own translation; Android's "gives the whole sentence" moved words between lines.
assert.strictEqual(globalThis.DualSub.AI_PROMPT_VERSION, 3);
assert.ok(prompt.includes('never move words or meaning into the translation of an earlier or later sentence'));
assert.ok(!prompt.includes('reading the translations in order gives the whole sentence'));

// The request is data: context, then numbered sentences with one row each.
assert.deepStrictEqual(JSON.parse(aiRequest(['Before.'], ['One.', 'Two.'])), {
  context: ['Before.'],
  sentences: [{ id: 1, rows: ['One.'] }, { id: 2, rows: ['Two.'] }],
});

// Batches: at most 60 sentences and 6,000 characters each.
assert.deepStrictEqual(aiBatches(Array(130).fill('short')), [[0, 60], [60, 120], [120, 130]]);
assert.deepStrictEqual(aiBatches(['x'.repeat(4000), 'y'.repeat(2500), 'z']), [[0, 1], [1, 3]]);
assert.deepStrictEqual(aiBatches([]), []);

const sentences = ['so in college I was a government major', 'It is about procrastination.', 'OK'];
// A fenced reply with text around it; markup and a leading comma are removed; a missing id is null.
assert.deepStrictEqual(
  parseAiReply('Here you go:\n```json\n{"sentences":[{"id":1,"rows":["<i>در دانشگاه</i> رشته‌ام علوم سیاسی بود"]},{"id":3,"rows":[", باشه"]}]}\n```', sentences),
  ['در دانشگاه رشته‌ام علوم سیاسی بود', null, 'باشه'],
);
// A quote the model left unescaped is repaired.
assert.deepStrictEqual(parseAiReply('{"sentences":[{"id":2,"rows":["درباره ی "تعلل" است."]}]}', sentences), [null, 'درباره ی "تعلل" است.', null]);
// A copy of the source before the translation is cut; a bare copy is no translation; a short word like "OK" may stay.
assert.deepStrictEqual(
  parseAiReply('{"sentences":[{"id":1,"rows":["so in college I was a government major - در دانشگاه"]},{"id":2,"rows":["It is about procrastination."]},{"id":3,"rows":["OK"]}]}', sentences),
  ['در دانشگاه', null, 'OK'],
);
assert.throws(() => parseAiReply('Sorry, I cannot help.', sentences), /no JSON/);

// ---- AI sentence splitting ----
const { captionWords, isUnpunctuated, wordChunks, aiSegmentRequest, parseSegmentReply, segmentLines } = globalThis.DualSub;
const w = (text, time) => ({ text, time });
const pieces = [
  { start: 0, end: 2, text: 'so in college i was', words: [w('so', 0), w('in', 0.3), w('college', 0.6), w('i', 1.2), w('was', 1.5)] },
  { start: 2, end: 4, text: 'a government major then', words: [w('a', 2), w('government', 2.2), w('major', 2.8), w('then', 3.6)] },
  { start: 4, end: 6, text: 'i wrote papers', words: [w('i', 4.1), w('wrote', 4.4), w('papers', 4.9)] },
];
assert.strictEqual(captionWords(pieces).length, 12);
assert.strictEqual(captionWords([...pieces, { start: 6, end: 7, text: 'no timing' }]), null);
assert.strictEqual(isUnpunctuated(pieces), true);
assert.strictEqual(isUnpunctuated([{ text: 'Hello there.' }, { text: 'Yes!' }]), false);

// Chunks of at most N words, cut at the longest pause near the end.
const many = Array.from({ length: 10 }, (_, i) => w(`w${i}`, i + (i >= 7 ? 5 : 0))); // a long pause before word 7
assert.deepStrictEqual(wordChunks(many, 8, 4), [[0, 7], [7, 10]]);
assert.deepStrictEqual(wordChunks(many.slice(0, 5), 8, 4), [[0, 5]]);

assert.deepStrictEqual(JSON.parse(aiSegmentRequest(['Before.'], ['so', 'in'])), { context: ['Before.'], words: '0:so 1:in' });

// The reply: in a fence, starts out of order, a duplicate and one out of range are repaired; the first starts at 0.
const words = captionWords(pieces);
const reply = 'Sure:\n```json\n{"sentences":[{"start":8,"text":"Then I wrote papers.","translation":"بعد مقاله نوشتم."},{"start":1,"text":"So in college, I was a government major.","translation":"در دانشگاه رشته‌ام علوم سیاسی بود."},{"start":8,"text":"x","translation":"x"},{"start":99,"text":"y","translation":"y"}]}\n```';
const split = parseSegmentReply(reply, words.length);
assert.deepStrictEqual(split.map((s) => s.start), [0, 8]);
const lines = segmentLines(split, words, 6);
assert.deepStrictEqual(lines.map((l) => [l.start, l.end, l.text, l.tr, l.words.length]), [
  [0, 3.6, 'So in college, I was a government major.', 'در دانشگاه رشته‌ام علوم سیاسی بود.', 8],
  [3.6, 6, 'Then I wrote papers.', 'بعد مقاله نوشتم.', 4],
]);
// A sentence whose words the AI changed keeps the captions' own words.
const changed = segmentLines([{ start: 0, text: 'So in college I was a politics major.', translation: 't' }], words.slice(0, 8), 3.6);
assert.strictEqual(changed[0].text, 'So in college i was a government major');
assert.throws(() => parseSegmentReply('{"sentences":[]}', 5), /no sentences/);

// ---- Pacing: no more than 10 requests per key and model in any minute (the app's AiKeyPacer budget) ----
const { aiPacerWait } = globalThis.DualSub;
// A fake clock: 30 requests asked for at once, each sent when the pacer allows it.
let now = 0;
let sentTimes = [];
const sends = [];
for (let n = 0; n < 30; n++) {
  for (;;) {
    const { wait, sent } = aiPacerWait(sentTimes, now);
    sentTimes = sent;
    if (!wait) break;
    now += wait;
  }
  sentTimes.push(now);
  sends.push(now);
}
for (const t of sends) assert.ok(sends.filter((x) => x >= t && x < t + 60_000).length <= 10, `more than 10 requests in the minute from ${t}`);
assert.deepStrictEqual([sends[9], sends[10], sends[20]], [0, 60_000, 120_000]); // ten at once, then the next minute
assert.strictEqual(aiPacerWait([0, 1000], 61_500).wait, 0); // requests older than a minute no longer count

// ---- Cache size: the oldest answers go once storage passes 80% of its quota ----
const { cacheValue, aiCacheToDrop } = globalThis.DualSub;
assert.strictEqual(cacheValue({ t: 5, v: 'ترجمه' }), 'ترجمه');
assert.strictEqual(cacheValue('old plain value'), 'old plain value');
assert.deepStrictEqual(cacheValue({ t: 5, v: [{ start: 0 }] }), [{ start: 0 }]);
const entries = [['aic:new', { t: 300, v: 'x'.repeat(90) }], ['aic:old', { t: 100, v: 'x'.repeat(90) }], ['aic:plain', 'x'.repeat(90)], ['aic:mid', { t: 200, v: 'x'.repeat(90) }]];
assert.deepStrictEqual(aiCacheToDrop(entries, 700, 1000), []); // below 80%
assert.deepStrictEqual(aiCacheToDrop(entries, 800, 1000), ['aic:plain', 'aic:old', 'aic:mid']); // down to half: oldest first, untimed first of all
