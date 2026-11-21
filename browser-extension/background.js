// Google Translate's web batch endpoints, tried in order (no key needed).
const ENDPOINTS = [
  ['https://translate.googleapis.com/translate_a/t', 'gtx'],
  ['https://clients5.google.com/translate_a/t', 'dict-chrome-ex'],
];

// Google's code for a caption language, as the Android app's googleLanguageCode: the base language, Chinese by script.
// Unknown stays auto-detected.
function googleSource(lang) {
  if (!lang) return 'auto';
  if (/^zh-(Hant|TW|HK)/i.test(lang)) return 'zh-TW';
  if (/^zh/i.test(lang)) return 'zh-CN';
  return lang.split('-')[0];
}

async function translate(texts, target, source) {
  let last;
  for (const [address, client] of ENDPOINTS) {
    try {
      const body = new URLSearchParams();
      texts.forEach((t) => body.append('q', t));
      const reply = await fetch(`${address}?client=${client}&sl=${encodeURIComponent(googleSource(source))}&tl=${encodeURIComponent(target)}`, { method: 'POST', body });
      if (!reply.ok) throw new Error(`Google Translate returned HTTP ${reply.status}`);
      const entries = await reply.json();
      if (!Array.isArray(entries) || entries.length !== texts.length) throw new Error('Google Translate returned an unexpected reply');
      // Each entry is the translation, or [translation, detected language].
      return entries.map((e) => (Array.isArray(e) ? e[0] : e) || '');
    } catch (error) {
      last = error;
    }
  }
  throw last;
}

// Wiktionary's definitions of [word], or of its lowercase form (sentence-initial words); {} when it has none.
// Both forms are asked at once since each request takes seconds on slow networks.
// Google's own dictionary endpoint (translate_a/single) answers 429 on many networks, so it is not used.
async function define(word) {
  const forms = [...new Set([word, word.toLowerCase()])];
  const replies = await Promise.all(
    forms.map((w) => fetch(`https://en.wiktionary.org/api/rest_v1/page/definition/${encodeURIComponent(w)}`).then((r) => (r.ok ? r.json() : null))),
  );
  return replies.find(Boolean) || {};
}

const escape = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

async function anki(action, params = {}) {
  let reply;
  try {
    reply = await fetch('http://127.0.0.1:8765', { method: 'POST', body: JSON.stringify({ action, version: 6, params }) });
  } catch {
    throw new Error('Anki is not reachable. Open Anki with the AnkiConnect add-on installed.');
  }
  const { result, error } = await reply.json();
  if (error) throw new Error(error);
  return result;
}

// shortcut: uses the stock "Basic" note type (Front/Back); add a note-type setting if a user's Anki names it differently.
async function addToAnki(card) {
  const { permission } = await anki('requestPermission');
  if (permission !== 'granted') throw new Error('Anki did not allow DualSub.');
  await anki('createDeck', { deck: 'DualSub' });
  const front = `${escape(card.word)}<br><small>${escape(card.sentence)}</small>`;
  const back = [card.translation, ...card.meanings.map((m) => `${m.pos}: ${m.defs.join('; ')}`), card.sentenceTr]
    .filter(Boolean)
    .map(escape)
    .join('<br>');
  return anki('addNote', { note: { deckName: 'DualSub', modelName: 'Basic', fields: { Front: front, Back: back }, tags: ['dualsub'] } });
}

// Google's voice for [text] as a data: URL, as the Android app's GoogleWebSpeech (200 characters, 512 KiB at most).
async function speech({ text, lang }) {
  const q = text.trim();
  if (!q || q.length > 200) return { error: 'Text too long for Google voice' };
  const tl = googleSource(lang) === 'auto' ? 'en' : googleSource(lang);
  const reply = await fetch(`https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=${encodeURIComponent(tl)}&q=${encodeURIComponent(q)}`);
  if (!reply.ok) throw new Error(`Google voice returned HTTP ${reply.status}`);
  const bytes = new Uint8Array(await reply.arrayBuffer());
  if (!bytes.length || bytes.length > 512 * 1024) throw new Error('Google voice returned no usable audio');
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return { url: `data:audio/mpeg;base64,${btoa(binary)}` };
}

importScripts('translation/ai.js', 'data/english.js', 'data/english-tagger.js', 'data/english-grammar-match.js', 'data/english-grammar.js');

// English analysis data, loaded on the first English line and again after Chrome stops the idle worker:
// the tagger model (1.6 MB), WordNet's words and irregular forms, CEFR-J levels and IPA.
let english;
const learnerFile = (name) => fetch(chrome.runtime.getURL(`data/learner-data/${name}`));
function englishData() {
  english ??= Promise.all([
    learnerFile('en-pos.model').then((r) => r.arrayBuffer()),
    ...['en-words.txt', 'en-exceptions.txt', 'en-levels.txt', 'en-ipa.txt'].map((n) => learnerFile(n).then((r) => r.text())),
  ]).then(([model, ...lists]) => ({
    model: DualSub.readGisModel(model),
    lexicon: DualSub.englishLexicon(...lists),
    grammar: DualSub.compileGrammar(DualSub.ENGLISH_GRAMMAR_CATALOGUE),
  }));
  return english;
}

