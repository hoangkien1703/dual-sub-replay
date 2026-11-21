// Caption tracks: parsing and timing. Shared by ui/content.js and test/captions.test.js.

// A YouTube json3 timedtext reply as [{start, end, text, words?}], times in seconds. Auto-generated tracks time
// each chunk (tOffsetMs); those lines keep [words] = [{text, time}], which drive the spoken-word highlight
// (Android's json3Words).
// shortcut: json3 only (what the desktop web player asks for); add srv3/XML if a track ever arrives in it.
function parseCaptions(body) {
  const lines = [];
  for (const event of JSON.parse(body).events || []) {
    if (!event.segs || event.tStartMs == null) continue;
    const text = event.segs.map((s) => s.utf8 || '').join('').replace(/\s+/g, ' ').trim();
    if (!text) continue;
    const line = { start: event.tStartMs / 1000, end: (event.tStartMs + (event.dDurationMs || 0)) / 1000, text };
    if (event.segs.some((s) => s.tOffsetMs != null)) {
      line.words = event.segs
        .map((s) => ({ text: (s.utf8 || '').replace(/\s+/g, ' ').trim(), time: (event.tStartMs + (s.tOffsetMs || 0)) / 1000 }))
        .filter((w) => w.text);
    }
    lines.push(line);
  }
  // Auto-generated tracks roll two lines at once; end each line where the next begins.
  for (let i = 0; i < lines.length - 1; i++) {
    const next = lines[i + 1].start;
    if (next > lines[i].start && next < lines[i].end) lines[i].end = next;
  }
  return lines;
}

// Where each timed word chunk starts in [text], or null when one cannot be found (then nothing is highlighted).
// Case does not count: the AI's sentences start with a capital that the captions' words lack.
function placeWords(text, words) {
  const lower = text.toLowerCase();
  let cursor = 0;
  const places = [];
  for (const w of words) {
    const at = lower.indexOf(w.text.toLowerCase(), cursor);
    if (at < 0) return null;
    places.push(at);
    cursor = at + w.text.length;
  }
  return places;
}

// The time each token of [tokens] (from DualSub.tokens, covering [line].text in order) is spoken, or null when the
// line has no word timing.
function wordTimes(line, tokens) {
  const places = line.words && placeWords(line.text, line.words);
  if (!places) return null;
  let offset = 0;
  return tokens.map((token) => {
    const at = offset;
    offset += token.text.length;
    if (!token.word) return null;
    let k = -1;
    while (k + 1 < places.length && places[k + 1] <= at) k++;
    return k >= 0 ? line.words[k].time : null;
  });
}

// Android's sentenceBreak: whitespace after a sentence end (optionally after a closing quote or bracket), or right
// after 。！？ in Japanese and Chinese, which put no space there.
const SENTENCE_BREAK = /(?<=[.!?。！？…])\s+|(?<=[.!?。！？…]["'’”)])\s+|(?<=[。！？])(?=[^\s」』）)"'’”。！？…])/;

// Cuts pieces at sentence ends inside them ("…the price. Even at, look at"), so a sentence line never carries the
// start of the next one (Android's splitAtSentenceEnds). Timed words give the cut times; otherwise time is shared
// by length.
function splitAtSentenceEnds(lines) {
  return lines.flatMap((line) => {
    const parts = line.text.split(SENTENCE_BREAK).map((p) => p.trim()).filter(Boolean);
    if (parts.length <= 1) return [line];
    let cursor = 0;
    const from = parts.map((p) => {
      const at = line.text.indexOf(p, cursor);
      cursor = at + p.length;
      return at;
    });
    const places = line.words && placeWords(line.text, line.words);
    const span = line.end - line.start;
    const startOf = (i) => {
      if (i === 0) return line.start;
      const k = places ? places.findIndex((at) => at >= from[i]) : -1;
      return k >= 0 ? line.words[k].time : line.start + (span * from[i]) / line.text.length;
    };
    return parts.map((text, i) => {
      const piece = { start: startOf(i), end: i + 1 < parts.length ? startOf(i + 1) : line.end, text };
      if (places) {
        const to = i + 1 < parts.length ? from[i + 1] : Infinity;
        piece.words = line.words.filter((_, k) => places[k] >= from[i] && places[k] < to);
      }
      return piece;
    });
  });
}

// Joins caption pieces into sentences so Google translates whole sentences, not fragments.
// Port of the Android app's sentenceCaptionUnits (ui/TranslationCoordinator.kt), with its limits:
const MAX_UNIT_CHARACTERS = 120; // unpunctuated auto captions stop joining here...
const MAX_UNIT_SECONDS = 8; // ...or here
const MAX_SENTENCE_TAIL_CHARACTERS = 32; // a short lowercase sentence end ("a way.") joins even past them
const MAX_JOIN_PAUSE_SECONDS = 1.2;
const MAX_UNFINISHED_PAUSE_SECONDS = 3; // after a Japanese/Chinese piece, when the captions mark ends with 。
const SENTENCE_END = /[.!?。！？…]["'’”)]*$/;
const isCjk = (ch) => ch != null && ch.charCodeAt(0) >= 0x3000 && ch.charCodeAt(0) <= 0x9fff;

function isSentenceTail(text) {
  const trimmed = text.trim();
  const letter = trimmed.match(/\p{L}/u);
  return trimmed.length <= MAX_SENTENCE_TAIL_CHARACTERS && SENTENCE_END.test(trimmed) &&
    letter != null && letter[0] === letter[0].toLowerCase() && letter[0] !== letter[0].toUpperCase();
}

function sentenceLines(lines) {
  const marksCjkEnds = lines.length > 0 && lines.filter((l) => /[。！？]$/.test(l.text.trim())).length * 5 >= lines.length;
  const out = [];
  for (const next of lines) {
    const previous = out[out.length - 1];
    if (previous) {
      const left = previous.text.trim();
      const pauseLimit = marksCjkEnds && isCjk(left.slice(-1)) ? MAX_UNFINISHED_PAUSE_SECONDS : MAX_JOIN_PAUSE_SECONDS;
      const continues = !SENTENCE_END.test(left) && next.start - previous.end <= pauseLimit;
      const fits = next.end - previous.start <= MAX_UNIT_SECONDS && previous.text.length + next.text.length <= MAX_UNIT_CHARACTERS;
      if (continues && (fits || isSentenceTail(next.text))) {
        const separator = isCjk(previous.text.slice(-1)) && isCjk(next.text[0]) ? '' : ' ';
        out[out.length - 1] = { start: previous.start, end: Math.max(previous.end, next.end), text: previous.text + separator + next.text };
        if (previous.words && next.words) out[out.length - 1].words = [...previous.words, ...next.words];
        continue;
      }
    }
    out.push({ ...next });
  }
  return out;
}

// Index of the line playing at [time], or -1 in a gap.
function activeLine(lines, time) {
  let lo = 0;
  let hi = lines.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].start <= time) {
      found = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return found >= 0 && time < lines[found].end ? found : -1;
}

// Splits texts into Google Translate batches of at most maxTexts texts and maxChars characters.
function batches(texts, maxTexts = 50, maxChars = 4000) {
  const out = [];
  let current = [];
  let chars = 0;
  for (const text of texts) {
    if (current.length && (current.length >= maxTexts || chars + text.length > maxChars)) {
      out.push(current);
      current = [];
      chars = 0;
    }
    current.push(text);
    chars += text.length;
  }
  if (current.length) out.push(current);
  return out;
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, { parseCaptions, splitAtSentenceEnds, sentenceLines, wordTimes, activeLine, batches });
