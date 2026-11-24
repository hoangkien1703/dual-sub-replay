// The guide's AI setup: pick a service, follow its steps (the Android app's setup text), paste the key here.
const $ = (id) => document.getElementById(id);

// Android's ai_provider_*_note and ai_setup_step_* strings, with "Tap" made "Click".
const SETUP = {
  gemini: { note: 'Free key with a Google account', get: 'and sign in with your Google account.', create: 'Click “Create API key”, then copy the key (it starts with AIza).' },
  openrouter: { note: 'Many models, some of them free', get: 'and sign in or create an account.', create: 'Create an API key, then copy it (it starts with sk-or-).' },
  openai: { note: 'Paid, from the makers of ChatGPT', get: 'and sign in. OpenAI charges for use, so add credit first.', create: 'Click “Create new secret key”, then copy the key (it starts with sk-).' },
  claude: { note: 'Paid, from Anthropic', get: 'and sign in. Anthropic charges for use, so add credit first.', create: 'Click “Create Key”, give it a name, then copy the key (it starts with sk-ant-).' },
};
const PREFIX = { gemini: 'AIza', openrouter: 'sk-or-', openai: 'sk-', claude: 'sk-ant-' };
let chosen = 'gemini';

function render() {
  for (const button of $('services').children) button.setAttribute('aria-pressed', String(button.dataset.id === chosen));
  const service = DualSub.AI_SERVICES[chosen];
  const link = document.createElement('a');
  link.href = service.keys;
  link.target = '_blank';
  link.textContent = chosen === 'gemini' || chosen === 'openrouter' ? 'Get a free key' : 'Get a key';
  const steps = [document.createElement('li'), document.createElement('li'), document.createElement('li')];
  steps[0].append('Click ', link, ` ${SETUP[chosen].get}`);
  steps[1].textContent = SETUP[chosen].create;
  steps[2].textContent = 'Paste the key below and click Save.';
  $('steps').replaceChildren(...steps);
  $('key').placeholder = `${service.label} API key (${PREFIX[chosen]}…)`;
}

for (const [id, service] of Object.entries(DualSub.AI_SERVICES)) {
  const button = document.createElement('button');
  button.className = 'service';
  button.dataset.id = id;
  const name = document.createElement('b');
  name.textContent = service.label;
  const note = document.createElement('small');
  note.textContent = SETUP[id].note;
  button.append(name, note);
  button.onclick = () => {
    chosen = id;
    render();
  };
  $('services').append(button);
}

chrome.storage.local.get({ ai: {} }, ({ ai }) => {
  if (ai.service) chosen = ai.service;
  if (ai.key) $('result').textContent = `A ${DualSub.AI_SERVICES[chosen].label} key is already saved. Paste a new one to replace it.`;
  render();
});

$('save').onclick = async () => {
  const key = $('key').value.trim();
  if (!key) return void ($('result').textContent = 'Paste your key first.');
  const { ai = {} } = await chrome.storage.local.get('ai');
  await chrome.storage.local.set({ ai: { service: chosen, key, model: ai.service === chosen ? ai.model || '' : '' } });
  await chrome.storage.sync.set({ translator: 'ai' });
  $('key').value = '';
  const label = DualSub.AI_SERVICES[chosen].label;
  $('result').className = key.startsWith(PREFIX[chosen]) ? 'ok' : 'bad';
  $('result').textContent = key.startsWith(PREFIX[chosen])
    ? `Saved. AI translation with ${label} is on; reload your YouTube video.`
    : `Saved, but ${label} keys usually start with ${PREFIX[chosen]}. If translation fails with "invalid API key", check you picked the right service.`;
};
