// AI subtitle translation with the user's own key: services, prompt, request, batches and reply parsing.
// Port of the Android app's translation/AiSubtitleTranslator.kt and assistant/AiProvider.kt. Rows here are whole
// sentences, so every sentence is sent as one row. Loaded by background.js (importScripts) and the tests.

// Services as Android's AiProvider: Gemini, OpenRouter and OpenAI speak the OpenAI chat API, Claude its own.
const AI_SERVICES = {
  gemini: { label: 'Gemini', base: 'https://generativelanguage.googleapis.com/v1beta/openai', model: 'gemini-flash-lite-latest', keys: 'https://aistudio.google.com/apikey', schema: true },
  openrouter: { label: 'OpenRouter', base: 'https://openrouter.ai/api/v1', model: 'openrouter/free', keys: 'https://openrouter.ai/keys', schema: false },
  openai: { label: 'OpenAI', base: 'https://api.openai.com/v1', model: 'gpt-6-luna', keys: 'https://platform.openai.com/api-keys', schema: true },
  claude: { label: 'Claude', base: 'https://api.anthropic.com/v1', model: 'claude-haiku-5-5', keys: 'https://platform.claude.com/settings/keys', anthropic: true },
};

const MAX_AI_BATCH_SENTENCES = 60;
const MAX_AI_BATCH_CHARS = 6000;
const AI_CONTEXT_SENTENCES = 4; // sentences of the previous request sent again, so pronouns and tone carry over

// A language's English name; Chinese by script, as the app's englishTargetName.
const languageName = (code) => {
  if (code === 'zh' || code === 'zh-CN') return 'Simplified Chinese';
  if (code === 'zh-TW') return 'Traditional Chinese';
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) || code;
  } catch {
    return code;
  }
};

// Raised when the prompt changes, so sentences cached with the old one are translated again (Android's AI_PROMPT_VERSION).
const AI_PROMPT_VERSION = 3;

// Android's aiTranslationSystemPrompt (its version 2), with one deliberate change in version 3 (contributor report
// 2026-11-21): a piece of a longer sentence is translated on its own. Android's "translate it so that reading the
// translations in order gives the whole sentence" let the model move words between lines (in a verb-final language
// such as Persian, a line's verb ended up under the line before), so a line's translation no longer matched it.
function aiSystemPrompt(source, target) {
  const from = languageName(source);
  const to = languageName(target);
  return [
    `You translate video subtitles from ${from} (${source}) to ${to} (${target}) for someone learning ${from} who reads ${to}.`,
    'The input is JSON: "context" holds earlier sentences of the same video, for meaning only; "sentences" holds the sentences to translate, in the order they are spoken, each split into the rows the screen shows.',
    'Captions are often made by automatic speech recognition: punctuation may be missing, so one spoken sentence can be cut across several input sentences, and some words are misheard. Read the sentences as one continuous speech. When a sentence is only a piece of a longer one, translate just that piece, even if its translation is an unfinished phrase (end it with … when the sentence goes on in the next one); never move words or meaning into the translation of an earlier or later sentence, because each translation is shown under its own line and must say exactly what that line says. When a word makes no sense in context, translate what the speaker most likely said (a word that sounds similar), not the misheard word.',
    'Fill in subjects the source leaves out (common in Japanese, Korean, Chinese and Vietnamese) from context; when someone talks about their own life, it is usually "I".',
    "Translate naturally, as a native speaker would say it in this situation, keeping the speaker's tone, slang and jokes. Keep names, brands, code and numbers as they are.",
    'Write only the translation in each row, never a copy of the source text.',
    "Split each sentence's translation into exactly as many rows as the source sentence has, in the same order. Each row holds the part of the translation that says what its source row says, as far as natural word order allows. Do not leave a row empty unless its source row is only a filler word, and do not start a row with a comma.",
    'Answer with JSON only, no comments and no code fences: {"sentences":[{"id":<id>,"rows":["…", …]}, …]} with every id from the input.',
  ].join('\n');
}

