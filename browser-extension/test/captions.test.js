// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
require('../data/captions.js');
require('../data/words.js');
const { parseCaptions, splitAtSentenceEnds, sentenceLines, wordTimes, activeLine, batches, tokens } = globalThis.DualSub;

// An auto-generated (asr) json3 track: rolling lines overlap, append events carry only "\n".
const asr = JSON.stringify({
  events: [
    { tStartMs: 0, dDurationMs: 50000, id: 1, wpWinPosId: 1 },
    { tStartMs: 1000, dDurationMs: 4000, segs: [{ utf8: 'hello' }, { utf8: ' world', tOffsetMs: 400 }] },
    { tStartMs: 3000, dDurationMs: 10, aAppend: 1, segs: [{ utf8: '\n' }] },
    { tStartMs: 3000, dDurationMs: 4000, segs: [{ utf8: 'second\nline' }] },
  ],
});
const lines = parseCaptions(asr);
assert.deepStrictEqual(lines, [
  { start: 1, end: 3, text: 'hello world', words: [{ text: 'hello', time: 1 }, { text: 'world', time: 1.4 }] },
  { start: 3, end: 7, text: 'second line' },
]);

assert.strictEqual(activeLine(lines, 0.5), -1);
assert.strictEqual(activeLine(lines, 1), 0);
assert.strictEqual(activeLine(lines, 3), 1);
assert.strictEqual(activeLine(lines, 7), -1);

assert.deepStrictEqual(batches(['aa', 'bb', 'cc'], 2), [['aa', 'bb'], ['cc']]);
assert.deepStrictEqual(batches(['aaa', 'bbb'], 50, 5), [['aaa'], ['bbb']]);

// Sentence joining: the Android app's TranslationCoordinatorTest cases (times in seconds).
const rows = (...pieces) => pieces.map(([start, end, text]) => ({ start, end, text }));
const joined = (...pieces) => sentenceLines(rows(...pieces)).map((l) => l.text);

// Punctuation and a long silence keep unrelated sentences apart.
assert.deepStrictEqual(joined([0, 1, 'Done.'], [1, 2, 'Next fragment'], [4, 5, 'a separate thought']), ['Done.', 'Next fragment', 'a separate thought']);
// Japanese joins without spaces, and the joined line keeps the first start and the last end.
assert.deepStrictEqual(sentenceLines(rows([0, 1, '雨が降っていたので'], [1, 2, '出かけるのをやめました。'])), [{ start: 0, end: 2, text: '雨が降っていたので出かけるのをやめました。' }]);
// An unfinished Japanese piece joins the next after a pause of up to 3 s when the captions mark ends with 。.
assert.deepStrictEqual(
  joined([0, 0.8, 'はい。'], [0.9, 1.5, 'とても'], [3.5, 5, '楽しい場所です。'], [6, 7.5, '私の前のアパートから'], [9.5, 10.2, '梅田で'], [12, 13.5, '自転車で10分ぐらいです。']),
  ['はい。', 'とても楽しい場所です。', '私の前のアパートから梅田で自転車で10分ぐらいです。'],
);
// Without sentence marks the short pause stays; a pause over 3 s still ends the line.
assert.deepStrictEqual(joined([0, 1, 'とても'], [3, 4, '楽しい場所です']), ['とても', '楽しい場所です']);
assert.deepStrictEqual(joined([0, 1, 'とても'], [4.5, 5.5, '楽しい場所です。']), ['とても', '楽しい場所です。']);
// Auto-caption fragments of one sentence become one line.
assert.deepStrictEqual(joined([0, 1.5, 'so what I want to'], [1.5, 3, 'tell you about today is'], [3, 4.5, 'how we think.']), ['so what I want to tell you about today is how we think.']);
// Unpunctuated captions stop joining at 120 characters...
const long = joined([0, 3, 'Traditional LMS can take three to over 300 seconds'], [3, 6, 'to do the type of classification work that this model can'], [6, 7, 'and more words here to pass the limit']);
assert.ok(long.length > 1 && long.every((t) => t.length <= 120));
// ...but a short lowercase sentence end still joins the sentence before it.
assert.deepStrictEqual(joined([0, 8, 'x'.repeat(110)], [8, 9, 'a way.']), [`${'x'.repeat(110)} a way.`]);

// A piece is cut at a sentence end inside it, then joined with what follows (Android's interiorSentenceEnds test).
const interior = sentenceLines(splitAtSentenceEnds(rows([0, 4, 'The three models, but you are paying a fraction of the price. Even at, look at'], [4, 5, 'this.'])));
assert.deepStrictEqual(interior.map((l) => l.text), ['The three models, but you are paying a fraction of the price.', 'Even at, look at this.']);
assert.ok(interior[1].start > 0 && interior[1].start < 4);
// Japanese breaks right after 。 with no space.
assert.deepStrictEqual(splitAtSentenceEnds(rows([0, 2, 'はい。そうです。'])).map((l) => l.text), ['はい。', 'そうです。']);
// Timed words give the cut time and stay with their sentence.
const timed = splitAtSentenceEnds([{ start: 0, end: 3, text: 'Done. Next one', words: [{ text: 'Done.', time: 0 }, { text: 'Next', time: 2 }, { text: 'one', time: 2.5 }] }]);
assert.deepStrictEqual(timed.map((l) => [l.start, l.end, l.text, l.words.length]), [[0, 2, 'Done.', 1], [2, 3, 'Next one', 2]]);

// Spoken-word times: each word token gets the time of the chunk it is in; joined lines keep both lines' words.
const spoken = sentenceLines(parseCaptions(asr).slice(0, 1).concat({ start: 3, end: 4, text: 'again', words: [{ text: 'again', time: 3.2 }] }))[0];
assert.strictEqual(spoken.text, 'hello world again');
assert.deepStrictEqual(wordTimes(spoken, tokens(spoken.text, 'en')), [1, null, 1.4, null, 3.2]);
assert.strictEqual(wordTimes({ text: 'no timing', start: 0, end: 1 }, tokens('no timing', 'en')), null);

// Word times ignore case: an AI sentence starts with a capital the captions' word lacks.
const capital = { start: 0, end: 2, text: 'Then I wrote.', words: [{ text: 'then', time: 0 }, { text: 'i', time: 0.5 }, { text: 'wrote.', time: 1 }] };
assert.deepStrictEqual(wordTimes(capital, tokens(capital.text, 'en')), [0, null, 0.5, null, 1, null]);
