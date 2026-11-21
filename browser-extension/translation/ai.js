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

const languageName = (code) => {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) || code;
  } catch {
    return code;
  }
};

// Raised when the prompt changes, so sentences cached with the old one are translated again (Android's AI_PROMPT_VERSION).
const AI_PROMPT_VERSION = 3;

// Android's aiTranslationSystemPrompt (its version 2), with one deliberate change in version 3 (owner report
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

// Plain text without markup or a leading comma (Android's cleanAiRow).
const cleanRow = (row) => String(row ?? '').replace(/<\/?[A-Za-z][^<>]*>/g, '').trim().replace(/^[,;，、；]+/, '').trim();

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

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { AI_SERVICES, AI_PROMPT_VERSION, AI_CONTEXT_SENTENCES, languageName, AI_JSON_SCHEMA, aiSystemPrompt, aiRequest, aiBatches, parseAiReply });
