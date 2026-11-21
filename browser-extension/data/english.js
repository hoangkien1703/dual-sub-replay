// English analysis: dictionary forms (WordNet), word levels (CEFR-J), IPA, and the pieces grammar is read from.
// Port of the Android app's data/EnglishLexicon.kt and data/EnglishMorphology.kt; tags come from
// data/english-tagger.js. Data files are in data/learner-data/ (credits in THIRD_PARTY_NOTICES.md).
// OpenNLP tags and WordNet; here a form made by its spelling rules counts only when the word data knows it.

// base past past-participle; alternatives joined by "/" (Android's IRREGULAR_VERBS).
const IRREGULAR_VERBS =
  'be was/were been,have had had,do did done,go went gone,get got got/gotten,make made made,say said said,' +
  'see saw seen,take took taken,come came come,know knew known,think thought thought,give gave given,' +
  'find found found,tell told told,become became become,leave left left,feel felt felt,bring brought brought,' +
  'begin began begun,keep kept kept,hold held held,write wrote written,stand stood stood,hear heard heard,' +
  'let let let,mean meant meant,set set set,meet met met,run ran run,pay paid paid,sit sat sat,' +
  'speak spoke spoken,lie lay lain,lead led led,read read read,grow grew grown,lose lost lost,fall fell fallen,' +
  'send sent sent,build built built,understand understood understood,draw drew drawn,break broke broken,' +
  'spend spent spent,cut cut cut,rise rose risen,drive drove driven,buy bought bought,wear wore worn,' +
  'choose chose chosen,seek sought sought,throw threw thrown,catch caught caught,deal dealt dealt,win won won,' +
  'forget forgot forgotten,lay laid laid,sell sold sold,fight fought fought,eat ate eaten,sleep slept slept,' +
  'teach taught taught,ride rode ridden,hide hid hidden,shoot shot shot,swim swam swum,sing sang sung,' +
  'drink drank drunk,fly flew flown,forgive forgave forgiven,hang hung hung,hit hit hit,hurt hurt hurt,' +
  'put put put,quit quit quit,shut shut shut,sink sank sunk,steal stole stolen,stick stuck stuck,' +
  'strike struck struck,swear swore sworn,sweep swept swept,tear tore torn,wake woke woken,bite bit bitten,' +
  'blow blew blown,feed fed fed,freeze froze frozen,bet bet bet,bend bent bent,bleed bled bled,dig dug dug,' +
  'shake shook shaken,shine shone shone,spin spun spun,spread spread spread,bear bore born,beat beat beaten,' +
  'burn burnt burnt,learn learnt learnt,smell smelt smelt,spell spelt spelt,lend lent lent,show showed shown,' +
  'prove proved proven,cost cost cost,hide hid hidden,ring rang rung,arise arose arisen,awake awoke awoken,' +
  'forbid forbade forbidden,overcome overcame overcome,undertake undertook undertaken,withdraw withdrew withdrawn,' +
  'mistake mistook mistaken,upset upset upset,light lit lit,slide slid slid,sell sold sold,dream dreamt dreamt,' +
  'kneel knelt knelt,lean leant leant,leap leapt leapt,fit fit fit,split split split,cast cast cast,' +
  'broadcast broadcast broadcast,bind bound bound,grind ground ground,wind wound wound,flee fled fled,' +
  'sting stung stung,swing swung swung,hang hung hung,weep wept wept,creep crept crept,sew sewed sewn';

// Base forms that look like an -ed, -ing or -s form (Android's BASE_FORMS_WITH_SUFFIX_SPELLING).
const SUFFIX_LOOKALIKES =
  'need succeed proceed exceed speed seed heed weed breed shed wed embed bed string spring bring sing ring sting swing cling fling sling wring ' +
  'focus discuss miss pass kiss guess press express dress cross toss bless possess process address access stress confess impress bias';

