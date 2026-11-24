// Run all tests: node --test (from the browser-extension folder)
// The extension loads unpacked with no build step, so it carries copies of the app's English word levels and IPA;
// they must stay the same files as app/src/main/learner-data/ (regenerate those with their tools/ scripts, then copy).
const assert = require('node:assert');
const fs = require('node:fs');
for (const name of ['en-levels.txt', 'en-ipa.txt']) {
  const app = `${__dirname}/../../app/src/main/learner-data/${name}`;
  if (!fs.existsSync(app)) continue; // the extension folder on its own (a store package) has no app beside it
  assert.ok(fs.readFileSync(app).equals(fs.readFileSync(`${__dirname}/../data/learner-data/${name}`)), `${name} differs from the app's copy`);
}
