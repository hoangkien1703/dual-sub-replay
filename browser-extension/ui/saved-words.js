// Saved words page: the list (search, delete, open the line on YouTube) and Practice with Android's review intervals.
// Classic scripts share one global scope with data/*.js, so their names are used through DualSub only.
const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  node.dir = 'auto';
  return node;
};
let words = [];
let revealed = false;
let videoFilter = ''; // a video id, or '' for all videos (Android's "Video: All videos")

const clock = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const intervalLabel = (ms) =>
  ms < DualSub.DAY_MS ? '10 min' : ms < 30 * DualSub.DAY_MS ? `${Math.round(ms / DualSub.DAY_MS)} d` : ms < 365 * DualSub.DAY_MS ? `${Math.round(ms / 30 / DualSub.DAY_MS)} mo` : `${(ms / 365 / DualSub.DAY_MS).toFixed(1)} y`;
const inVideo = (w) => !videoFilter || w.videoId === videoFilter;
const due = () => words.filter((w) => inVideo(w) && w.dueAt <= Date.now()).sort((a, b) => a.dueAt - b.dueAt);

function head(w) {
  const node = el('div', 'head');
  const [label, color] = DualSub.PARTS_OF_SPEECH[w.pos] || DualSub.PARTS_OF_SPEECH.other;
  node.style.setProperty('--pos', color);
  node.append(el('span', 'word', w.word));
  if (w.pos !== 'other') node.append(el('span', 'tag', label));
  if (w.level) node.append(el('span', `level level-${w.level[0]}`, w.level));
  const extra = [w.ipa && `/${w.ipa}/`, w.base && w.base !== w.word.toLowerCase() && `→ ${w.base}`].filter(Boolean).join('  ');
  if (extra) node.append(el('div', 'word-info', extra));
  return node;
}

// The sentence with the saved word marked.
function sentence(w) {
  const node = el('p', 'sentence');
  const at = w.sentence.indexOf(w.word);
  if (at < 0) node.textContent = w.sentence;
  else node.append(w.sentence.slice(0, at), el('mark', null, w.word), w.sentence.slice(at + w.word.length));
  return node;
}

function source(w) {
  const link = el('a', 'source', `▶ ${w.title || 'YouTube'} · ${clock(w.time)}`);
  link.href = `https://www.youtube.com/watch?v=${encodeURIComponent(w.videoId)}&t=${Math.floor(w.time)}s`;
  link.target = '_blank';
  return link;
}

function meaningList(w) {
  const node = el('div', 'meanings');
  for (const g of w.grammar || []) node.append(el('div', 'pos', `${g.form} · ${g.level}`), el('p', 'grammar-text', g.text));
  for (const m of w.meanings || []) {
    node.append(el('div', 'pos', m.pos));
    const list = el('ol');
    m.defs.forEach((d) => list.append(el('li', null, d)));
    node.append(list);
  }
  return node;
}

function renderList() {
  const query = $('search').value.trim().toLowerCase();
  const shown = words.filter((w) => inVideo(w) && (!query || [w.word, w.translation, w.sentence, w.sentenceTr, w.notes].join('\n').toLowerCase().includes(query)));
  $('empty').hidden = words.length > 0;
  $('words').replaceChildren(
    ...shown.map((w) => {
      const item = el('li', 'card');
      const remove = el('button', 'delete', '×');
      remove.title = `Delete ${w.word}`;
      remove.onclick = async () => {
        if (confirm(`Delete "${w.word}" from saved words?`)) await DualSub.deleteWord(w.id);
      };
      const foot = el('div', 'foot');
      foot.append(source(w), el('span', 'next', w.dueAt > Date.now() ? `Next review in ${intervalLabel(w.dueAt - Date.now())}` : 'Due now'));
      const notes = el('textarea', 'notes');
      notes.placeholder = 'Notes';
      notes.value = w.notes || '';
      notes.maxLength = 5000;
      notes.onchange = () => DualSub.updateWord(w.id, { notes: notes.value });
      item.append(remove, head(w), el('div', 'translation', w.translation), sentence(w), el('p', 'sentence-tr', w.sentenceTr), notes, foot);
      return item;
    }),
  );
}

async function rate(w, rating) {
  const interval = DualSub.reviewInterval(w.intervalMs, rating);
  revealed = false;
  await DualSub.updateWord(w.id, { dueAt: Date.now() + interval, intervalMs: interval });
}