// Android's IRREGULAR: verb dictionary forms, and which irregular forms are past only, participle only, or both.
const IRREGULAR = (() => {
  const lemmas = {};
  const past = new Set();
  const pp = new Set();
  for (const w of SUFFIX_LOOKALIKES.split(' ')) lemmas[w] = w;
  for (const entry of IRREGULAR_VERBS.split(',')) {
    const [base, pastForms, ppForms] = entry.split(' ');
    lemmas[base] ??= base;
    for (const form of pastForms.split('/')) past.add(form), (lemmas[form] ??= base);
    for (const form of ppForms.split('/')) pp.add(form), (lemmas[form] ??= base);
  }
  Object.assign(lemmas, { am: 'be', is: 'be', are: 'be', being: 'be', has: 'have', having: 'have', does: 'do', doing: 'do', goes: 'go', dying: 'die', lying: 'lie', tying: 'tie', seeing: 'see' });
  return {
    lemmas,
    pastOnly: new Set([...past].filter((f) => !pp.has(f))),
    ppOnly: new Set([...pp].filter((f) => !past.has(f))),
    both: new Set([...past].filter((f) => pp.has(f))),
  };
})();

const IRREGULAR_PLURALS = { children: 'child', men: 'man', women: 'woman', feet: 'foot', teeth: 'tooth', mice: 'mouse', geese: 'goose' };
const COMPARISON_LEMMAS = { better: 'good', best: 'good', worse: 'bad', worst: 'bad', further: 'far', farther: 'far' };
const ADVERB_COMPARATIVES = new Set('more less better worse faster harder sooner later earlier longer further farther closer higher lower easier louder quicker slower'.split(' '));

const ES_ENDINGS = ['ss', 'sh', 'ch', 'x', 'zz', 'o'];
const KEEP_DOUBLE = new Set(['add', 'odd', 'err', 'ebb', 'buzz', 'fizz', 'purr']);
const E_ENDINGS = [/[^aeiou]at$/, /[^aeiou]id$/, /[^aeiou]ud$/, /ir$/, /[^aeiou]ur$/, /iz$/, /[^aeiou][aeiou]s$/, /[^aeiou]in$/, /[^aeiou]ib$/, /[^aeiou]ar$/, /[^aeiou]um$/, /[^aeiou]os$/, /[^aeiou]ag$/, /[^aeiou]l$/];
const NO_SILENT_E = new Set(['focus', 'bias', 'visit', 'travel', 'cancel', 'label', 'model', 'level', 'signal', 'total', 'fuel', 'feel']);
const E_STEMS = new Set(['creat', 'chang', 'arrang', 'challeng', 'exchang', 'rang', 'complet', 'delet', 'compet', 'explor', 'ignor', 'ador', 'stor']);

const needsSilentE = (stem) =>
  !NO_SILENT_E.has(stem) &&
  (/(u|v|c|dg)$/.test(stem) || E_STEMS.has(stem) || /^[^aeiou]*[aeiou][^aeiouwxy]$/.test(stem) ||
    (stem.length > 4 && E_ENDINGS.some((r) => r.test(stem)) && !/(al|el)$/.test(stem)));

// Undoes the spelling changes of -ed and -ing: stopp → stop, liv → live, agre(ed) → agree.
function regularStem(stem, suffix) {
  if (KEEP_DOUBLE.has(stem)) return stem;
  if (stem.length > 2 && stem.at(-1) === stem.at(-2) && 'bdgkmnprtz'.includes(stem.at(-1))) return stem.slice(0, -1);
  if (suffix === 'ed' && stem.endsWith('e')) return `${stem}e`;
  if ('aeioy'.includes(stem.at(-1))) return stem;
  return needsSilentE(stem) ? `${stem}e` : stem;
}

// The dictionary form of a verb by its spelling, for helper verbs and words WordNet does not know.
function spelledVerbLemma(word) {
  if (IRREGULAR.lemmas[word]) return IRREGULAR.lemmas[word];
  if (word.endsWith('ied') && word.length > 4) return `${word.slice(0, -3)}y`;
  if (word.endsWith('ies') && word.length > 4) return `${word.slice(0, -3)}y`;
  if (word.endsWith('ed') && word.length > 3) return regularStem(word.slice(0, -2), 'ed');
  if (word.endsWith('ing') && word.length > 4) return regularStem(word.slice(0, -3), 'ing');
  if (word.endsWith('es') && word.length > 3 && ES_ENDINGS.some((e) => word.slice(0, -2).endsWith(e))) return word.slice(0, -2);
  if (word.endsWith('s') && !/(ss|us)$/.test(word) && word.length > 2) return word.slice(0, -1);
  return word;
}

