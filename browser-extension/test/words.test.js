// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
require('../data/words.js');
const { tokens, meanings, partOfSpeech } = globalThis.DualSub;

const words = (text, lang) => tokens(text, lang).filter((t) => t.word).map((t) => t.text);
assert.deepStrictEqual(words("I don't know, really.", 'en'), ["I", "don't", 'know', 'really']);
assert.ok(words('ご飯を食べる', 'ja').length >= 3); // Japanese has no spaces; the segmenter still splits it
assert.strictEqual(tokens('a, b', 'not a lang!').map((t) => t.text).join(''), 'a, b');

const strip = (html) => html.replace(/<[^>]+>/g, '').trim();
const wiki = {
  en: [
    { partOfSpeech: 'Verb', language: 'English', definitions: [{ definition: 'to <a>move</a> fast' }, { definition: '<span></span>' }] },
    { partOfSpeech: 'Noun', language: 'English', definitions: [{ definition: 'a race' }] },
  ],
  ja: [{ partOfSpeech: 'Verb', language: 'Japanese', definitions: [{ definition: 'to eat' }] }],
};
assert.deepStrictEqual(meanings(wiki, 'en-US', strip), [
  { pos: 'Verb', defs: ['to move fast'] },
  { pos: 'Noun', defs: ['a race'] },
]);
assert.deepStrictEqual(meanings(wiki, 'ja', strip), [{ pos: 'Verb', defs: ['to eat'] }]);
assert.strictEqual(meanings({ mul: [{ partOfSpeech: 'Symbol', language: 'Translingual', definitions: [{ definition: 'x' }] }] }, 'fr', strip).length, 0);

// Part of speech, as the Android app's LanguageAwareTokenizer fallback rules.
const pos = (text, lang) => tokens(text, lang).filter((t) => t.word).map((t) => `${t.text}:${t.pos}`);
assert.deepStrictEqual(pos('The lazy dog quickly ran to them and barked', 'en'), [
  'The:particle', 'lazy:adjective', 'dog:noun', 'quickly:adverb', 'ran:noun', 'to:preposition', 'them:pronoun', 'and:conjunction', 'barked:verb',
]);
assert.strictEqual(partOfSpeech('It’s', 'en-GB'), 'noun'); // curly apostrophe is normalized, the word stays whole
assert.strictEqual(partOfSpeech('は', 'ja'), 'particle');
assert.strictEqual(partOfSpeech('食べる', 'ja'), 'verb');
assert.strictEqual(partOfSpeech('楽しい', 'ja'), 'adjective');
assert.strictEqual(partOfSpeech('casa', 'es'), 'other'); // no rules yet for other languages
