// Watch-page UI: a dual-subtitle overlay on the player, a clickable transcript beside it, and a word card.
const state = { videoId: null, lang: '', trackKey: null, lines: [], rows: [], active: -1, ccTried: false, since: 0, gen: 0 };
let target = 'en';
let translator = 'google'; // or 'ai'
let overlay;
let panel;
let card;

// Until the user picks one, translate into the browser's language (or English), as the app follows the phone's.
chrome.storage.sync.get({ target: '', translator: 'google' }, (v) => {
  target = v.target ? DualSub.targetCode(v.target) : DualSub.defaultTarget(chrome.i18n.getUILanguage());
  translator = v.translator;
});
chrome.storage.onChanged.addListener((change) => {
  if (!change.target && !change.translator && !change.ai) return;
  if (change.target) target = DualSub.targetCode(change.target.newValue);
  if (change.target && panel && state.lines.length) panel.querySelector('.dualsub-title').textContent = `${DualSub.languageName(state.lang)} → ${DualSub.languageName(target)}`;
  if (change.translator) translator = change.translator.newValue;
  translateAll();
});

const currentVideoId = () => (location.pathname === '/watch' ? new URLSearchParams(location.search).get('v') : null);
const video = () => document.querySelector('#movie_player video');
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  node.dir = 'auto';
  return node;
};
const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const htmlText = (html) => {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('style').forEach((s) => s.remove()); // Wiktionary inlines CSS in some definitions
  return doc.body.textContent.replace(/\s+/g, ' ').trim();
};

async function ask(message) {
  try {
    return (await chrome.runtime.sendMessage(message)) || { error: 'no reply' };
  } catch (error) {
    return { error: error.message };
  }
}

// Word lookups by "type|language|word", kept as promises so a hover or line prefetch and a click share one request.
const lookups = new Map();
function remember(key, reply) {
  lookups.set(key, reply.then((r) => (r.error && lookups.delete(key), r))); // a failure is asked again next time
  return lookups.get(key);
}
function lookup(type, word) {
  const key = `${type}|${target}|${word}`;
  return lookups.get(key) || remember(key, ask(type === 'define' ? { type, word } : { type, texts: [word], target, source: state.lang }));
}

// Translates every word of [line] in one request, so a click on any of them shows its translation at once.
function prefetchWords(line) {
  const words = [...new Set(DualSub.tokens(line.text, state.lang).filter((t) => t.word).map((t) => t.text))]
    .filter((w) => !lookups.has(`translate|${target}|${w}`));
  if (!words.length) return;
  const reply = ask({ type: 'translate', texts: words, target, source: state.lang });
  words.forEach((w, i) => remember(`translate|${target}|${w}`, reply.then((r) => (r.error ? r : { texts: [r.texts[i]] }))));
}

window.addEventListener('message', (event) => {
  const data = event.data;
  if (event.source !== window || event.origin !== location.origin || !data || data.dualsub !== 'track' || data.videoId !== currentVideoId()) return;
  let lines;
  let pieces;
  try {
    pieces = DualSub.parseCaptions(data.text);
    lines = DualSub.sentenceLines(DualSub.splitAtSentenceEnds(pieces));
  } catch {
    return;
  }
  const key = `${data.videoId}|${data.lang}|${lines.length}`;
  if (!lines.length || key === state.trackKey) return; // the player re-downloads the same track often
  state.trackKey = key;
  state.lang = data.lang;
  state.pieces = pieces; // the track as YouTube cut it, which the AI can split into sentences again
  state.lines = lines;
  state.status = null;
  state.active = -1;
  renderPanel();
  translateAll();
  analyzeEnglish();
  // Opening the dictionary connection takes seconds through slow networks; do it now, not on the first click.
  const first = DualSub.tokens(lines[0].text, state.lang).find((t) => t.word);
  if (first) lookup('define', first.text);
});

function reset(videoId) {
  Object.assign(state, { videoId, lang: '', trackKey: null, pieces: null, lines: [], rows: [], active: -1, ccTried: false, ccRetried: false, since: Date.now(), status: null });
  state.gen++;
  lookups.clear();
  closeCard();
  document.documentElement.classList.remove('dualsub-on');
  if (overlay) overlay.replaceChildren();
  if (panel) renderPanel();
}