// -ed and -ing stems: a doubled consonant undone, a silent e put back, or neither.
function stemCandidates(stem) {
  if (!stem) return [];
  const doubled = stem.length > 2 && stem.at(-1) === stem.at(-2) && !'aeiou'.includes(stem.at(-1));
  return [doubled && stem.slice(0, -1), `${stem}e`, stem].filter(Boolean);
}

function verbCandidates(word) {
  if (word.endsWith('ies')) return [`${word.slice(0, -3)}y`, word.slice(0, -1)];
  if (word.endsWith('es')) return [word.slice(0, -1), word.slice(0, -2)];
  if (word.endsWith('s')) return [word.slice(0, -1)];
  if (word.endsWith('ied')) return [`${word.slice(0, -3)}y`, word.slice(0, -1)];
  if (word.endsWith('ed')) return stemCandidates(word.slice(0, -2));
  if (word.endsWith('ing')) {
    const c = stemCandidates(word.slice(0, -3));
    return word.endsWith('nging') ? c.reverse() : c; // singing → sing, not singe; hoping → hope, not hop
  }
  return [];
}

// leaves → leaf, wives → wife, boxes → box, cities → city, years → year.
const nounCandidates = (word) =>
  [word.endsWith('ves') && `${word.slice(0, -3)}f`, word.endsWith('ves') && `${word.slice(0, -3)}fe`, word.endsWith('ies') && `${word.slice(0, -3)}y`,
    word.slice(0, -1), word.endsWith('es') && word.slice(0, -2)].filter(Boolean);

function comparedCandidates(word) {
  const stem = word.endsWith('est') ? word.slice(0, -3) : word.endsWith('er') ? word.slice(0, -2) : null;
  if (!stem || stem.length < 3) return [];
  return stem.endsWith('i') ? [`${stem.slice(0, -1)}y`] : stemCandidates(stem).filter((c) => c.length >= 3);
}

// Android's EnglishLexicon over "word parts" (WordNet), "form part base…" (WordNet exceptions), "word part level"
// (CEFR-J) and "word<TAB>ipa" lines.
function englishLexicon(wordsText, exceptionsText, levelsText, ipaText) {
  const words = new Map();
  for (const line of wordsText.split('\n')) {
    const space = line.indexOf(' ');
    if (space > 0) words.set(line.slice(0, space), line.slice(space + 1));
  }
  const exceptions = new Map();
  for (const line of exceptionsText.split('\n')) {
    const f = line.split(' ');
    if (f.length >= 3 && !exceptions.has(`${f[0]} ${f[1]}`)) exceptions.set(`${f[0]} ${f[1]}`, f[2]);
  }
  const levels = new Map();
  for (const line of levelsText.split('\n')) {
    const f = line.trim().split(' ');
    if (!line.startsWith('#') && f.length === 3) levels.set(`${f[0]} ${f[1]}`, f[2]);
  }
  const ipa = new Map();
  for (const line of ipaText.split('\n')) {
    const [w, sounds] = line.split('\t');
    if (sounds && !line.startsWith('#')) ipa.set(w, sounds.split(',')[0]);
  }
  const isWord = (w, part) => (words.get(w) || '').includes(part);
  return {
    isWord,
    ipa: (w) => ipa.get(w) || null,
    // The CEFR-J level of [lemma] as [part] (n, v, a, r or f), else its lowest level as any part.
    level: (lemma, part) => levels.get(`${lemma} ${part}`) || ['n', 'v', 'a', 'r', 'f'].map((p) => levels.get(`${lemma} ${p}`)).filter(Boolean).sort()[0] || null,
    verbLemma(w) {
      return IRREGULAR.lemmas[w] || exceptions.get(`${w} v`) || verbCandidates(w).find((c) => isWord(c, 'v')) || (isWord(w, 'v') ? w : spelledVerbLemma(w));
    },
    // Plurals become singular, except a plural the CEFR-J list has as a word of its own (glasses, news, clothes).
    nounLemma(w) {
      if (IRREGULAR_PLURALS[w]) return IRREGULAR_PLURALS[w];
      if (exceptions.has(`${w} n`)) return exceptions.get(`${w} n`);
      if (!w.endsWith('s') || w.endsWith('ss') || levels.has(`${w} n`)) return w;
      return nounCandidates(w).find((c) => isWord(c, 'n')) || (isWord(w, 'n') ? w : null) || (/(us|is)$/.test(w) || w.length <= 3 ? w : w.slice(0, -1));
    },
    // bigger → big, happiest → happy; a word that is an adjective itself (clever, silver) stays.
    adjectiveLemma(w) {
      if (COMPARISON_LEMMAS[w]) return COMPARISON_LEMMAS[w];
      if (exceptions.has(`${w} a`)) return exceptions.get(`${w} a`);
      if (levels.has(`${w} a`)) return w;
      return comparedCandidates(w).find((c) => isWord(c, 'a')) || w;
    },
    // faster → fast, sooner → soon, better → well; other adverbs (after, never) stay as they are.
    adverbLemma(w) {
      if (w === 'better' || w === 'best') return 'well';
      if (COMPARISON_LEMMAS[w]) return COMPARISON_LEMMAS[w];
      if (exceptions.has(`${w} r`)) return exceptions.get(`${w} r`);
      if (!ADVERB_COMPARATIVES.has(w) && (levels.has(`${w} r`) || (isWord(w, 'r') && !isWord(w, 'a')))) return w;
      return comparedCandidates(w).find((c) => isWord(c, 'a') || isWord(c, 'r')) || w;
    },
  };
}