// The user message: context sentences and the numbered sentences, as data.
const aiRequest = (context, sentences) => JSON.stringify({ context, sentences: sentences.map((text, i) => ({ id: i + 1, rows: [text] })) });

// The response_format that makes Gemini and OpenAI answer valid JSON of the prompt's shape.
const AI_JSON_SCHEMA = {
  type: 'json_schema',
  json_schema: {
    name: 'subtitle_translations',
    strict: true,
    schema: {
      type: 'object',
      properties: {
        sentences: {
          type: 'array',
          items: { type: 'object', properties: { id: { type: 'integer' }, rows: { type: 'array', items: { type: 'string' } } }, required: ['id', 'rows'], additionalProperties: false },
        },
      },
      required: ['sentences'],
      additionalProperties: false,
    },
  },
};

// Index ranges [from, to) of requests of at most 60 sentences and 6,000 characters.
function aiBatches(texts) {
  const out = [];
  let from = 0;
  let chars = 0;
  texts.forEach((text, i) => {
    if (i > from && (i - from >= MAX_AI_BATCH_SENTENCES || chars + text.length > MAX_AI_BATCH_CHARS)) {
      out.push([from, i]);
      from = i;
      chars = 0;
    }
    chars += text.length;
  });
  if (texts.length) out.push([from, texts.length]);
  return out;
}

// [json] with each quote inside a string escaped when it cannot end the string (Android's withStrayQuotesEscaped).
function withStrayQuotesEscaped(json) {
  let out = '';
  let inString = false;
  let escaped = false;
  for (let i = 0; i < json.length; i++) {
    const ch = json[i];
    if (escaped) escaped = false;
    else if (inString && ch === '\\') escaped = true;
    else if (inString && ch === '"') {
      const next = json.slice(i + 1).trimStart()[0];
      if (next == null || ',:]}'.includes(next)) inString = false;
      else out += '\\';
    } else if (ch === '"') inString = true;
    out += ch;
  }
  return out;
}

// Plain text without markup or a leading comma (Android's cleanAiRow). Tags are removed until none is left, so a
// nested one such as "<b<i>>" can't leave a tag behind; rows reach the page as text anyway, never as HTML.
function cleanRow(row) {
  let text = String(row ?? '');
  for (let before = null; before !== text; ) {
    before = text;
    text = text.replace(/<\/?[A-Za-z][^<>]*>/g, '');
  }
  return text.trim().replace(/^[,;，、；]+/, '').trim();
}

// Three words or more, or four characters written without spaces: too long to be a name kept as it is.
const canBeEcho = (text) => text.split(/\s+/).filter(Boolean).length >= 3 || (text.length >= 4 && !/\s/.test(text) && /^[぀-ヿ㐀-鿿฀-๿]/.test(text));

// [row] without a copy of [source] written before its translation (Android's withoutSourceEcho), or '' when it is only that copy.
function withoutEcho(row, source) {
  const s = source.trim();
  if (!canBeEcho(s) || !row.startsWith(s)) return row;
  return row.slice(s.length).replace(/^[\s\-–—:：=→|/]+/, '');
}

// The translation of each sentence by position, or null where the reply has none.
function parseAiReply(reply, sentences) {
  const start = reply.indexOf('{');
  const end = reply.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('The AI reply has no JSON.');
  const json = reply.slice(start, end + 1);
  let items;
  try {
    items = JSON.parse(json).sentences;
  } catch {
    try {
      items = JSON.parse(withStrayQuotesEscaped(json)).sentences;
    } catch (error) {
      throw new Error(`The AI reply is not the JSON asked for: ${error.message}`);
    }
  }
  const byId = new Map((Array.isArray(items) ? items : []).map((item) => [item && item.id, item]));
  return sentences.map((text, i) => {
    const rows = byId.get(i + 1)?.rows;
    if (!Array.isArray(rows)) return null;
    const joined = rows.map(cleanRow).map((r) => withoutEcho(r, text)).filter(Boolean).join(' ');
    return joined && !(canBeEcho(text.trim()) && joined === text.trim()) ? joined : null; // a bare copy of the source is no translation
  });
}