// English lines are tagged in context by the service worker (Android's EnglishMorphology), which recolors their words
// and gives the word card its dictionary form, level and grammar points.
async function analyzeEnglish(lines = state.lines) {
  if (!/^en\b/.test(state.lang) || !lines.length) return;
  const reply = await ask({ type: 'englishLines', texts: lines.map((l) => l.text) });
  if (!state.lines.includes(lines[0]) || !reply.lines) return; // replaced meanwhile by a newer track or the AI's sentences
  reply.lines.forEach((en, i) => (lines[i].en = en));
  renderPanel();
  state.active = -1; // redraw the overlay in the new colors
}

function replay(index) {
  const v = video();
  if (!v || !state.lines[index]) return;
  v.currentTime = state.lines[index].start;
  v.play();
}

// The word the open card started from, so a click on another word of the same line selects the phrase between them.
let picked = null;

// The original line with each word clickable.
function original(line) {
  const node = el('div', 'dualsub-orig');
  const tokens = DualSub.tokens(line.text, state.lang);
  const times = DualSub.wordTimes(line, tokens);
  const tagged = line.en && new Map(line.en.words.map((w) => [w.start, w]));
  let offset = 0;
  tokens.forEach((token, index) => {
    token.start = offset;
    token.end = offset += token.text.length;
    const analysis = tagged && tagged.get(token.start);
    if (analysis && analysis.end === token.end) token.pos = analysis.pos; // the tagger's part of speech in this sentence
    if (!token.word) {
      node.append(token.text);
      return;
    }
    const word = el('span', 'dualsub-word', token.text);
    word.style.setProperty('--pos', DualSub.PARTS_OF_SPEECH[token.pos][1]);
    if (times && times[index] != null) word.dataset.t = times[index];
    word.onmouseenter = () => lookup('define', token.text); // gets a head start on the slow dictionary
    word.onclick = (event) => {
      event.stopPropagation(); // a word opens its card instead of replaying the line
      if (card && picked && picked.line === line && picked.index !== index) {
        // A second word in the same line selects the phrase from the first to it, as on Android.
        const [from, to] = [Math.min(picked.index, index), Math.max(picked.index, index)];
        const text = tokens.slice(from, to + 1).map((t) => t.text).join('').trim();
        const start = picked.index;
        openCard(word, { text, word: true, pos: 'other', start: tokens[from].start, end: tokens[to].end }, line);
        picked = { line, index: start };
        return;
      }
      openCard(word, token, line);
      picked = { line, index };
    };
    node.append(word);
  });
  return node;
}

// Google's voice, as on Android; the browser's own voice when Google cannot be reached or the page refuses the audio.
async function speak(text) {
  const reply = await ask({ type: 'speech', text, lang: state.lang });
  try {
    if (reply.url) return await new Audio(reply.url).play();
  } catch {}
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = state.lang || 'en';
  speechSynthesis.cancel();
  speechSynthesis.speak(utterance);
}

function mount() {
  const player = document.querySelector('#movie_player');
  if (player && !player.contains(overlay)) {
    overlay = overlay || el('div', 'dualsub-overlay');
    overlay.onclick = () => state.active >= 0 && replay(state.active);
    player.appendChild(overlay);
  }
  const column = document.querySelector('#secondary-inner') || document.querySelector('#secondary');
  if (column && !column.contains(panel)) {
    panel = panel || el('div', 'dualsub-panel');
    column.prepend(panel);
    renderPanel();
  }
}

// The panel's status line; [link] adds an action such as "Set up AI". It is kept in state, so a redrawn panel shows
// it again.
function status(text, link) {
  state.status = { text, link };
  const line = panel && panel.querySelector('.dualsub-status');
  if (!line) return;
  line.textContent = text;
  if (link) {
    const a = el('a', null, link.label);
    a.onclick = () => ask({ type: link.open });
    line.append(' · ', a);
  }
}