// ---- Pieces for grammar: Android's englishMorphemesOf ----

// Endings split off a word, longest first, with the tag each has when the model gave only one.
const CONTRACTION_ENDINGS = [["n't", 'PART'], ["'re", 'AUX'], ["'ve", 'AUX'], ["'ll", 'AUX'], ["'s", 'AUX'], ["'d", 'AUX'], ["'m", 'AUX']];
const FUSED_WORDS = { gonna: 3, wanna: 3, gotta: 3, cannot: 3 }; // gonna = gon + na (going to)
const SUBJECT_PRONOUNS = new Set(['i', 'you', 'he', 'she', 'it', 'we', 'they']);
const IS_CONTRACTIONS = new Set(['it', 'that', 'what', 'there', 'here', 'he', 'she', 'who', 'where', 'how', 'when', 'why', 'this']);
const INFINITIVE_LEADS = new Set('going gon ought used able supposed want wants wanted need needs needed have has had got try tries tried trying'.split(' '));
const GOING_TO_PLACES = new Set('school work bed church class college university hospital jail prison town court war sea dinner lunch breakfast market office'.split(' '));
const NOT_A_VERB_TAGS = new Set(['NOUN', 'ADJ']);
const WORDS_BEFORE_VERB = new Set(['not', "n't", 'better', 'rather']);
const MODALS = new Set(['can', 'ca', 'could', 'will', 'wo', 'would', 'shall', 'sha', 'should', 'may', 'might', 'must', "'ll"]);
const CONTRACTED_LEMMAS = { "n't": 'not', "'re": 'be', "'m": 'be', "'ve": 'have', "'ll": 'will', ca: 'can', wo: 'will', sha: 'shall', ai: 'be', gon: 'go', wan: 'want', na: 'to', ta: 'to', not: 'not' };
const MAX_GAP = 3;

// Lowercase that keeps the length, so offsets still point into the line.
const lowerSameLength = (text) => {
  const lower = text.toLowerCase();
  return lower.length === text.length ? lower : text;
};

function splitContraction(token) {
  const lower = lowerSameLength(token.text).replace(/’/g, "'");
  const tags = token.tag.split('+');
  const ending = CONTRACTION_ENDINGS.find(([e]) => lower.endsWith(e) && lower.length > e.length);
  const cut = FUSED_WORDS[lower] ?? (ending ? lower.length - ending[0].length : null);
  if (!token.isWord || cut == null) return [{ text: lower, start: token.start, tag: tags[0] }];
  const base = lower.slice(0, cut);
  const end = lower.slice(cut);
  // The model tags a few whole contractions oddly (I'd as SCONJ); the pronoun part is a pronoun.
  const baseTag = tags.length === 1 && SUBJECT_PRONOUNS.has(base) ? 'PRON' : tags[0];
  let endTag;
  if (tags.length > 1) endTag = tags[1];
  else if (end !== "'s") endTag = (CONTRACTION_ENDINGS.find(([e]) => e === end) || [])[1] || 'PART';
  else if (base === 'let') endTag = 'PRON';
  else if (IS_CONTRACTIONS.has(base)) endTag = 'AUX';
  else return [{ text: lower, start: token.start, tag: tags[0] }]; // a possessive stays part of its noun
  return [{ text: base, start: token.start, tag: baseTag }, { text: end, start: token.start + cut, tag: endTag }];
}

