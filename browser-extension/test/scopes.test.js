// Run all tests: node --test (from the extension folder)
// Classic scripts loaded together share one global scope: a name declared twice stops a page from loading
// (const/let) or silently replaces a function. This happened three times, so every group is checked here.
const assert = require('node:assert');
const fs = require('node:fs');
const root = `${__dirname}/..`;
const manifest = JSON.parse(fs.readFileSync(`${root}/manifest.json`, 'utf8'));
const pageScripts = (page) => [...fs.readFileSync(`${root}/${page}`, 'utf8').matchAll(/<script src="([^"]+)"/g)].map((m) => `ui/${m[1]}`.replace(/ui\/\.\.\//, ''));
const imported = [...fs.readFileSync(`${root}/background.js`, 'utf8').matchAll(/importScripts\(([^)]*)\)/g)].flatMap((m) => [...m[1].matchAll(/'([^']+)'/g)].map((n) => n[1]));

const groups = {
  'content scripts': manifest.content_scripts.find((c) => c.js.includes('ui/content.js')).js,
  'service worker': ['background.js', ...imported],
  popup: pageScripts('ui/popup.html'),
  'saved words page': pageScripts('ui/saved-words.html'),
};

for (const [group, files] of Object.entries(groups)) {
  const seen = new Map();
  for (const file of files) {
    const source = fs.readFileSync(`${root}/${file}`, 'utf8');
    // Top-level declarations only: they start at the beginning of a line.
    for (const [, name] of source.matchAll(/^(?:async\s+)?(?:function\s*\*?\s*|const\s+|let\s+|var\s+|class\s+)([A-Za-z_$][\w$]*)/gm)) {
      assert.ok(!seen.has(name), `${group}: "${name}" is declared in both ${seen.get(name)} and ${file}`);
      seen.set(name, file);
    }
  }
  assert.ok(seen.size > 0, `${group}: no scripts found`);
}