// ---- Cache size ----
// AI answers are cached in storage.local ("aic:" sentences, "ais:" split chunks) as { t: write time, v: value };
// entries from before the time was kept are plain values and count as the oldest.
const cacheValue = (entry) => (entry && typeof entry === 'object' && 't' in entry ? entry.v : entry);

// The cache keys to remove, oldest first, so that [bytesInUse] falls to half of [quota] once it passes 80% of it.
// [entries] are [key, stored value] pairs of the cache only; saved words and settings are never in them.
function aiCacheToDrop(entries, bytesInUse, quota) {
  if (bytesInUse < quota * 0.8) return [];
  let excess = bytesInUse - quota * 0.5;
  const drop = [];
  for (const [key, value] of [...entries].sort((a, b) => (a[1]?.t ?? 0) - (b[1]?.t ?? 0))) {
    if (excess <= 0) break;
    drop.push(key);
    excess -= key.length + JSON.stringify(value).length;
  }
  return drop;
}

// ---- Request pacing ----
// At most 10 automatic requests per key and model in any minute, the app's budget (AiKeyPacer,
// AI_TRANSLATION_KEY_REQUESTS_PER_MINUTE): free Gemini keys allow few requests a minute, and a long video would
// otherwise send all its batches back to back.
const AI_REQUESTS_PER_MINUTE = 10;

// How long to wait before the next request, given the times of the earlier ones ([sent], oldest first), and those
// times that still count: { wait, sent }.
function aiPacerWait(sent, now, perMinute = AI_REQUESTS_PER_MINUTE) {
  const recent = sent.filter((t) => t > now - 60_000);
  return { wait: recent.length < perMinute ? 0 : recent[recent.length - perMinute] + 60_000 - now, sent: recent };
}

// ---- AI sentence splitting for captions without punctuation ----
// Automatic captions are cut every few seconds, not where sentences end, so a translated line either loses its start
// or borrows words from its neighbour. The AI splits the timed words into sentences and translates each, in one
// request per chunk (docs/specs/2026-11-21-browser-extension-ai-sentence-splitting.md).

// Raised when the splitting prompt changes, so chunks cached with the old one are asked again.
const AI_SEGMENT_VERSION = 1;
const MAX_SEGMENT_WORDS = 300;
const SEGMENT_CUT_WINDOW = 60;

// The track's timed words [{ text, time }] from parsed pieces, or null for an empty track. A piece without word
// timing (some automatic tracks have none) shares its time evenly among its words, as the app's
// estimateWordTimings does, so its words can still be split into sentences.
function captionWords(pieces) {
  if (!pieces.length) return null;
  return pieces.flatMap((p) => {
    if (p.words && p.words.length) return p.words;
    const words = p.text.split(/\s+/).filter(Boolean);
    return words.map((text, i) => ({ text, time: p.start + (i * (p.end - p.start)) / words.length }));
  });
}

