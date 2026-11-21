// Words of a caption line: splitting, part of speech and dictionary meanings.
// Port of the Android app's data/LanguageAwareTokenizer.kt fallback rules (word lists and suffixes);
// its OpenNLP, Kuromoji and jieba analyzers are not ported (see docs/project/browser-extension.md).

// Label and color of each part of speech, as Android's PartOfSpeech. Colors are made for dark backgrounds.
const PARTS_OF_SPEECH = {
  noun: ['Noun', '#4FC3F7'],
  verb: ['Verb', '#81C784'],
  adjective: ['Adjective', '#FFB74D'],
  adverb: ['Adverb', '#BA68C8'],
  pronoun: ['Pronoun', '#4DD0E1'],
  conjunction: ['Conjunction', '#FF8A65'],
  preposition: ['Preposition', '#F06292'],
  particle: ['Particle / Grammar', '#E57373'],
  other: ['Other', '#CFD8DC'],
};

const set = (words) => new Set(words.split(' '));
const ENGLISH = {
  particle: set('the a an'),
  conjunction: set('and or but because both if so than until while as though although either neither since unless'),
  preposition: set('in on at by for with about against between into through during before after above below to from up down out off over under of via onto'),
  pronoun: set('i you he she it we they me him her us them my your his its our their mine yours hers ours theirs this that these those what which who whom whose myself yourself himself herself itself'),
  adverb: set('actually really very too quickly possibly now then here there again once just always never often sometimes soon almost quite already well away back still also'),
  adjective: set('good new first last long great little own other old right big high different small large next early young important few public bad same able quick brown lazy hot cold fast slow easy hard simple complex bright dark'),
  verb: set(
    'is am are was were be been being have has had having do does did doing say says said saying get gets got getting ' +
      'make makes made making go goes went going gone know knows knew knowing known take takes took taking taken ' +
      'see sees saw seeing seen come comes came coming think thinks thought thinking look looks looked looking ' +
      'want wants wanted wanting give gives gave giving given use uses used using find finds found finding ' +
      'tell tells told telling ask asks asked asking work works worked working seem seems seemed seeming ' +
      'feel feels felt feeling try tries tried trying leave leaves left leaving call calls called calling',
  ),
};

function englishPartOfSpeech(word) {
  const lower = word.toLowerCase().replace(/’/g, "'");
  for (const pos of ['particle', 'conjunction', 'preposition', 'pronoun', 'adverb', 'adjective', 'verb']) {
    if (ENGLISH[pos].has(lower)) return pos;
  }
  if (lower.endsWith('ly') && lower.length > 3) return 'adverb';
  if (/(ing|ed|ize|ify)$/.test(lower)) return 'verb';
  if (/(ful|less|able|ive|ous|al)$/.test(lower)) return 'adjective';
  return 'noun'; // also the -tion, -ment, -ness, -ity, -er, -or suffixes
}

const JAPANESE_PARTICLES = set('は が を に で へ と も から まで より の ね よ か な ぞ ぜ わ て ば');
const JAPANESE_ADJECTIVES = set('いい よい すごい 楽しい 嬉しい 美しい 美味しい おいしい 大きい 小さい 新しい 古い 高い 安い 難しい やさしい 優しい 好き 嫌い 綺麗 きれい 静か 元気 大切 大丈夫');
const JAPANESE_VERB_ENDINGS = 'ている ていた てある ておく ていく てくる ます ました ません たい たくない たかった られる させる ない なかった る く ぐ す つ ぬ ぶ む う'.split(' ');

function japanesePartOfSpeech(word) {
  if (JAPANESE_PARTICLES.has(word)) return 'particle';
  if (JAPANESE_ADJECTIVES.has(word) || (word.endsWith('い') && word.length > 1)) return 'adjective';
  if (JAPANESE_VERB_ENDINGS.some((e) => word.endsWith(e))) return 'verb';
  return 'noun';
}

// shortcut: only English and Japanese have rules; other languages stay 'other' until their analyzer is ported.
function partOfSpeech(word, lang) {
  const base = (lang || '').split('-')[0];
  if (base === 'en') return englishPartOfSpeech(word);
  if (base === 'ja') return japanesePartOfSpeech(word);
  return 'other';
}

// Splits a line into [{text, word, pos}] with the browser's own word breaker (also handles Japanese and Chinese).
function tokens(text, lang) {
  let segmenter;
  try {
    segmenter = new Intl.Segmenter(lang || undefined, { granularity: 'word' });
  } catch {
    segmenter = new Intl.Segmenter(undefined, { granularity: 'word' });
  }
  return [...segmenter.segment(text)].map((s) => ({ text: s.segment, word: !!s.isWordLike, pos: s.isWordLike ? partOfSpeech(s.segment, lang) : null }));
}

// Up to three parts of speech with three definitions each from a Wiktionary definition reply,
// preferring the caption language. [toText] turns Wiktionary's HTML into plain text.
function meanings(reply, lang, toText) {
  const groups = reply[(lang || '').split('-')[0]] || Object.values(reply).flat().filter((g) => g.language !== 'Translingual');
  return groups
    .map((g) => ({ pos: g.partOfSpeech, defs: (g.definitions || []).map((d) => toText(d.definition)).filter(Boolean).slice(0, 3) }))
    .filter((g) => g.defs.length)
    .slice(0, 3);
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { PARTS_OF_SPEECH, partOfSpeech, tokens, meanings });