// The panel as on Android: a header with the language pair, a status line, settings and the guide, then one card
// per sentence with its play button.
function renderPanel() {
  if (!panel) return;
  const head = el('div', 'dualsub-head');
  const text = el('div', 'dualsub-head-text');
  text.append(
    el('div', 'dualsub-title', state.lang ? `${DualSub.languageName(state.lang)} → ${DualSub.languageName(target)}` : 'DualSub Replay'),
    el('div', 'dualsub-status', state.lines.length ? `${state.lines.length} lines` : 'Waiting for captions…'),
  );
  const settings = el('button', 'dualsub-icon', '⚙');
  settings.title = 'Settings';
  settings.onclick = () => ask({ type: 'openSettings' });
  const guide = el('button', 'dualsub-icon', '?');
  guide.title = 'Guide: how to use DualSub Replay and AI translation';
  guide.onclick = () => ask({ type: 'openGuide' });
  const logo = document.createElement('img');
  logo.className = 'dualsub-logo';
  logo.src = chrome.runtime.getURL('icons/icon32.png');
  logo.alt = 'DualSub Replay';
  head.append(logo, text, settings, guide);

  const list = el('ol', 'dualsub-list');
  state.rows = state.lines.map((line, i) => {
    const row = el('li', 'dualsub-row');
    const play = el('button', 'dualsub-play', '▶');
    play.title = `Play from ${clock(line.start)}`;
    const body = el('div', 'dualsub-text');
    body.append(original(line), el('div', 'dualsub-tr', line.tr || ''));
    row.append(play, body);
    row.onclick = () => replay(i);
    line.row = row; // kept on the line, so a translation for a line the AI has since replaced finds no row
    list.appendChild(row);
    return row;
  });
  panel.replaceChildren(head, list);
  if (state.status && state.lines.length) status(state.status.text, state.status.link);
}

function setTranslation(line, tr) {
  line.tr = tr;
  const cell = line.row && line.row.isConnected && line.row.querySelector('.dualsub-tr');
  if (cell) cell.textContent = tr;
  state.active = -1; // redraw the overlay with its translation
}

// Google translates every line; with AI translation chosen the AI runs at the same time and replaces Google's
// lines as its requests answer, as the Android app does. Captions without punctuation are split into sentences by
// the AI in the same requests, when the track has word timing (docs/specs/2026-11-21-browser-extension-ai-sentence-splitting.md).
async function translateAll() {
  const gen = ++state.gen;
  state.lines.forEach((l) => delete l.ai);
  // Captions already in the language picked (an English browser on an English video, say): one line, no requests.
  if (state.lang && DualSub.targetCode(state.lang) === target) {
    state.lines.forEach((l) => setTranslation(l, ''));
    return status(`The captions are already in ${DualSub.languageName(target)}`, { label: 'Pick your language', open: 'openSettings' });
  }
  if (state.status) {
    // A note about an earlier language or a failed try no longer applies.
    state.status = null;
    const line = panel && panel.querySelector('.dualsub-status');
    if (line) line.textContent = `${state.lines.length} lines`;
  }
  const pieces = state.pieces;
  const split = translator === 'ai' && pieces && DualSub.isUnpunctuated(pieces) && DualSub.captionWords(pieces);
  await Promise.all([translateWithGoogle(gen), translator === 'ai' && (split ? splitWithAi(gen, pieces) : translateWithAi(gen))]);
}

async function translateWithGoogle(gen) {
  const lines = state.lines;
  let offset = 0;
  const batches = DualSub.batches(lines.map((l) => l.text)).map((texts) => ({ texts, from: (offset += texts.length) - texts.length }));
  for (const { texts, from } of playingFirst(batches, lines)) {
    const reply = await ask({ type: 'translate', texts, target, source: state.lang });
    if (gen !== state.gen) return; // a newer video, track or language took over
    if (reply.error) return status(`Translation failed: ${reply.error}`);
    reply.texts.forEach((tr, i) => lines[from + i].ai || setTranslation(lines[from + i], tr));
  }
}

// Where playback is, or where the address says it starts (?t=1234s, 1h2m3s) while the player has not jumped there yet.
function playingTime() {
  const t = new URLSearchParams(location.search).get('t') || '';
  const [, h = 0, m = 0, sec = 0] = t.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/) || [];
  const now = video() ? video().currentTime : 0;
  return now >= 1 ? now : h * 3600 + m * 60 + +sec; // the address only counts before playback has moved
}

// Batches ({ from, texts }) reordered to start with the one being played, then the rest of the video, then its
// start: a video opened at minute 20 (a saved word's line) gets its translation there first, as on Android.
function playingFirst(batches, lines) {
  const now = playingTime();
  const first = Math.max(0, batches.findIndex((b) => lines[b.from + b.texts.length - 1].end > now));
  return [...batches.slice(first), ...batches.slice(0, first)];
}

const AI_REST_MS = 30_000;

// Sends an AI [message], resting 30 s while the service stays busy after its quick retries (Android's
// AI_FAILURE_REST_MS), Google's lines showing meanwhile; null when a newer video, track or language took over.
async function askAi(message, gen) {
  for (;;) {
    status('AI translating…');
    const reply = await ask(message);
    if (gen !== state.gen) return null;
    if (!reply.busy) return reply;
    status(`AI is busy (${reply.error}). Showing Google; trying again in 30 s.`);
    await new Promise((r) => setTimeout(r, AI_REST_MS));
    if (gen !== state.gen) return null;
  }
}

