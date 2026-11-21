// Grammar points as patterns over morphemes, and the English rules that need code.
// Port of the Android app's data/GrammarPattern.kt, data/GrammarMatcher.kt and data/EnglishGrammar.kt
// (the English parts; the catalogue itself is generated into data/english-grammar.js).
//
// Pattern syntax: alternatives separated by " / ", each a space-separated list of conditions on one morpheme:
//  - `would|'d` the surface or dictionary form is one of these words; `_` means any word;
//    `~ing` the word ends with it (and is longer).
//  - `@VERB,AUX` the UD tag is one of these; several `@` groups must all hold.
//  - `%BASE,S3` the form (BASE, PRES, S3, PAST, PP, ING, MODAL, COMP, SUP, LY, PL) starts with one of these.
//  - `!only` never this word. `?` at the end makes the condition optional.
//  - A first condition starting with `^` checks the morpheme before the point, a last one starting with `>` the one
//    after it; neither is part of the point. A `>` with only `!` words also allows the end of the sentence.
//  - `MID` stands for `not|n't? _@ADV? _@ADV?`, the optional words between English helper verbs.

function grammarCondition(raw) {
  const optional = raw.length > 1 && raw.endsWith('?');
  const body = optional ? raw.slice(0, -1) : raw;
  const [main, ...excluded] = body.split('!');
  const suffix = main.startsWith('~');
  const word = suffix ? main.slice(1) : main;
  const [tagged, conjugations = ''] = word.split('%');
  const [texts, ...groups] = tagged.split('@');
  const c = {
    optional,
    excluded: new Set(excluded),
    suffix,
    texts: texts && texts !== '_' ? new Set(texts.split('|').filter(Boolean)) : new Set(),
    tagGroups: groups.map((g) => new Set(g.split(','))),
    forms: conjugations.split(',').filter(Boolean),
  };
  c.onlyExcludes = !c.texts.size && !c.tagGroups.length && !c.forms.length;
  c.matches = (m) => {
    if (c.excluded.has(m.surface) || c.excluded.has(m.base)) return false;
    if (c.texts.size) {
      const hit = c.suffix
        ? [...c.texts].some((t) => [m.surface, m.base].some((w) => w.length > t.length && w.endsWith(t)))
        : c.texts.has(m.surface) || c.texts.has(m.base);
      if (!hit) return false;
    }
    return c.tagGroups.every((g) => g.has(m.pos) || g.has(m.form)) && (!c.forms.length || c.forms.some((f) => m.form.startsWith(f)));
  };
  return c;
}

function grammarSequence(spec) {
  let parts = spec.split(' ').filter(Boolean).flatMap((p) => (p === 'MID' ? ["not|n't?", '_@ADV?', '_@ADV?'] : [p]));
  const before = parts[0].startsWith('^') ? grammarCondition(parts[0].slice(1)) : null;
  if (before) parts = parts.slice(1);
  const after = parts.at(-1).startsWith('>') ? grammarCondition(parts.at(-1).slice(1)) : null;
  if (after) parts = parts.slice(0, -1);
  const steps = parts.map(grammarCondition);
  const afterAllows = (ms, at) => !after || (at >= ms.length ? after.onlyExcludes : after.matches(ms[at]));
  // The furthest index the steps from [step] can reach starting at [at], or -1.
  const longestEnd = (ms, step, at) => {
    if (step === steps.length) return afterAllows(ms, at) ? at : -1;
    const c = steps[step];
    const taken = at < ms.length && c.matches(ms[at]) ? longestEnd(ms, step + 1, at + 1) : -1;
    const skipped = c.optional ? longestEnd(ms, step + 1, at) : -1;
    return Math.max(taken, skipped);
  };
  return (ms, index) => {
    if (before && (index === 0 || !before.matches(ms[index - 1]))) return 0;
    return Math.max(longestEnd(ms, 0, index) - index, 0);
  };
}

// Compiled catalogue: { level, form, meaning, lengthAt(morphemes, index) }.
const compileGrammar = (catalogue) =>
  catalogue.map(([level, form, spec, meaning]) => {
    const alternatives = spec.split(' / ').map(grammarSequence);
    return { level, form, meaning, lengthAt: (ms, i) => Math.max(...alternatives.map((a) => a(ms, i))) };
  });

// ---- Android's EnglishGrammar.kt rules ----

const WHETHER_VERBS = new Set(['know', 'ask', 'wonder', 'see', 'check', 'sure', 'decide', 'tell', 'doubt']);
const QUESTION_WORDS = new Set(['what', 'where', 'when', 'why', 'who', 'whom', 'which', 'how', 'whose']);
const SUBJECT_TAGS = new Set(['PRON', 'PROPN', 'DET', 'NOUN']);
const NOT_COMMANDS = new Set(['thank', 'thanks', 'welcome', 'bless']);
const startsSentence = (ms, i) => i === 0 || ms[i - 1].pos === 'PUNCT';