// True when fewer than one piece in five ends a sentence: automatic captions without punctuation.
const isUnpunctuated = (pieces) => pieces.filter((p) => /[.!?。！？…]["'’”)]*$/.test(p.text.trim())).length * 5 < pieces.length;

// Index ranges [from, to) of at most 300 words, each cut at the longest pause among its last 60 words.
function wordChunks(words, max = MAX_SEGMENT_WORDS, window = SEGMENT_CUT_WINDOW) {
  const chunks = [];
  let from = 0;
  while (from < words.length) {
    let to = words.length;
    if (to - from > max) {
      to = from + max;
      for (let j = from + max; j > from + max - window; j--) {
        if (words[j].time - words[j - 1].time > words[to].time - words[to - 1].time) to = j;
      }
    }
    chunks.push([from, to]);
    from = to;
  }
  return chunks;
}

function aiSegmentPrompt(source, target) {
  const from = languageName(source);
  const to = languageName(target);
  return [
    `You get the automatic captions of a video in ${from} (${source}): numbered words without punctuation, in the order they are spoken. Split them into the sentences the speaker said and translate each sentence into ${to} (${target}) for someone learning ${from} who reads ${to}.`,
    'The input is JSON: "context" holds the sentences just before these words, for meaning only; "words" holds "number:word" items.',
    'Each sentence starts at a word number; the sentences follow each other and together cover every word, in order. Keep each sentence under about 25 words: split a longer one where a clause ends.',
    "Write each sentence's text with punctuation and capital letters, using exactly its words: never add, drop or change a word.",
    "Translate each whole sentence naturally, as a native speaker would say it in this situation, keeping the speaker's tone; a sentence's translation says exactly what that sentence says. Keep names, brands and numbers as they are. When a word makes no sense in context, translate what the speaker most likely said.",
    'Answer with JSON only, no comments and no code fences: {"sentences":[{"start":<word number>,"text":"…","translation":"…"}, …]}.',
  ].join('\n');
}

const aiSegmentRequest = (context, words) => JSON.stringify({ context, words: words.map((w, i) => `${i}:${w}`).join(' ') });

// The response_format that makes Gemini and OpenAI answer valid JSON of the splitting prompt's shape.
const AI_SEGMENT_SCHEMA = {
  type: 'json_schema',
  json_schema: {
    name: 'caption_sentences',
    strict: true,
    schema: {
      type: 'object',
      properties: {
        sentences: {
          type: 'array',
          items: { type: 'object', properties: { start: { type: 'integer' }, text: { type: 'string' }, translation: { type: 'string' } }, required: ['start', 'text', 'translation'], additionalProperties: false },
        },
      },
      required: ['sentences'],
      additionalProperties: false,
    },
  },
};

// The reply's sentences [{ start, text, translation }] for [count] words: starts inside the words, increasing, once
// each; the first always starts at word 0, so every word belongs to a sentence.
function parseSegmentReply(reply, count) {
  const start = reply.indexOf('{');
  const end = reply.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('The AI reply has no JSON.');
  const json = reply.slice(start, end + 1);
  let items;
  try {
    items = JSON.parse(json).sentences;
  } catch {
    try {
      items = JSON.parse(withStrayQuotesEscaped(json)).sentences;
    } catch (error) {
      throw new Error(`The AI reply is not the JSON asked for: ${error.message}`);
    }
  }
  const seen = new Set();
  const sentences = (Array.isArray(items) ? items : [])
    .filter((s) => s && Number.isInteger(s.start) && s.start >= 0 && s.start < count)
    .sort((a, b) => a.start - b.start)
    .filter((s) => !seen.has(s.start) && seen.add(s.start))
    .map((s) => ({ start: s.start, text: cleanRow(s.text), translation: cleanRow(s.translation) }));
  if (!sentences.length) throw new Error('The AI reply has no sentences.');
  sentences[0].start = 0;
  return sentences;
}

const letters = (text) => text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');

// Caption lines from the AI's sentences over [words] ({ text, time }), the chunk's own; [endTime] ends the last one.
// A sentence keeps the AI's punctuated text only when it has the same words as its range.
function segmentLines(sentences, words, endTime) {
  return sentences.map((s, i) => {
    const to = i + 1 < sentences.length ? sentences[i + 1].start : words.length;
    const own = words.slice(s.start, to);
    const original = own.map((w) => w.text).join(' ');
    const text = s.text && letters(s.text) === letters(original) ? s.text : original.charAt(0).toUpperCase() + original.slice(1);
    return { start: own[0].time, end: i + 1 < sentences.length ? words[to].time : endTime, text, words: own, tr: s.translation, ai: true };
  });
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { AI_SERVICES, cacheValue, aiCacheToDrop, AI_REQUESTS_PER_MINUTE, aiPacerWait, AI_PROMPT_VERSION, AI_SEGMENT_VERSION, AI_SEGMENT_SCHEMA, AI_CONTEXT_SENTENCES, languageName, captionWords, isUnpunctuated, wordChunks, aiSegmentPrompt, aiSegmentRequest, parseSegmentReply, segmentLines, AI_JSON_SCHEMA, aiSystemPrompt, aiRequest, aiBatches, parseAiReply });