function aiStopped(reply, count) {
  if (reply.noKey) status(`${count} lines · Google translation (AI has no key yet)`, { label: 'Set up AI', open: 'openGuide' });
  else status(`AI translation failed: ${reply.error}. Showing Google.`, { label: 'Help', open: 'openGuide' });
}

// The AI splits the track's timed words into sentences and translates them, a chunk at a time from the part being
// played; each chunk's lines replace the ones in its time range.
async function splitWithAi(gen, pieces) {
  const words = DualSub.captionWords(pieces);
  const trackEnd = pieces.at(-1).end;
  const chunkEnd = (to) => (to < words.length ? words[to].time : Math.max(trackEnd, words.at(-1).time + 1));
  const chunks = DualSub.wordChunks(words);
  const now = playingTime();
  const first = Math.max(0, chunks.findIndex(([, to]) => chunkEnd(to) > now));
  for (const [from, to] of [...chunks.slice(first), ...chunks.slice(0, first)]) {
    const start = words[from].time;
    const end = chunkEnd(to);
    const context = state.lines.filter((l) => l.end <= start).slice(-3).map((l) => l.text);
    const reply = await askAi({ type: 'aiSegment', source: state.lang || 'en', target, words: words.slice(from, to).map((w) => w.text), context }, gen);
    if (!reply) return;
    if (reply.error || reply.noKey) return aiStopped(reply, state.lines.length);
    const fresh = DualSub.segmentLines(reply.sentences, words.slice(from, to), end);
    state.lines = [...state.lines.filter((l) => !(l.start < end && l.end > start)), ...fresh].sort((a, b) => a.start - b.start);
    renderPanel();
    state.active = -1;
    analyzeEnglish(fresh);
  }
  status(`${state.lines.length} sentences · AI translation`);
}

async function translateWithAi(gen) {
  const lines = state.lines;
  const texts = lines.map((l) => l.text);
  const ranges = DualSub.aiBatches(texts);
  // The request with the playing part of the video goes first.
  const now = playingTime();
  const first = Math.max(0, ranges.findIndex(([, to]) => lines[to - 1].end > now));
  for (const [from, to] of [...ranges.slice(first), ...ranges.slice(0, first)]) {
    const context = texts.slice(Math.max(0, from - DualSub.AI_CONTEXT_SENTENCES), from);
    const reply = await askAi({ type: 'aiTranslate', source: state.lang || 'en', target, sentences: texts.slice(from, to), context }, gen);
    if (!reply) return;
    if (reply.error || reply.noKey) return aiStopped(reply, lines.length);
    reply.texts.forEach((tr, i) => {
      if (!tr) return; // Google keeps what the AI leaves out
      lines[from + i].ai = true;
      setTranslation(lines[from + i], tr);
    });
  }
  status(`${lines.length} lines · AI translation`);
}

function closeCard() {
  if (card) card.remove();
  card = null;
  picked = null;
}