// After a modal, and after "to" that follows going, ought, used, want, need, have or got, a noun or adjective is the verb.
function fixVerbsAfterHelpers(pieces) {
  const fixed = pieces.map((p) => ({ ...p }));
  for (let i = 0; i < fixed.length; i++) {
    const piece = fixed[i];
    if (piece.tag === 'AUX' && (MODALS.has(piece.text) || piece.text === "'d")) {
      let next = i + 1;
      while (next < fixed.length && (fixed[next].tag === 'ADV' || WORDS_BEFORE_VERB.has(fixed[next].text))) next++;
      if (next < fixed.length && NOT_A_VERB_TAGS.has(fixed[next].tag)) fixed[next].tag = 'VERB';
    }
    const lead = fixed[i - 1]?.text;
    const verb = fixed[i + 1];
    const beforeLead = fixed[i - 2];
    const accustomed = lead === 'used' && beforeLead != null && ['be', 'get'].includes(spelledVerbLemma(CONTRACTED_LEMMAS[beforeLead.text] ?? beforeLead.text));
    if (piece.text === 'to' && INFINITIVE_LEADS.has(lead) && !accustomed && verb && NOT_A_VERB_TAGS.has(verb.tag)) {
      if (lead === 'going' && GOING_TO_PLACES.has(verb.text)) continue;
      piece.tag = 'PART';
      verb.tag = 'VERB';
    }
  }
  return fixed;
}

// The next helper or main verb after [index], skipping not and adverbs.
function nextVerb(pieces, index) {
  const next = pieces.slice(index + 1, index + 1 + MAX_GAP).find((p) => p.tag !== 'ADV' && p.tag !== 'PART');
  return next && (next.tag === 'VERB' || next.tag === 'AUX') ? next : null;
}

// Whether a form of have, be or get comes just before [index] (only not, adverbs and a subject between).
function afterHelper(pieces, index) {
  for (let i = index - 1; i >= Math.max(0, index - MAX_GAP - 1); i--) {
    const p = pieces[i];
    if (p.tag === 'ADV' || p.tag === 'PRON' || p.text === 'not' || p.text === "n't") continue;
    if (p.tag !== 'AUX' && p.tag !== 'VERB') return false;
    const lemma = CONTRACTED_LEMMAS[p.text] ?? (p.text === "'s" || p.text === "'d" ? 'have' : spelledVerbLemma(p.text));
    return ['have', 'be', 'get'].includes(lemma);
  }
  return false;
}

function verbForm(word, lemma, pieces, index) {
  if (MODALS.has(word) || (word === "'d" && lemma === 'would')) return 'MODAL';
  if (['am', 'is', 'are', "'m", "'re"].includes(word) || (word === "'s" && lemma === 'be')) return 'PRES';
  if (word === 'was' || word === 'were') return 'PAST';
  if (word === "'s") return 'S3';
  if (word === "'d") return 'PAST';
  if (word === 'been') return 'PP';
  // come, run, put: the base form is also a participle after have.
  if (word === lemma) return (IRREGULAR.ppOnly.has(word) || IRREGULAR.both.has(word)) && afterHelper(pieces, index) ? 'PP' : 'BASE';
  if (IRREGULAR.ppOnly.has(word)) return 'PP';
  if (IRREGULAR.pastOnly.has(word)) return 'PAST';
  if (IRREGULAR.both.has(word) || word.endsWith('ed')) return afterHelper(pieces, index) ? 'PP' : 'PAST';
  if (word.endsWith('ing')) return 'ING';
  if (word.endsWith('s')) return 'S3';
  return 'BASE';
}

