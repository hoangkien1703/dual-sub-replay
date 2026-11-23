# AI subtitle translation with the user's own key

## Status

Implemented. The contributor asked on 2026-11-21: their Android app translates with AI, and Google's Persian is hard to
understand even in whole sentences.

## Context / problem

Both apps now send Google the same whole sentences, and Google's Persian is still stiff and often unclear. The
Android app's "Translate subtitles with → AI translation" (`translation/AiSubtitleTranslator.kt`,
[Android spec](https://github.com/hoangkien1703/dual-sub-replay/blob/main/docs/specs/2026-10-28-ai-subtitle-translation.md))
asks a language model with the user's own key. It sends whole stretches of the video with earlier sentences as
context, so the translation reads naturally.

## Goals

- Toolbar menu → **Translate subtitles with**: Google (default) or AI translation. AI translation shows Google's lines
  first and replaces each with the AI's as soon as its request answers, as Android does.
- The same services, default models and prompt as Android: Gemini, OpenRouter and OpenAI (OpenAI-compatible chat API),
  and Claude (Anthropic Messages API).

## Non-goals

- AI only, AI thinking levels, several keys per service with hand-over, and key pacing.
- A disk cache of AI translations: they are kept per page load, so reopening a video asks again.
- The AI assistant, and AI for word meanings.

## User-visible behavior

- Menu: Translate subtitles with [Google | AI translation]. With AI: Service, API key (a password field with a
  "Get a key" link), and Model (empty means the service's default).
- With AI on and a key saved, the panel header says "AI translating…" and then "N lines · AI translation". Lines
  switch from Google's to the AI's text, starting with the part of the video that is playing.
- On an AI failure (bad key, quota, network), the header says "AI translation failed: <reason>. Showing Google." and
  Google's lines stay.
- AI translation without a key behaves like Google.

## Technical constraints / invariants

- [Network rule](../../AGENTS.md#browser-extension): requests go only from `background.js`, and only to the
  services' own API hosts in `host_permissions`. No project server and no shared key.
- The key stays in `chrome.storage.local` (never `sync`) and is sent only to the chosen service. It is not encrypted:
  Chrome stores extension data in the profile folder. Android encrypts its keys with the Keystore; browsers have no
  equivalent, so the page says so.
- Subtitle text goes to the model as JSON data inside the user message, never as instructions.

## Proposed approach / plan

1. `translation/ai.js` (a data module, testable in Node): services and default models, Android's system prompt
   (`AI_PROMPT_VERSION` 2), the request JSON (`context` with the 4 previous sentences, and `sentences` with one row
   each, since rows here are whole sentences), batches of 60 sentences or 6,000 characters, and the reply parser
   (JSON in a fence or with text around it, stray-quote repair, markup and leading-comma cleanup).
2. `background.js` loads it with `importScripts` and sends `aiTranslate` requests in OpenAI chat format (with a JSON
   schema `response_format` for Gemini and OpenAI) or Anthropic Messages format.
3. `ui/content.js`: after Google's pass, when AI is chosen, send the batches in order, starting with the one at the
   playing time, then replace the lines and stop at the first failure.
4. `ui/popup.*`: the settings.

## Acceptance criteria

- [x] The prompt, request and batches match Android's for the same sentences (unit test).
- [x] Reply parsing reads fenced JSON, repairs a stray quote, strips markup and a leading comma, and gives null for a
      missing id (unit test).
- [x] With AI chosen and an invalid key, Google's lines stay and the header names the failure (headless Chrome).
- [ ] With the contributor's real key, the lines switch to the AI's Persian (contributor's Chrome).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `cd browser-extension && node --test` | Linux, Node 22 |
| Headless Chrome | AI chosen with a fake key: the failure message appears and Google's lines stay | Chrome 156 |
| Live AI | The contributor's key and a real video | Contributor's Chrome; no key here |

## Risks / edge cases

- Free Gemini keys have daily limits. A 30-minute video needs about 8 requests, and every reload asks again.
- Through slow proxies, a 60-sentence request can take tens of seconds. Google's lines show meanwhile.

## Version

0.3.0. A new feature with new host permissions.

## Implementation result

As planned. Google and the AI run at the same time, and Google never overwrites a line the AI already gave. Without a
key, the header says "add an AI key in the DualSub menu" and Google's lines stay.

## Validation result

- Unit tests: passed (`test/ai.test.js`, with the prompt, request, batches and reply parsing).
- Headless Chrome: passed. With no key, Google's lines show with the hint. With a fake key, each service's own
  refusal appears in the header while Google's lines stay: "Gemini returned HTTP 400: Please pass a valid API key",
  "Claude returned HTTP 401: invalid x-api-key", and OpenAI's 401. So the requests reach each service's real API.
- Live AI translation: not run, because there is no key here. The contributor checks with their own key.

## Follow-up 0.3.1: busy services

The contributor's Gemini key got "HTTP 503: This model is currently experiencing high demand", and AI translation stopped
for the rest of that video. Ported from Android:

- `background.js` asks again after 1 s and 3 s on 5xx (including Anthropic's 529), on a 429 that is not about
  quota, credit or billing, and on a network failure (`AI_BUSY_RETRY_DELAYS_MS`).
- When it is still busy, `ui/content.js` keeps Google's lines, says "AI is busy (…). Showing Google; trying again in
  30 s.", and asks for the same batch again after 30 s (`AI_FAILURE_REST_MS`). A bad key or another 4xx still stops
  the AI at once.

Validation: headless Chrome, with the service worker's `fetch` patched to answer 503. Three tries and then the
busy message, and after 30 s the same batch was asked again. Unit tests passed.

## Follow-up 0.6.2: a line's meaning stays under that line

The contributor reported that some translated lines missed part of their meaning, which showed up in the line before
instead (and the other way round). Auto captions without punctuation cut one long spoken sentence across several
lines (the 120-character / 8-second joining limit). Android's prompt asked the model to translate such a piece "so that
reading the translations in order gives the whole sentence naturally". In a verb-final language such as Persian, the
model then moved a line's verb or object into the next or previous line's translation.

The prompt is now version 3, a deliberate difference from Android's version 2. A piece is translated on its own, even
as an unfinished phrase ending with "…", and the model must never move words or meaning into another sentence's
translation, because each translation is shown under its own line. The cache key carries `AI_PROMPT_VERSION`, so
sentences cached with version 2 are asked again.

Validation: unit tests pass (`test/ai.test.js` pins version 3 and the new rule). The contributor checks the Persian with a
real key; there is no key here.