async function openCard(anchor, token, line) {
  const word = token.text;
  closeCard();
  const v = video();
  if (v) v.pause();
  const current = (card = el('div', 'dualsub-card'));
  const [posLabel, posColor] = DualSub.PARTS_OF_SPEECH[token.pos];
  // The Android card: the word with Pronounce and Close, its part of speech, the meaning, grammar, dictionary, Save.
  const top = el('div', 'dualsub-card-top');
  const title = el('div', 'dualsub-card-word', word);
  title.style.setProperty('--pos', posColor);
  const pronounce = el('button', 'dualsub-icon', '🔊');
  pronounce.title = 'Pronounce';
  pronounce.onclick = () => speak(word);
  const close = el('button', 'dualsub-icon', '✕');
  close.title = 'Close (Esc)';
  close.onclick = closeCard;
  top.append(title, pronounce, close);
  const info = el('div', 'dualsub-card-info');
  if (token.pos !== 'other') info.append(el('span', 'dualsub-pos-label', posLabel));
  const meaning = el('div', 'dualsub-meaning');
  const translation = el('div', 'dualsub-card-tr', '…');
  meaning.append(el('span', 'dualsub-meaning-label', `Meaning (${target})`), translation);
  const grammar = el('div', 'dualsub-card-grammar');
  const defs = el('div', 'dualsub-card-defs');
  const save = el('button', 'dualsub-save', 'Save to vocabulary');
  const copy = el('button', null, 'Copy');
  copy.onclick = async () => {
    await navigator.clipboard.writeText(word);
    copy.textContent = 'Copied ✓';
  };
  const toAnki = el('button', null, 'Send to Anki');
  const note = el('div', 'dualsub-card-note');
  save.disabled = toAnki.disabled = true;
  const actions = el('div', 'dualsub-card-actions');
  actions.append(copy, toAnki);
  card.append(top, info, meaning, grammar, el('div', 'dualsub-section', 'Dictionary'), defs, save, actions, note);
  // In fullscreen only the player's subtree is drawn.
  (document.fullscreenElement || document.body).appendChild(card);

  const box = anchor.getBoundingClientRect();
  card.style.left = `${Math.max(8, Math.min(box.left, innerWidth - card.offsetWidth - 8))}px`;
  // Above the word when it is low on screen, below otherwise; never taller than the room there.
  const above = box.top > innerHeight / 2;
  if (above) card.style.bottom = `${innerHeight - box.top + 8}px`;
  else card.style.top = `${box.bottom + 8}px`;
  card.style.maxHeight = `min(60vh, ${above ? box.top - 16 : innerHeight - box.bottom - 16}px)`;

  const entry = {
    word, pos: token.pos, translation: '', meanings: [], sentence: line.text, sentenceTr: line.tr || '',
    lang: state.lang, target, videoId: state.videoId, title: document.title.replace(/ - YouTube$/, ''), time: line.start, end: line.end,
  };
  // English: dictionary form (went → go), IPA, CEFR-J level and the grammar points of this word in its sentence,
  // from the tagged line (Android's word card for English). When the word itself is part of no point, the points of
  // the whole sentence are listed, so the card always says what grammar the sentence uses.
  if (/^en\b/.test(state.lang) && token.start != null) {
    grammar.replaceChildren(el('div', 'dualsub-section', 'Grammar'), el('div', 'dualsub-note-text', 'Reading the sentence…'));
    (line.en ? Promise.resolve({ lines: [line.en] }) : ask({ type: 'englishLines', texts: [line.text] })).then((reply) => {
      if (card !== current) return;
      if (!reply.lines) return grammar.replaceChildren(el('div', 'dualsub-section', 'Grammar'), el('div', 'dualsub-note-text', `Grammar is not available: ${reply.error || 'no reply'}`));
      const en = reply.lines[0];
      const help = en.words.find((w) => w.start === token.start && w.end === token.end);
      if (help) {
        Object.assign(entry, { base: help.base, ipa: help.ipa, level: help.level });
        if (help.ipa) info.append(el('span', 'dualsub-ipa', `/${help.ipa}/`));
        if (help.base && help.base !== word.toLowerCase()) info.append(el('span', null, `→ ${help.base}`));
        if (help.level) info.append(el('span', `dualsub-level dualsub-level-${help.level[0]}`, help.level));
      }
      const own = DualSub.grammarForSelection(en.grammar, token.start, token.end);
      const points = own.length ? own : DualSub.grammarForSelection(en.grammar, 0, line.text.length); // each point once
      entry.grammar = own.map(({ form, level, text }) => ({ form, level, text }));
      const heading = own.length ? 'Grammar' : points.length ? 'Grammar in this sentence' : 'Grammar';
      grammar.replaceChildren(
        el('div', 'dualsub-section', heading),
        ...(points.length ? points : [null]).map((p) => {
          if (!p) return el('div', 'dualsub-note-text', 'No grammar point found in this sentence.');
          const item = el('div', 'dualsub-grammar-point');
          const form = el('div', 'dualsub-grammar-form', p.form);
          form.append(el('span', `dualsub-level dualsub-level-${p.level[0]}`, p.level));
          item.append(form, el('div', null, p.text));
          return item;
        }),
      );
    });
  }
  defs.textContent = 'Looking up…';
  // The translation shows as soon as it arrives; the dictionary is usually much slower.
  const translated = lookup('translate', word).then((tr) => {
    if (card !== current) return;
    entry.translation = tr.texts ? tr.texts[0] : '';
    translation.textContent = entry.translation || `Translation failed: ${tr.error}`;
  });
  const dict = await lookup('define', word);
  await translated;
  if (card !== current) return;
  defs.replaceChildren();
  entry.meanings = dict.entries ? DualSub.meanings(dict.entries, state.lang, htmlText) : [];
  for (const m of entry.meanings) {
    defs.append(el('div', 'dualsub-card-pos', m.pos));
    const list = el('ol');
    m.defs.forEach((d) => list.append(el('li', null, d)));
    defs.append(list);
  }
  if (!entry.meanings.length) defs.textContent = dict.error ? `Dictionary failed: ${dict.error}` : 'No dictionary entry.';
  save.disabled = toAnki.disabled = false;

  save.onclick = async () => {
    await DualSub.saveWord(entry);
    save.disabled = true;
    save.textContent = 'Saved to vocabulary ✓';
    const open = el('a', 'dualsub-card-link', 'Open saved words');
    open.href = '#';
    open.onclick = (e) => {
      e.preventDefault();
      ask({ type: 'openSavedWords' });
    };
    note.replaceChildren(open);
  };
  toAnki.onclick = async () => {
    toAnki.disabled = true;
    note.textContent = 'Sending…';
    const reply = await ask({ type: 'anki', card: entry });
    note.textContent = reply.error ? `Anki: ${reply.error}` : 'Added to the DualSub deck.';
    toAnki.disabled = !reply.error;
  };
}