// If: the tenses in its clause tell a real condition, an unreal present and an unreal past apart.
function conditionalRule(ms, i) {
  if (ms[i].surface !== 'if') return null;
  const asked = ms.slice(0, i).filter((m) => m.pos !== 'PRON' && m.pos !== 'PROPN').at(-1);
  if (asked && WHETHER_VERBS.has(asked.base)) return { length: 1, form: 'whether', meaning: 'EN_WHETHER', level: 'B2' };
  const clause = [];
  for (const m of ms.slice(i + 1)) {
    if (m.pos === 'PUNCT' || m.surface === 'then') break;
    clause.push(m);
  }
  const pastPerfect = clause.some((m, at) => m.base === 'have' && m.form === 'PAST' && clause.slice(at + 1, at + 4).some((x) => x.form === 'PP'));
  const past = clause.some((m) => (m.pos === 'VERB' || m.pos === 'AUX') && m.form === 'PAST');
  if (pastPerfect) return { length: 1, form: 'if + had done', meaning: 'EN_IF_PAST_UNREAL', level: 'B2' };
  if (past) return { length: 1, form: 'if + past', meaning: 'EN_IF_UNREAL', level: 'B1' };
  return { length: 1, form: 'if + present', meaning: 'EN_IF_REAL', level: 'A2' };
}

// A question word that starts a question; "when" that starts a time clause instead.
function questionWordRule(ms, i) {
  const word = ms[i];
  if (!QUESTION_WORDS.has(word.surface)) return null;
  const rest = ms.slice(i + 1);
  const untilEnd = [];
  for (const m of rest) {
    if (m.surface === '.' || m.surface === '!') break;
    untilEnd.push(m);
  }
  const asks = startsSentence(ms, i) && (untilEnd.some((m) => m.surface === '?') || rest[0]?.pos === 'AUX');
  if (asks) return { length: 1, form: 'what / where / why…?', meaning: 'EN_WH_QUESTION', level: 'A1' };
  if (word.surface === 'when') return { length: 1, form: 'when + sentence', meaning: 'EN_WHEN_CLAUSE', level: 'A1' };
  return null;
}

// Is it…? Have you…?: be or have first, then the subject (do and the modals have their own points).
function helperQuestionRule(ms, i) {
  const helper = ms[i];
  if (helper.pos !== 'AUX' || !['be', 'have'].includes(helper.base) || !startsSentence(ms, i)) return null;
  const negated = ms[i + 1]?.base === 'not';
  const subject = ms[i + (negated ? 2 : 1)];
  if (!subject || !SUBJECT_TAGS.has(subject.pos)) return null;
  return { length: negated ? 3 : 2, form: 'is / have + subject?', meaning: 'EN_YES_NO_QUESTION', level: 'A1' };
}

// A plain verb with no subject at the start of a sentence (not a question): an order or a request.
function imperativeRule(ms, i) {
  const verb = ms[i];
  const afterPlease = i > 0 && (ms[i - 1].surface === 'please' || ms[i - 1].pos === 'INTJ');
  if (!startsSentence(ms, i) && !afterPlease) return null;
  if (verb.form !== 'BASE' || NOT_COMMANDS.has(verb.base)) return null;
  if (verb.pos !== 'VERB' && !(verb.pos === 'AUX' && verb.base === 'be')) return null;
  if (ms.slice(i + 1).find((m) => ['.', '!', '?'].includes(m.surface))?.surface === '?') return null;
  return { length: 1, form: 'verb (command)', meaning: 'EN_IMPERATIVE', level: 'A1' };
}

const ENGLISH_RULES = [conditionalRule, questionWordRule, helperQuestionRule, imperativeRule];

// The grammar points of a line's morphemes: every pattern and rule hit, then the longest first so each morpheme
// belongs to one point; between hits of the same length the earlier catalogue pattern wins, and the catalogue wins
// over the rules. Each point: { form, meaning, level, start, end } (character offsets in the line).
function matchEnglishGrammar(ms, compiled) {
  const candidates = [];
  compiled.forEach((p, priority) => {
    for (let i = 0; i < ms.length; i++) {
      const length = p.lengthAt(ms, i);
      if (length > 0) candidates.push({ priority, index: i, length, form: p.form, meaning: p.meaning, level: p.level });
    }
  });
  ENGLISH_RULES.forEach((rule, n) => {
    for (let i = 0; i < ms.length; i++) {
      const hit = rule(ms, i);
      if (hit) candidates.push({ priority: compiled.length + n, index: i, ...hit });
    }
  });
  candidates.sort((a, b) => b.length - a.length || a.priority - b.priority || a.index - b.index);
  const accepted = [];
  for (const c of candidates) {
    const last = c.index + c.length - 1;
    if (accepted.some((a) => c.index <= a.last && last >= a.first)) continue;
    accepted.push({ first: c.index, last, form: c.form, meaning: c.meaning, level: c.level, start: ms[c.index].start, end: ms[last].end });
  }
  return accepted.sort((a, b) => a.start - b.start || a.end - b.end).map(({ form, meaning, level, start, end }) => ({ form, meaning, level, start, end }));
}

// The points that belong to a selection from [start] to [end]: those overlapping it, and one that starts right
// after it; a point that comes up more than once is listed once (Android's grammarForSelection).
function grammarForSelection(matches, start, end) {
  const inside = matches.filter((m) => m.start < end && m.end > start);
  const following = matches.filter((m) => m.start === end).slice(0, 1);
  const seen = new Set();
  return [...inside, ...following].filter((m) => !seen.has(m.form + m.meaning) && seen.add(m.form + m.meaning));
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { compileGrammar, matchEnglishGrammar, grammarForSelection });