function renderPractice() {
  const box = $('review');
  const [w] = due();
  if (!w) {
    const next = words.reduce((min, x) => Math.min(min, x.dueAt), Infinity);
    box.replaceChildren(el('p', 'done', words.length ? `All done. The next word is due in ${intervalLabel(next - Date.now())}.` : 'Save words under a YouTube video to practice them here.'));
    return;
  }
  const front = [head(w), sentence(w)];
  if (!revealed) {
    const show = el('button', 'primary', 'Show answer');
    show.onclick = () => {
      revealed = true;
      renderPractice();
    };
    box.replaceChildren(...front, show);
    return;
  }
  const ratings = el('div', 'ratings');
  DualSub.RATINGS.forEach((rating, i) => {
    const button = el('button', `rating ${rating}`, `${rating[0].toUpperCase()}${rating.slice(1)} · ${intervalLabel(DualSub.reviewInterval(w.intervalMs, rating))}`);
    button.title = `Key ${i + 1}`;
    button.onclick = () => rate(w, rating);
    ratings.append(button);
  });
  box.replaceChildren(...front, el('div', 'translation', w.translation), meaningList(w), el('p', 'sentence-tr', w.sentenceTr), ...(w.notes ? [el('p', 'notes-text', w.notes)] : []), source(w), ratings);
}

function renderVideos() {
  const videos = new Map();
  for (const w of words) if (w.videoId && !videos.has(w.videoId)) videos.set(w.videoId, w.title || w.videoId);
  if (videoFilter && !videos.has(videoFilter)) videoFilter = '';
  $('video').replaceChildren(new Option('All videos', ''), ...[...videos].map(([id, title]) => new Option(title, id)));
  $('video').value = videoFilter;
}

async function load() {
  words = await DualSub.savedWords();
  renderVideos();
  $('count').textContent = words.filter(inVideo).length;
  $('due').textContent = due().length;
  renderList();
  renderPractice();
}

function show(practice) {
  $('list-view').hidden = practice;
  $('practice-view').hidden = !practice;
  $('tab-list').setAttribute('aria-pressed', String(!practice));
  $('tab-practice').setAttribute('aria-pressed', String(practice));
}

$('tab-list').onclick = () => show(false);
$('tab-practice').onclick = () => show(true);
$('search').oninput = renderList;
$('video').onchange = () => {
  videoFilter = $('video').value;
  revealed = false;
  load();
};

// Export and import in the Android app's formats.
function download(name, text, type) {
  const link = el('a');
  link.href = URL.createObjectURL(new Blob([text], { type }));
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1000);
}
const today = () => new Date().toISOString().slice(0, 10);
$('export-json').onclick = () => download(`dualsub-backup-${today()}.json`, DualSub.exportBackup(words.filter(inVideo)), 'application/json');
$('export-tsv').onclick = () => download(`dualsub-anki-${today()}.txt`, DualSub.exportAnkiTsv(words.filter(inVideo)), 'text/tab-separated-values');
$('import').onclick = () => $('import-file').click();
$('import-file').onchange = async () => {
  const file = $('import-file').files[0];
  $('import-file').value = '';
  if (!file) return;
  try {
    if (file.size > 8 * 1024 * 1024) throw new Error('Import is larger than 8 MiB.');
    const result = await DualSub.importBackup(await file.text(), DualSub.partOfSpeech);
    $('transfer-note').textContent = `Imported ${result.added} words. ${result.duplicates} already saved, kept as they were. ${result.invalid} invalid, skipped.`;
  } catch (error) {
    $('transfer-note').textContent = `Import failed: ${error.message}`;
  }
};
// Space shows the answer, 1–4 rate it.
document.addEventListener('keydown', (e) => {
  if ($('practice-view').hidden || ['INPUT', 'TEXTAREA', 'SELECT'].includes(e.target.tagName)) return;
  const [w] = due();
  if (!w) return;
  if (e.key === ' ' && !revealed) {
    e.preventDefault();
    revealed = true;
    renderPractice();
  } else if (revealed && DualSub.RATINGS[e.key - 1]) rate(w, DualSub.RATINGS[e.key - 1]);
});
chrome.storage.onChanged.addListener((change, area) => area === 'local' && change.words && load()); // also words saved in a YouTube tab
if (location.hash === '#practice') show(true);
load();