// Each English line's words (part of speech in context, dictionary form, level, IPA) and grammar points with their
// explanations, as the Android app's EnglishMorphology and englishGrammar.
async function englishLines({ texts }) {
  const { model, lexicon, grammar } = await englishData();
  return {
    lines: texts.map((text) => {
      const tokens = DualSub.tagEnglish(model, text);
      const morphemes = DualSub.englishMorphemes(tokens, lexicon);
      return {
        words: DualSub.englishWords(tokens, morphemes, lexicon),
        grammar: DualSub.matchEnglishGrammar(morphemes, grammar).map((g) => ({ ...g, text: DualSub.ENGLISH_GRAMMAR_TEXT[g.meaning] })),
      };
    }),
  };
}

// Translates [sentences] with the AI service and key the user saved; { noKey } when there is no key.
// Answers are kept per sentence in storage.local ("aic:" keys), so a video opened again costs no requests.
// shortcut: the cache is never pruned; storage.local holds about 10 MB (~30,000 sentences), add pruning when that is near.
async function aiTranslate({ source, target, sentences, context }) {
  const { ai = {} } = await chrome.storage.local.get('ai');
  if (!ai.key) return { noKey: true };
  const service = DualSub.AI_SERVICES[ai.service] || DualSub.AI_SERVICES.gemini;
  const model = ai.model || service.model;
  // The prompt version, service, model and languages are part of the key, as in Android's cache.
  const cacheKey = (text) => `aic:2|${ai.service || 'gemini'}|${model}|${source}|${target}|${text}`;
  const cached = await chrome.storage.local.get(sentences.map(cacheKey));
  const missing = sentences.filter((s) => cached[cacheKey(s)] == null);
  if (!missing.length) return { texts: sentences.map((s) => cached[cacheKey(s)]) };
  const reply = await aiAsk(service, ai.key, model, source, target, missing, context);
  if (!reply.texts) return reply;
  const fresh = {};
  missing.forEach((s, i) => reply.texts[i] && (fresh[cacheKey(s)] = reply.texts[i]));
  await chrome.storage.local.set(fresh);
  return { texts: sentences.map((s) => cached[cacheKey(s)] ?? fresh[cacheKey(s)] ?? null) };
}

async function aiAsk(service, key, model, source, target, sentences, context) {
  const system = DualSub.aiSystemPrompt(source, target);
  const user = DualSub.aiRequest(context, sentences);
  const request = service.anthropic
    ? {
        url: `${service.base}/messages`,
        // Anthropic refuses browser requests without the last header.
        headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' },
        body: { model, max_tokens: 16000, system, messages: [{ role: 'user', content: user }] },
      }
    : {
        url: `${service.base}/chat/completions`,
        headers: { authorization: `Bearer ${key}` },
        body: { model, messages: [{ role: 'system', content: system }, { role: 'user', content: user }], ...(service.schema && { response_format: DualSub.AI_JSON_SCHEMA }) },
      };
  // A busy service (5xx, Anthropic's 529, or a short rate limit) is asked again after these waits, as Android's
  // AI_BUSY_RETRY_DELAYS_MS: free Gemini answers 503 "high demand" to about half of all requests at busy times.
  const waits = [1000, 3000];
  for (;;) {
    let reply;
    try {
      reply = await fetch(request.url, { method: 'POST', headers: { 'content-type': 'application/json', ...request.headers }, body: JSON.stringify(request.body) });
    } catch {
      return { error: `${service.label} could not be reached`, busy: true };
    }
    const body = await reply.json().catch(() => ({}));
    if (reply.ok) {
      const text = service.anthropic ? (body.content || []).map((c) => c.text || '').join('') : body.choices?.[0]?.message?.content || '';
      return { texts: DualSub.parseAiReply(text, sentences) };
    }
    const detail = (Array.isArray(body) ? body[0] : body)?.error?.message?.replace(/\.$/, '') || '';
    const busy = reply.status >= 500 || (reply.status === 429 && !/quota|credit|billing/i.test(detail));
    if (busy && waits.length) {
      await new Promise((r) => setTimeout(r, waits.shift()));
      continue;
    }
    return { error: `${service.label} returned HTTP ${reply.status}${detail ? `: ${detail}` : ''}`, busy };
  }
}

const handlers = {
  aiTranslate,
  speech,
  englishLines,
  translate: (m) => translate(m.texts, m.target, m.source).then((texts) => ({ texts })),
  define: (m) => define(m.word).then((entries) => ({ entries })),
  anki: (m) => addToAnki(m.card).then((id) => ({ id })),
  openSavedWords: () => chrome.tabs.create({ url: chrome.runtime.getURL('ui/saved-words.html') }).then(() => ({})),
  openSettings: () => chrome.tabs.create({ url: chrome.runtime.getURL('ui/popup.html') }).then(() => ({})),
  openGuide: () => chrome.tabs.create({ url: chrome.runtime.getURL('ui/guide.html') }).then(() => ({})),
};

chrome.runtime.onMessage.addListener((message, sender, reply) => {
  const handler = handlers[message.type];
  if (!handler) return;
  handler(message).then(reply, (e) => reply({ error: String(e.message || e) }));
  return true;
});

// The guide opens once after installing, as the Android app shows its guide on first launch.
chrome.runtime.onInstalled.addListener(({ reason }) => reason === 'install' && chrome.tabs.create({ url: chrome.runtime.getURL('ui/guide.html') }));
