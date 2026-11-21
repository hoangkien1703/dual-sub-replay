// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
const fs = require('node:fs');
for (const file of ['english.js', 'english-tagger.js', 'english-grammar-match.js', 'english-grammar.js']) require(`../data/${file}`);
const { englishLexicon, englishMorphemes, englishWords, readGisModel, tagEnglish, compileGrammar, matchEnglishGrammar, grammarForSelection, ENGLISH_GRAMMAR_CATALOGUE, ENGLISH_GRAMMAR_TEXT } = globalThis.DualSub;

const data = (name) => fs.readFileSync(`${__dirname}/../data/learner-data/${name}`);
const modelBytes = data('en-pos.model');
const model = readGisModel(modelBytes.buffer.slice(modelBytes.byteOffset, modelBytes.byteOffset + modelBytes.length));
const lexicon = englishLexicon(...['en-words.txt', 'en-exceptions.txt', 'en-levels.txt', 'en-ipa.txt'].map((n) => data(n).toString('utf8')));
const compiled = compileGrammar(ENGLISH_GRAMMAR_CATALOGUE);

const analyze = (text) => {
  const tokens = tagEnglish(model, text);
  const morphemes = englishMorphemes(tokens, lexicon);
  return { words: englishWords(tokens, morphemes, lexicon), grammar: matchEnglishGrammar(morphemes, compiled) };
};
const word = (text, w) => analyze(text).words.find((x) => x.text === w);

// Every example sentence of the Android app's EnglishGrammarCatalogueTest finds its own point.
const { examples } = JSON.parse(fs.readFileSync(`${__dirname}/english-grammar-examples.json`, 'utf8'));
assert.strictEqual(examples.length, 172);
const missed = examples.filter(([meaning, sentence]) => !analyze(sentence).grammar.some((g) => g.meaning === meaning));
assert.deepStrictEqual(missed, [], `${missed.length} examples did not find their point`);
// Every point has its explanation.
for (const [, , , meaning] of ENGLISH_GRAMMAR_CATALOGUE) assert.ok(ENGLISH_GRAMMAR_TEXT[meaning], meaning);

// Parts of speech in context: the same word as a noun and as a verb.
assert.strictEqual(word('Turn on the light.', 'light').pos, 'noun');
assert.strictEqual(word('They light the fire.', 'light').pos, 'verb');
// "It may rain": a noun-looking word after a modal is the verb (fixVerbsAfterHelpers).
assert.strictEqual(word('It may rain tomorrow.', 'rain').pos, 'verb');

// Dictionary forms through WordNet (morphy), levels by part of speech, IPA.
assert.strictEqual(word('The leaves are falling.', 'leaves').base, 'leaf');
assert.strictEqual(word('He leaves at six.', 'leaves').base, 'leave');
assert.deepStrictEqual(word('We went home.', 'went'), { start: 3, end: 7, text: 'went', pos: 'verb', base: 'go', level: 'A1', ipa: 'wɛnt' });
assert.strictEqual(word('She is happier now.', 'happier').base, 'happy');
assert.strictEqual(word('They were singing loudly.', 'singing').base, 'sing');
assert.strictEqual(word('I am hoping for rain.', 'hoping').base, 'hope');
assert.strictEqual(word("I don't know.", "don't").level, null);

// The card's points for one word: those it is part of, and one that starts right after it.
const line = "It's going to rain.";
const { grammar } = analyze(line);
const at = line.indexOf('going');
assert.ok(grammarForSelection(grammar, at, at + 'going'.length).some((g) => g.meaning === 'EN_GOING_TO' && g.level === 'A2'));
