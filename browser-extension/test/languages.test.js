// Run all tests: node --test (from the browser-extension folder)
const assert = require('node:assert');
require('../data/languages.js');
require('../data/language-codes.js');
require('../translation/ai.js');
const { TRANSLATION_LANGUAGES, targetCode, defaultTarget, googleTarget, languageName } = globalThis.DualSub;

// The app's list: 102 languages, Vietnamese and both Chinese scripts among them.
assert.strictEqual(TRANSLATION_LANGUAGES.length, 102);
for (const code of ['vi', 'zh', 'zh-TW', 'fa', 'en']) assert.ok(TRANSLATION_LANGUAGES.some(([c]) => c === code), code);

// The browser's language picks the default: Traditional Chinese by region or script, other Chinese is Simplified,
// otherwise the base code, and English when the app does not offer it.
assert.strictEqual(defaultTarget('zh-TW'), 'zh-TW');
assert.strictEqual(defaultTarget('zh-HK'), 'zh-TW');
assert.strictEqual(defaultTarget('zh-MO'), 'zh-TW');
assert.strictEqual(defaultTarget('zh-Hant'), 'zh-TW');
assert.strictEqual(defaultTarget('zh-CN'), 'zh');
assert.strictEqual(defaultTarget('zh'), 'zh');
assert.strictEqual(defaultTarget('vi'), 'vi');
assert.strictEqual(defaultTarget('pt-BR'), 'pt');
assert.strictEqual(defaultTarget('en-GB'), 'en');
assert.strictEqual(defaultTarget('iw'), 'he');
assert.strictEqual(defaultTarget('xx'), 'en');
assert.strictEqual(defaultTarget(''), 'en');

// A "zh-CN" stored by an earlier version becomes the app's "zh"; Google gets "zh-CN" back.
assert.strictEqual(targetCode('zh-CN'), 'zh');
assert.strictEqual(googleTarget('zh'), 'zh-CN');
assert.strictEqual(googleTarget('zh-TW'), 'zh-TW');
assert.strictEqual(googleTarget('fa'), 'fa');
assert.strictEqual(languageName('zh'), 'Simplified Chinese');
assert.strictEqual(languageName('zh-TW'), 'Traditional Chinese');
