const $ = (id) => document.getElementById(id);

chrome.storage.sync.get({ target: 'fa', translator: 'google' }, (v) => {
  $('target').value = v.target;
  $('translator').value = v.translator;
  $('ai').hidden = v.translator !== 'ai';
});
$('target').onchange = () => chrome.storage.sync.set({ target: $('target').value });
$('translator').onchange = () => {
  $('ai').hidden = $('translator').value !== 'ai';
  chrome.storage.sync.set({ translator: $('translator').value });
};

// The AI service, key and model stay in this browser (storage.local, never synced); the key goes only to that service.
for (const [id, service] of Object.entries(DualSub.AI_SERVICES)) $('service').append(new Option(service.label, id));
function showService() {
  const service = DualSub.AI_SERVICES[$('service').value];
  $('get-key').href = service.keys;
  $('model').placeholder = service.model;
  $('key-note').textContent = `Your key stays in this browser, unencrypted in the Chrome profile, and is sent only to ${service.label}.`;
}
chrome.storage.local.get({ ai: {} }, ({ ai }) => {
  $('service').value = ai.service || 'gemini';
  $('key').value = ai.key || '';
  $('model').value = ai.model || '';
  showService();
});
// Saved on change (not on each keystroke): every save makes open videos translate again.
const saveAi = () => chrome.storage.local.set({ ai: { service: $('service').value, key: $('key').value.trim(), model: $('model').value.trim() } });
$('service').onchange = () => {
  showService();
  saveAi();
};
$('key').onchange = saveAi;
$('model').onchange = saveAi;

DualSub.savedWords().then((words) => ($('count').textContent = words.length));
$('open').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('ui/saved-words.html') });
const openGuide = (e) => {
  e?.preventDefault();
  chrome.tabs.create({ url: chrome.runtime.getURL('ui/guide.html') });
};
$('guide').onclick = openGuide;
$('how').onclick = openGuide;
