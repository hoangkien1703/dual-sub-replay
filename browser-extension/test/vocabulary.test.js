// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
require('../data/vocabulary.js');
const { reviewInterval, DAY_MS } = globalThis.DualSub;

// The Android app's intervals: a new word, then a word already a day or more apart.
assert.strictEqual(reviewInterval(0, 'again'), 600_000);
assert.strictEqual(reviewInterval(0, 'hard'), DAY_MS);
assert.strictEqual(reviewInterval(0, 'good'), 3 * DAY_MS);
assert.strictEqual(reviewInterval(0, 'easy'), 7 * DAY_MS);
assert.strictEqual(reviewInterval(10 * DAY_MS, 'again'), 600_000);
assert.strictEqual(reviewInterval(10 * DAY_MS, 'hard'), 12 * DAY_MS);
assert.strictEqual(reviewInterval(10 * DAY_MS, 'good'), 20 * DAY_MS);
assert.strictEqual(reviewInterval(10 * DAY_MS, 'easy'), 30 * DAY_MS);
assert.strictEqual(reviewInterval(3000 * DAY_MS, 'easy'), 3650 * DAY_MS); // capped at ten years

// Practice transfer in the Android app's formats.
const { exportBackup, exportAnkiTsv, parseBackup, mergeImport } = globalThis.DualSub;
const pos = () => 'noun';
const saved = {
  id: 'a1', word: 'procrastination', pos: 'noun', translation: 'به تعویق انداختن', meanings: [], sentence: 'It is about procrastination.',
  sentenceTr: 'درباره تعلل است.', lang: 'en', target: 'fa', videoId: 'arj7oStGLkU', title: 'TED "talk"', time: 3, end: 6.5,
  added: 1, dueAt: 5, intervalMs: DAY_MS, notes: 'line one\nline two',
};
const backup = JSON.parse(exportBackup([saved]));
assert.strictEqual(backup.format, 'DualSubReplay');
assert.strictEqual(backup.version, 1);
assert.deepStrictEqual(backup.words[0], {
  id: 'a1', word: 'procrastination', reading: null, wordLanguage: 'en', meaningLanguage: 'fa', meaning: 'به تعویق انداختن',
  sentence: 'It is about procrastination.', translatedSentence: 'درباره تعلل است.', videoId: 'arj7oStGLkU', startMs: 3000, endMs: 6500,
  translated: false, online: true, dueAt: 5, intervalMs: DAY_MS, deck: null, sourceTitle: 'TED "talk"', notes: 'line one\nline two',
});
// Round trip keeps what a word needs.
const [back] = parseBackup(exportBackup([saved]), pos).words;
for (const key of ['id', 'word', 'translation', 'sentence', 'sentenceTr', 'lang', 'target', 'videoId', 'title', 'time', 'end', 'dueAt', 'intervalMs', 'notes']) {
  assert.deepStrictEqual(back[key], saved[key], key);
}
// An Android record (no meanings, personalNotes from Triple Sub Replay) imports; invalid ones are counted and skipped.
const android = JSON.stringify({
  format: 'DualSubReplay', version: 1,
  words: [
    { id: 'x', word: '食べる', reading: 'たべる', wordLanguage: 'ja', meaningLanguage: 'fa', meaning: 'خوردن', sentence: 'ご飯を食べる', translatedSentence: null,
      videoId: null, startMs: 0, endMs: 0, translated: false, online: false, dueAt: 0, intervalMs: 0, deck: null, sourceTitle: null, personalNotes: 'n' },
    { id: 'bad-language', word: 'w', wordLanguage: 'english', meaningLanguage: 'fa', meaning: 'm', sentence: '', startMs: 0, endMs: 0 },
    { id: 'bad-video', word: 'w', wordLanguage: 'en', meaningLanguage: 'fa', meaning: 'm', sentence: '', videoId: 'short', startMs: 0, endMs: 0 },
    { id: 'no-meaning', word: 'w', wordLanguage: 'en', meaningLanguage: 'fa', meaning: '', sentence: '', startMs: 0, endMs: 0 },
  ],
});
const parsed = parseBackup(android, pos);
assert.strictEqual(parsed.invalid, 3);
assert.strictEqual(parsed.words[0].notes, 'n');
assert.strictEqual(parsed.words[0].translation, 'خوردن');
assert.throws(() => parseBackup('{"format":"Other","version":1,"words":[]}', pos), /Unsupported/);
// Keep-existing: a saved id keeps the saved copy; repeating an import adds nothing.
const merged = mergeImport([saved], [{ ...saved, translation: 'changed' }, parsed.words[0], parsed.words[0]]);
assert.strictEqual(merged.added, 1);
assert.strictEqual(merged.duplicates, 2);
assert.strictEqual(merged.words.find((w) => w.id === 'a1').translation, 'به تعویق انداختن');
assert.strictEqual(mergeImport(merged.words, parsed.words).added, 0);
// Anki TSV: Android's header lines and columns, every field quoted, quotes doubled, a newline kept inside quotes.
const tsv = exportAnkiTsv([saved]).split('\n');
assert.deepStrictEqual(tsv.slice(0, 2), ['#separator:Tab', '#html:false']);
assert.strictEqual(tsv[2], '#columns:word\tmeaning\treading\twordLanguage\tmeaningLanguage\tsentence\ttranslatedSentence\tsource\tstartMs\tendMs\tdeck\tsourceTitle\tnotes\tthirdSentence\tthirdLanguage');
assert.ok(tsv[3].startsWith('"procrastination"\t"به تعویق انداختن"\t""\t"en"\t"fa"'));
assert.ok(tsv[3].includes('"https://www.youtube.com/watch?v=arj7oStGLkU"\t"3000"\t"6500"\t""\t"TED ""talk"""\t"line one'));
