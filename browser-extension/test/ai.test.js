// Run all tests: node --test (from the extension folder)
const assert = require('node:assert');
require('../translation/ai.js');
const { aiSystemPrompt, aiRequest, aiBatches, parseAiReply } = globalThis.DualSub;

// The prompt names both languages as Android's does.
const prompt = aiSystemPrompt('en', 'fa');
assert.ok(prompt.startsWith('You translate video subtitles from English (en) to Persian (fa) for someone learning English who reads Persian.'));
assert.ok(prompt.includes('Answer with JSON only'));
// Version 3: a line's meaning stays in its own translation; Android's "gives the whole sentence" moved words between lines.
assert.strictEqual(globalThis.DualSub.AI_PROMPT_VERSION, 3);
assert.ok(prompt.includes('never move words or meaning into the translation of an earlier or later sentence'));
assert.ok(!prompt.includes('reading the translations in order gives the whole sentence'));

// The request is data: context, then numbered sentences with one row each.
assert.deepStrictEqual(JSON.parse(aiRequest(['Before.'], ['One.', 'Two.'])), {
  context: ['Before.'],
  sentences: [{ id: 1, rows: ['One.'] }, { id: 2, rows: ['Two.'] }],
});

// Batches: at most 60 sentences and 6,000 characters each.
assert.deepStrictEqual(aiBatches(Array(130).fill('short')), [[0, 60], [60, 120], [120, 130]]);
assert.deepStrictEqual(aiBatches(['x'.repeat(4000), 'y'.repeat(2500), 'z']), [[0, 1], [1, 3]]);
assert.deepStrictEqual(aiBatches([]), []);

const sentences = ['so in college I was a government major', 'It is about procrastination.', 'OK'];
// A fenced reply with text around it; markup and a leading comma are removed; a missing id is null.
assert.deepStrictEqual(
  parseAiReply('Here you go:\n```json\n{"sentences":[{"id":1,"rows":["<i>در دانشگاه</i> رشته‌ام علوم سیاسی بود"]},{"id":3,"rows":[", باشه"]}]}\n```', sentences),
  ['در دانشگاه رشته‌ام علوم سیاسی بود', null, 'باشه'],
);
// A quote the model left unescaped is repaired.
assert.deepStrictEqual(parseAiReply('{"sentences":[{"id":2,"rows":["درباره ی "تعلل" است."]}]}', sentences), [null, 'درباره ی "تعلل" است.', null]);
// A copy of the source before the translation is cut; a bare copy is no translation; a short word like "OK" may stay.
assert.deepStrictEqual(
  parseAiReply('{"sentences":[{"id":1,"rows":["so in college I was a government major - در دانشگاه"]},{"id":2,"rows":["It is about procrastination."]},{"id":3,"rows":["OK"]}]}', sentences),
  ['در دانشگاه', null, 'OK'],
);
assert.throws(() => parseAiReply('Sorry, I cannot help.', sentences), /no JSON/);