document.addEventListener('mousedown', (e) => card && !card.contains(e.target) && !e.target.closest('.dualsub-word') && closeCard(), true);
document.addEventListener('keydown', (e) => e.key === 'Escape' && closeCard(), true);

function tick() {
  // After the extension is reloaded or updated, this copy stays in open tabs but can no longer reach the extension
  // ("Extension context invalidated"). It removes its panel and subtitles and stops, so the new copy works alone.
  if (!chrome.runtime?.id) {
    clearInterval(ticker);
    for (const node of [panel, overlay, card]) node?.remove();
    panel = overlay = card = null; // the old message listener stays; renderPanel() then does nothing
    document.documentElement.classList.remove('dualsub-on');
    return;
  }
  const id = currentVideoId();
  if (id !== state.videoId) reset(id);
  if (!id) return;
  mount();
  // During an ad the page's video is the ad: no subtitle for its time, and the wait for captions starts after it.
  if (document.querySelector('#movie_player.ad-showing')) {
    if (overlay) overlay.replaceChildren();
    state.active = -1;
    state.since = Date.now();
    return;
  }
  if (!state.lines.length) {
    // The player only downloads captions while CC is on, so press it once.
    const cc = document.querySelector('.ytp-subtitles-button');
    const waited = Date.now() - state.since;
    if (!state.ccTried && cc && waited > 1500) {
      state.ccTried = true;
      if (cc.getAttribute('aria-pressed') === 'false') cc.click();
    } else if (!state.ccRetried && cc && waited > 5000 && cc.getAttribute('aria-pressed') === 'true') {
      // After a page reload YouTube can show CC as on without loading the track (seen 2026-11-23 in Chrome 156):
      // turning it off and on makes the player download it.
      state.ccRetried = true;
      cc.click();
      setTimeout(() => cc.click(), 400);
    }
    if (state.ccTried && waited > 10000) status('No captions found for this video.');
    return;
  }
  document.documentElement.classList.add('dualsub-on');
  const v = video();
  const active = v ? DualSub.activeLine(state.lines, v.currentTime) : -1;
  if (active !== state.active) showLine(active);
  if (v) highlightSpoken(v.currentTime); // after a redraw too, so the new line's spoken word shows at once
}

function showLine(active) {
  const previous = panel && panel.querySelector('.dualsub-active');
  if (previous) previous.classList.remove('dualsub-active');
  state.active = active;
  const line = state.lines[active];
  if (line) prefetchWords(line);
  overlay.replaceChildren(...(line ? [original(line), el('div', 'dualsub-tr', line.tr || '')] : []));
  const row = state.rows[active];
  if (row) {
    row.classList.add('dualsub-active');
    const list = row.parentElement;
    list.scrollTop = row.offsetTop - list.offsetTop - list.clientHeight / 3; // scroll the list only, not the page
  }
}

// Underlines the word being spoken in the overlay and the active row, on auto-generated captions with word timing
// (Android's spoken-word highlight).
function highlightSpoken(now) {
  for (const box of [overlay, state.rows[state.active]]) {
    if (!box) continue;
    const words = box.querySelectorAll('.dualsub-word[data-t]');
    let spoken = null;
    for (const w of words) if (+w.dataset.t <= now) spoken = w;
    for (const w of words) w.classList.toggle('dualsub-spoken', w === spoken);
  }
}

const ticker = setInterval(tick, 150);
