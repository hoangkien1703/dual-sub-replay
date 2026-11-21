// Apache OpenNLP's part-of-speech model (Universal Dependencies English EWT, maxent), run without the OpenNLP
// library. Port of the Android app's data/OpenNlpPosTagger.kt: the features are the ones OpenNLP 2.5.4 uses with
// this model's generator.featuregen, so the tags are the same. Tags are UD parts of speech (NOUN, VERB, AUX, …).
//
// OpenNLP runs a beam of 3, but the model caches each word's features from the first, best sequence that reaches
// it, so every sequence at a word gets the same scores: taking the best tag word by word gives the same tags.

// Reads OpenNLP's binary GIS model (pos.model): type, correction constants, outcomes, outcome patterns, predicates,
// weights. Strings are Java's DataOutput.writeUTF: a 2-byte length, then the bytes.
function readGisModel(buffer) {
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  const decoder = new TextDecoder();
  let at = 0;
  const utf = () => {
    const length = view.getUint16(at);
    at += 2;
    const text = decoder.decode(bytes.subarray(at, at + length));
    at += length;
    return text;
  };
  const int = () => ((at += 4), view.getInt32(at - 4));
  const double = () => ((at += 8), view.getFloat64(at - 8));
  if (utf() !== 'GIS') throw new Error('The model is not a GIS model.');
  int();
  double();
  const outcomes = Array.from({ length: int() }, utf);
  const patterns = Array.from({ length: int() }, () => utf().split(' ').filter(Boolean).map(Number));
  const predicates = Array.from({ length: int() }, utf);
  const parameters = new Map();
  let predicate = 0;
  for (const pattern of patterns) {
    const ids = pattern.slice(1);
    for (let n = 0; n < pattern[0]; n++) parameters.set(predicates[predicate++], { ids, weights: ids.map(double) });
  }
  return { outcomes, parameters };
}

const lowercase = (text) => Array.from(text, (c) => c.toLowerCase()).join(''); // per code point, as Java's

// OpenNLP's token class (FeatureGeneratorUtil.tokenFeature).
function tokenClass(token) {
  let initialCapital = false;
  let allCapitals = true;
  let allLowercase = true;
  let hiragana = true;
  let katakana = true;
  let digits = 0;
  let letter = false;
  const punctuation = new Set();
  Array.from(token).forEach((ch, index) => {
    if (/\p{L}/u.test(ch)) {
      letter = true;
      if (/\p{Lu}/u.test(ch)) {
        if (index === 0) initialCapital = true;
        allLowercase = false;
      } else allCapitals = false;
    } else {
      allCapitals = false;
      allLowercase = false;
      if (/\p{Nd}/u.test(ch)) {
        digits++;
        hiragana = false;
        katakana = false;
      }
      if (',./-'.includes(ch)) punctuation.add(ch);
    }
    if (/\p{Script=Common}/u.test(ch)) {
      if (!'・ー〜'.includes(ch)) hiragana = katakana = false;
    } else if (/\p{Script=Latin}/u.test(ch)) hiragana = katakana = false;
    else if (/\p{Script=Han}/u.test(ch)) hiragana = katakana = allLowercase = false;
    else if (/\p{Script=Hiragana}/u.test(ch)) katakana = allLowercase = false;
    else if (/\p{Script=Katakana}/u.test(ch)) hiragana = allLowercase = false;
  });
  if (hiragana) return 'jah';
  if (katakana) return 'jak';
  if (allLowercase) return 'lc';
  if (digits === 2) return '2d';
  if (digits === 4) return '4d';
  if (digits > 0) {
    if (letter) return 'an';
    if (punctuation.has('-')) return 'dd';
    if (punctuation.has('/')) return 'ds';
    if (punctuation.has(',')) return 'dc';
    if (punctuation.has('.')) return 'dp';
    return 'num';
  }
  if (allCapitals) return token.length === 1 ? 'sc' : 'ac';
  if (/^[A-ZÄÖÜ]\.$/.test(token)) return 'cp';
  if (initialCapital) return 'ic';
  return 'other';
}

// OpenNLP's features for [index]: prior, affixes, ±2-word window with sentence start, token class with word, previous tags.
function taggerFeatures(tokens, index, previousTags) {
  const features = ['def'];
  const token = tokens[index];
  for (let n = 1; n <= Math.min(4, token.length); n++) features.push(`suf=${token.slice(token.length - n)}`);
  for (let n = 1; n <= Math.min(4, token.length); n++) features.push(`pre=${token.slice(0, n)}`);
  features.push(`w=${lowercase(token)}`);
  for (let n = 1; n <= 2; n++) if (index - n >= 0) features.push(`p${n}w=${lowercase(tokens[index - n])}`);
  for (let n = 1; n <= 2; n++) if (index + n < tokens.length) features.push(`n${n}w=${lowercase(tokens[index + n])}`);
  if (index === 0) features.push('S=begin');
  for (let n = 1; n <= 2; n++) if (index - n === 0) features.push(`p${n}S=begin`);
  const shape = tokenClass(token);
  features.push(`wc=${shape}`, `w&c=${lowercase(token)},${shape}`);
  if (index >= 1) {
    features.push(`t=${previousTags[index - 1]}`);
    if (index >= 2) features.push(`t2=${previousTags[index - 2]},${previousTags[index - 1]}`);
  }
  return features;
}

// The UD tag of each of [tokens].
function tagTokens(model, tokens) {
  const tags = [];
  for (let index = 0; index < tokens.length; index++) {
    const sums = new Float64Array(model.outcomes.length);
    for (const feature of taggerFeatures(tokens, index, tags)) {
      const context = model.parameters.get(feature);
      if (context) context.ids.forEach((id, i) => (sums[id] += context.weights[i]));
    }
    let best = 0;
    for (let i = 1; i < sums.length; i++) if (sums[i] > sums[best]) best = i; // softmax keeps the order, so the largest sum wins
    tags.push(model.outcomes[best]);
  }
  return tags;
}

// Same words as the spaced-language tokenizer, plus each punctuation mark the model expects to see.
const ENGLISH_TOKEN = /([\p{L}\p{M}\p{N}_]+(?:['’-]+[\p{L}\p{M}\p{N}_]+)*)|[^\s\p{L}\p{M}\p{N}_]/gu;

// [text]'s words and punctuation with their tags: [{ text, start, tag, isWord }].
function tagEnglish(model, text) {
  const matches = [...text.matchAll(ENGLISH_TOKEN)];
  const tags = tagTokens(model, matches.map((m) => m[0].replace(/’/g, "'")));
  return matches.map((m, i) => ({ text: m[0], start: m.index, tag: tags[i], isWord: m[1] != null }));
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { readGisModel, tokenClass, tagEnglish });
