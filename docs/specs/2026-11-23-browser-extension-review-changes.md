# Browser extension: languages, AI request pacing and cache size

## Status

Implemented. The changes the owner's review of PR #253 asked for (2026-11-23).

## Context / problem

- The menu offered 15 hand-picked languages, and the default was Persian, the contributor's own language. The app
  offers 102 languages and follows the phone's language.
- `translateWithAi` and `splitWithAi` sent every batch of a video back to back. A long video on a fast model could go
  over a free Gemini key's per-minute limit. The app keeps each key and model to 10 automatic requests in any minute
  (`AiKeyPacer`).
- The `aic:`/`ais:` AI cache was never pruned, so a heavy user could fill `storage.local`.

## User-visible behavior

- The menu lists the app's 102 languages in its order. Until the user picks one, subtitles are translated into the
  browser's language when the app offers it (Traditional Chinese for zh-TW, zh-HK, zh-MO and zh-Hant; other Chinese
  is Simplified), else English. A language picked earlier stays.
- AI translation of a long video goes at most 10 requests a minute per key and model. The status line shows "AI
  translating…" while a request waits its turn.
- Nothing visible changes for the cache: once Chrome's extension storage passes 80% of its quota, the oldest cached
  AI answers are dropped.

## Proposed approach / plan

1. `tools/build_extension_languages.py` generates `data/languages.js` from `TranslationLanguages.all`.
   `data/language-codes.js` ports `targetCode`, adds `defaultTarget(uiLanguage)`, and `googleTarget` (zh → zh-CN, as
   `googleLanguageCode`). `ui/content.js`, `ui/popup.js` and `background.js` use them.
2. `aiPacerWait(sent, now)` in `translation/ai.js` (pure); `background.js` waits for its turn before every request in
   `aiAsk`, retries included.
3. Cache entries become `{ t, v }`, and older plain values still read. `aiCacheToDrop` picks the oldest entries;
   `pruneAiCache` runs after each write.
4. Repository asks: `Maintainer: @parastouh` in `browser-extension/README.md`, and a sentence in AGENTS.md saying
   Android PRs don't have to update the extension. The generated grammar file names its real script, and comments and
   specs say "contributor" for the contributor's requests.

## Acceptance criteria

- [x] `test/languages.test.js`: 102 languages including vi, zh and zh-TW. Defaults for zh-TW, zh-HK, zh-MO, zh-Hant →
      zh-TW; zh-CN and zh → zh; pt-BR → pt; iw → he; an unknown code or none → en. Google gets zh-CN for zh.
- [x] `test/ai.test.js`: on a fake clock, 30 requests asked at once are sent 10 a minute, never more than 10 in any
      60 s. Requests older than a minute stop counting.
- [x] `test/ai.test.js`: the cache drops nothing below 80% of the quota; above it, the oldest entries (untimed ones
      first) go until half is free.
- [x] Headless Chrome (Chrome 156, Linux, English UI): the menu lists 102 languages with English selected. After
      Persian is picked, YouTube lines translate into Persian.

## Version

0.8.0.

## Validation result

Passed as above. `node --test` passes 8 test files, and `node --check` passes every file.

## Owner follow-up (2026-11-24)

The owner took the PR over to merge it. Three small changes from checking it in headless Chrome:

- **Captions already in the chosen language.** With an English browser on an English video, the default target is the
  caption language, so every line showed itself twice. Now nothing is translated (no requests), the second line stays
  empty, and the status line says "The captions are already in English · Pick your language" (opens the menu).
  Picking another language clears that note and translates as usual.
- **Dictionary failures.** Wiktionary's 404 still means "No dictionary entry"; any other failure (a 429 rate limit,
  a server error) now shows "Dictionary failed: Wiktionary returned HTTP …" instead of looking like a missing word.
- **Word card scrollbar** is thin and dark instead of Chrome's white default.

Checked in headless Chromium with the extension loaded and an offline YouTube page fixture: English → English shows
the note and empty second lines; switching to Vietnamese translates both lines and the status returns to "2 lines".
