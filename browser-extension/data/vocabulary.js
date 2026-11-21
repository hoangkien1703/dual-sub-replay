// Saved words in chrome.storage.local ("words") and their review schedule.
// Port of the Android app's data/SavedWord.kt (reviewInterval) and VocabularyRepository.kt.

const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
const RATINGS = ['again', 'hard', 'good', 'easy'];

// Next review interval after [rating], as Android's reviewInterval.
function reviewInterval(previous, rating) {
  const firstTime = previous < DAY_MS;
  const next = {
    again: 10 * MINUTE_MS,
    hard: firstTime ? DAY_MS : previous * 1.2,
    good: firstTime ? 3 * DAY_MS : previous * 2,
    easy: firstTime ? 7 * DAY_MS : previous * 3,
  }[rating];
  return Math.round(Math.min(Math.max(next, 10 * MINUTE_MS), 3650 * DAY_MS));
}

// The same word from the same line gets the same id, so saving it again updates it instead of adding a copy.
const wordId = (word, sentence) => `${word}\n${sentence}`;

// Every saved word, newest first. Words saved before ids and review data existed get them here.
async function savedWords() {
  const { words = [] } = await chrome.storage.local.get('words');
  return words.map((w) => ({ dueAt: 0, intervalMs: 0, pos: 'other', ...w, id: w.id || wordId(w.word, w.sentence) }));
}

async function saveWord(entry) {
  const words = await savedWords();
  const id = wordId(entry.word, entry.sentence);
  const old = words.find((w) => w.id === id);
  const word = { ...entry, id, added: old ? old.added : Date.now(), dueAt: old ? old.dueAt : 0, intervalMs: old ? old.intervalMs : 0 };
  await chrome.storage.local.set({ words: [word, ...words.filter((w) => w.id !== id)] });
}

async function updateWord(id, change) {
  const words = await savedWords();
  await chrome.storage.local.set({ words: words.map((w) => (w.id === id ? { ...w, ...change } : w)) });
}

async function deleteWord(id) {
  const words = await savedWords();
  await chrome.storage.local.set({ words: words.filter((w) => w.id !== id) });
}

// Practice transfer in the Android app's formats (docs/practice-transfer.md there, data/PracticeTransfer.kt),
// so words move between the phone and the browser.
const MAX_TRANSFER_RECORDS = 20_000;
const MAX_NOTES = 5000;
const LANGUAGE_TAG = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const TSV_COLUMNS = ['word', 'meaning', 'reading', 'wordLanguage', 'meaningLanguage', 'sentence', 'translatedSentence', 'source', 'startMs', 'endMs', 'deck', 'sourceTitle', 'notes', 'thirdSentence', 'thirdLanguage'];

// One saved word as Android's encodeWord writes it.
function androidWord(w) {
  const startMs = Math.round((w.time || 0) * 1000);
  const endMs = Math.max(startMs, Math.round((w.end ?? w.time ?? 0) * 1000));
  return {
    id: w.id, word: w.word, reading: null, wordLanguage: w.lang || 'en', meaningLanguage: w.target || 'fa', meaning: w.translation || w.word,
    sentence: w.sentence || '', translatedSentence: w.sentenceTr || null, videoId: w.videoId || null, startMs, endMs, translated: false,
    online: VIDEO_ID.test(w.videoId || '') && endMs > startMs, dueAt: w.dueAt || 0, intervalMs: w.intervalMs || 0, deck: null,
    sourceTitle: w.title || null, ...(w.notes && { notes: w.notes }),
  };
}

const exportBackup = (words) => JSON.stringify({ format: 'DualSubReplay', version: 1, words: words.map(androidWord) }, null, 2);

function exportAnkiTsv(words) {
  const quote = (value) => `"${String(value ?? '').replace(/"/g, '""')}"`;
  const rows = words.map(androidWord).map((w) =>
    [w.word, w.meaning, '', w.wordLanguage, w.meaningLanguage, w.sentence, w.translatedSentence, w.videoId ? `https://www.youtube.com/watch?v=${w.videoId}` : '',
      w.startMs, w.endMs, '', w.sourceTitle, w.notes, '', ''].map(quote).join('\t'));
  return `#separator:Tab\n#html:false\n#columns:${TSV_COLUMNS.join('\t')}\n${rows.join('\n')}`;
}

// An Android (or DualSub) JSON backup's words in this extension's shape; [partOfSpeech] colors them.
// Invalid records are counted and skipped, with Android's validateTransferWord rules.
function parseBackup(text, partOfSpeech) {
  const json = JSON.parse(text.replace(/^﻿/, ''));
  if (json.format !== 'DualSubReplay' || json.version !== 1 || !Array.isArray(json.words)) throw new Error('Unsupported backup format or version.');
  if (json.words.length > MAX_TRANSFER_RECORDS) throw new Error('Import exceeds 20,000 records.');
  let invalid = 0;
  const words = [];
  for (const j of json.words) {
    const valid = j && typeof j.id === 'string' && j.id.trim() && typeof j.word === 'string' && j.word.trim() && typeof j.meaning === 'string' && j.meaning.trim() &&
      LANGUAGE_TAG.test(j.wordLanguage) && LANGUAGE_TAG.test(j.meaningLanguage) && typeof j.sentence === 'string' &&
      Number.isFinite(j.startMs) && j.startMs >= 0 && Number.isFinite(j.endMs) && j.endMs >= j.startMs &&
      (j.videoId == null || j.videoId === '' || VIDEO_ID.test(j.videoId)) && !(j.dueAt < 0) && !(j.intervalMs < 0);
    if (!valid) {
      invalid++;
      continue;
    }
    words.push({
      id: j.id, word: j.word, pos: partOfSpeech(j.word, j.wordLanguage), translation: j.meaning, meanings: [], sentence: j.sentence,
      sentenceTr: j.translatedSentence || '', lang: j.wordLanguage, target: j.meaningLanguage, videoId: j.videoId || null,
      title: typeof j.sourceTitle === 'string' ? j.sourceTitle.replace(/\s+/g, ' ').trim().slice(0, 200) : '',
      time: j.startMs / 1000, end: j.endMs / 1000, added: Date.now(), dueAt: j.dueAt || 0, intervalMs: j.intervalMs || 0,
      notes: String(j.notes || j.personalNotes || '').slice(0, MAX_NOTES), // personalNotes: Triple Sub Replay's backups, as Android reads them
    });
  }
  return { words, invalid };
}

// [incoming] added to [existing]; a word whose id is already saved keeps the saved copy (Android's keep-existing default).
function mergeImport(existing, incoming) {
  const ids = new Set(existing.map((w) => w.id));
  const added = [];
  for (const w of incoming) if (!ids.has(w.id)) ids.add(w.id), added.push(w); // the first of duplicates in one file wins
  return { words: [...added, ...existing], added: added.length, duplicates: incoming.length - added.length };
}

async function importBackup(text, partOfSpeech) {
  const { words: incoming, invalid } = parseBackup(text, partOfSpeech);
  const result = mergeImport(await savedWords(), incoming);
  await chrome.storage.local.set({ words: result.words });
  return { added: result.added, duplicates: result.duplicates, invalid };
}

globalThis.DualSub = Object.assign(globalThis.DualSub || {}, {
  DAY_MS, RATINGS, reviewInterval, savedWords, saveWord, updateWord, deleteWord,
  exportBackup, exportAnkiTsv, parseBackup, mergeImport, importBackup,
});