function englishLemma(lexicon, piece, pieces, index) {
  const word = piece.text;
  if (piece.tag === 'PUNCT') return word;
  if (CONTRACTED_LEMMAS[word]) return CONTRACTED_LEMMAS[word];
  if (word === "'d") {
    const verb = nextVerb(pieces, index);
    return verb && verbForm(verb.text, spelledVerbLemma(verb.text), pieces, pieces.indexOf(verb)) === 'PP' ? 'have' : 'would';
  }
  if (word === "'s") {
    if (piece.tag !== 'AUX') return word;
    const next = nextVerb(pieces, index)?.text;
    return next === 'been' || next === 'got' ? 'have' : 'be';
  }
  if (MODALS.has(word)) return word;
  switch (piece.tag) {
    case 'VERB':
    case 'AUX':
      return lexicon.verbLemma(word);
    case 'ADV':
      return lexicon.adverbLemma(word);
    case 'ADJ':
      return lexicon.adjectiveLemma(word);
    case 'NOUN':
      return lexicon.nounLemma(word.replace(/'s$/, '').replace(/'$/, '')); // the teacher's → teacher
    default:
      return word;
  }
}

function englishForm(piece, lemma, pieces, index) {
  const w = piece.text;
  switch (piece.tag) {
    case 'VERB':
    case 'AUX':
      return verbForm(w, lemma, pieces, index);
    case 'ADJ':
      if (['better', 'worse', 'more', 'less', 'further', 'farther'].includes(w)) return 'COMP';
      if (['best', 'worst', 'most', 'least'].includes(w)) return 'SUP';
      if (lemma !== w && w.endsWith('er')) return 'COMP';
      if (lemma !== w && w.endsWith('est')) return 'SUP';
      return '*';
    case 'ADV':
      if (ADVERB_COMPARATIVES.has(w)) return 'COMP';
      if (['most', 'least', 'best', 'worst'].includes(w)) return 'SUP';
      return w.endsWith('ly') && w.length > 3 ? 'LY' : '*';
    case 'NOUN':
      return lemma !== w.replace(/'s$/, '').replace(/'$/, '') ? 'PL' : '*';
    default:
      return '*';
  }
}

// The pieces grammar patterns read: { surface (lowercase), start, end, pos (UD tag), form, base }.
// A contraction is split into its parts (can't → ca + n't, I'm → i + 'm) so its helper verb counts.
function englishMorphemes(tokens, lexicon) {
  const pieces = fixVerbsAfterHelpers(tokens.flatMap(splitContraction));
  const lemmas = pieces.map((p, i) => englishLemma(lexicon, p, pieces, i));
  return pieces.map((p, i) => ({ surface: p.text, start: p.start, end: p.start + p.text.length, pos: p.tag, form: englishForm(p, lemmas[i], pieces, i), base: lemmas[i] }));
}

// The extension's part of speech for a UD tag; determiners are grammar words, except this/that/these/those.
function englishPartOfSpeech(tag, lower) {
  const parts = { NOUN: 'noun', PROPN: 'noun', VERB: 'verb', AUX: 'verb', ADJ: 'adjective', ADV: 'adverb', PRON: 'pronoun', ADP: 'preposition', CCONJ: 'conjunction', SCONJ: 'conjunction', PART: 'particle' };
  if (tag === 'DET') return ['this', 'that', 'these', 'those'].includes(lower) ? 'pronoun' : 'particle';
  return parts[tag] || 'other';
}

const LEVEL_PART = { noun: 'n', verb: 'v', adjective: 'a', adverb: 'r' };

// Each word of [tokens] with its part of speech (by its first piece's tag, so a verb after a helper shows as a
// verb), dictionary form, CEFR-J level and IPA, as Android's englishWords and englishWordLevel.
function englishWords(tokens, morphemes, lexicon) {
  return tokens.filter((t) => t.isWord).map((token) => {
    const parts = morphemes.filter((m) => m.start >= token.start && m.start < token.start + token.text.length);
    const head = parts[0];
    const lower = token.text.toLowerCase().replace(/’/g, "'");
    const pos = englishPartOfSpeech(head ? head.pos : token.tag.split('+')[0], lower);
    const base = head && parts.length === 1 && head.base !== lower ? head.base : null;
    const contraction = lower.includes("'");
    return {
      start: token.start, end: token.start + token.text.length, text: token.text, pos, base,
      level: contraction ? null : lexicon.level(base || lower, LEVEL_PART[pos] || 'f'),
      ipa: lexicon.ipa(lower) || (base && lexicon.ipa(base)),
    };
  });
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { spelledVerbLemma, englishLexicon, englishMorphemes, englishWords });
