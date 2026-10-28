# Translate subtitles with AI, and keep sentence ends with their sentence

## Status

Implemented. In the "Study 4You word highlight and translation" project thread (2026-10-28) Kien
compared this app with 4You on the same video and asked for the two recommendations of that
study: fix the sentence-end bug, and add "an optional AI translation that uses the AI keys" as a
setting that is off by default. He then asked to choose the AI model for translation and to make
it last all day on free Gemini keys. Merge and release stay with the owner.

## Context / problem

At 0:37 of a Theo (t3.gg) video, YouTube's auto captions read "…almost misleading in" / "a way.
The reality is that two different" / "things are happening at once right now.". This app showed:

| Row | Our translation |
| --- | --- |
| "misleading in" | "hiểu lầm trong" |
| "A way." | "Xa." ("far") |
| "The reality is that two different things are" | "Thực tế là hiện tại có hai" |
| "happening at once right now." | "điều khác nhau đang xảy ra cùng một lúc." |

Two causes:

1. **Sentence tail on its own.** The first merged line was already over the 8 s limit of a
   translation unit (`MAX_UNIT_DURATION_MS`), so "a way." could not join it, became its own
   unit, was capitalized by `SubtitleMerger.merge` as if it started a sentence, and Google
   translated "A way." alone.
2. **Cut by position.** One Google translation of the whole sentence is cut across its rows by
   the word count of each row prefix's own translation, or by character proportion
   (`TranslationSlicing.kt`), so "khác nhau" ("different things") slid onto the next row.

4You (studied without copying anything of theirs) sends windows of caption lines with the lines
around them as context to its own server, where a model returns a translation already split per
line. This change does the same with the AI service the user already set up for the assistant.

## Goals

- A short lowercase piece that ends a sentence ("a way.") stays lowercase and joins the sentence
  before it, even when that unit is already at its size or time limit.
- Settings → Translation → **Translate with AI (beta)**, off by default, shown under Google
  Translate while Google is the engine. On, the AI assistant's service, model and keys
  (More settings → AI assistant) translate the playing window and the background look-ahead:
  sentences of up to 40 per request, each split into its display rows, with the previous
  request's last 4 sentences as context. The model answers JSON with one translation per row.
- Each row shows exactly the part the model gave it.
- Settings → Translation → **AI model for translation** (shown while Translate with AI is on): the
  service's suggested models plus the assistant's own model. The default is the service's first
  suggestion (Gemini Flash-Lite), not the assistant's model, which may be slow and thinking.
- Free keys last: thinking off where the service allows it (`reasoning_effort` `none` for Gemini,
  `minimal` for OpenAI, dropped if refused), up to 60 sentences / 6,000 characters per request
  (about 8 requests for 30 minutes of speech), requests take turns over the saved keys with at
  most one request per key every 6 s (10 a minute; Gemini's free tier allows 15), a key that
  answers 429 or "quota" rests 1 minute, doubling up to 30 minutes while it keeps refusing (Google
  translates meanwhile), a rejected key rests 6 hours, and a 16 MB cache so watching again sends
  nothing.
- Google stays the fallback, so subtitles never stop: a sentence the model left out, an
  unreadable reply, a refused key, no key, or a model slower than 6 s for the playing window
  all translate with Google as before.

## Non-goals

- A project server or shared key (the [AI assistant invariant](../../AGENTS.md#architecture-invariants) stands).
- AI translation on the on-device path (after Google failed, YouTube's translated captions and
  the device take over as before).
- Letting the assistant switch this setting (it is part of the AI settings it cannot touch).
- Server or on-device speech recognition for word timings (option 5 of the study).

## User-visible behavior

Before: as in the table above. After, without AI: "a way." ends the row before ("misleading in a
way."). With Translate with AI on and a key saved: rows read like 4You's ("theo một cách nào
đó." / "Thực tế là có hai chuyện khác nhau" / "đang diễn ra cùng lúc ngay lúc này."). Turning
the switch on or off reloads the current video's translations. Without a key the switch changes
nothing. Reset all settings turns it off.

## Technical constraints / invariants

- Subtitle text goes to the service the user chose and is billed to the user's key, like the
  assistant's questions. The setting text says so.
- Keys are read from the same encrypted `ai_assistant_keys` store; nothing new is saved.
- Translations are cached in memory and in `cacheDir/ai-translations`, keyed by service, model and
  language pair, so another model translates again.

## Proposed approach / plan

1. `SubtitleMerger.merge`: keep the lowercase start of a merged line that begins with a short
   sentence end while the line before is open. `sentenceCaptionUnits`: such a tail
   (`MAX_SENTENCE_TAIL_CHARACTERS`, 32) joins the open unit past the size and time limits.
2. `translation/AiSubtitleTranslator.kt`: group the texts the player asks for (each sentence
   and its row prefixes, `translationTexts`) back into sentences with rows, build the prompt,
   read the JSON reply (also inside a code fence), and cache the whole sentence plus each row
   prefix as the joined rows.
3. `translationSlices`: when a prefix's translation is literally the start of the whole
   translation, cut exactly there, so AI rows survive unchanged.
4. `assistant/AiTranslationAccess.kt`: the chosen service and model with each saved key in turn
   (a rejected, out-of-credit or rate-limited key hands over to the next), and the assistant's
   busy retries.
5. `AppViewModel.followGoogleTranslation`: AI first for the window (6 s wait, the request runs
   on and fills the cache) and for the background steps, Google for the rest.
6. Setting row, `ai_subtitle_translation` preference, Reset all settings, strings in all 8 languages.
7. `AiKeyPacer` (`assistant/AiTranslationAccess.kt`): turns, intervals and rests per key, kept for
   the app run; the model chips and `ai_translation_model` preference (empty = default).

## Acceptance criteria

- [x] The 0:37 captions keep "a way." with "…misleading in a way." and lowercase
  (`CaptionFormatTest.sentenceEndAfterALongLineJoinsTheSentenceBeforeIt`; fails before the fix).
- [x] With a fake model, each row shows exactly the model's row
  (`AiSubtitleTranslatorTest.eachRowGetsExactlyTheWordsTheModelGaveIt`), cached sentences are
  not sent again, and the next request carries context.
- [x] A left-out sentence, a reply without JSON and a failed request leave the text for Google
  (`aSentenceTheModelLeftOutStaysForGoogle`, `aReplyWithoutJsonIsAFailure`, `aFailedRequestThrowsSoGoogleTakesOver`).
- [x] Existing slicing, merging and translation-window tests still pass.
- [x] Translation defaults to the fast model, a chosen model is used only while the service
  offers it, thinking is off, keys take turns, a limited key rests longer each time, every key
  resting goes to Google, and a rejected key is not asked again (`AiTranslationAccessTest`).
- [ ] On a phone with a Gemini key, the 0:37 rows read naturally and match their source rows.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including the new tests above | Laptop and PR CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | Laptop and PR CI |
| Managed-device/emulator | `managed-device-tests` in PR CI | PR CI |
| Physical-device/manual | Preview build: Settings → Translation → Translate with AI on, play the 0:37 video, compare rows; turn it off, rows come from Google again | Owner's phone |

## Risks / edge cases

- A slow free model leaves the first window to Google; later minutes come from the AI.
- Free keys have rate limits; a 429 moves to the next key, then Google.
- A model that ignores the row count still gives a whole-sentence translation, which is cut as
  before.

## Follow-up from the first phone test (2026-10-28)

Kien's AI Studio page showed 12 of 15 requests a minute, and one Japanese → Vietnamese row read
"thực <i>sự</i>". Fixes: a sentence that was sent and answered (even if the model left it out)
is never sent again, so each following row no longer asks for it; after a failed request the AI
rests 30 s (`AI_FAILURE_REST_MS`) while Google translates; a key is asked at most every 6 s;
markup tags are removed from the model's rows. The setting text now says a free Gemini key works
best and that other services may cost money or answer less reliably
(`AiSubtitleTranslatorTest.aSentenceTheModelLeftOutIsNotAskedForAgain`,
`afterAFailureTheAiRestsAndThenIsAskedAgain`, `markupTheModelAddsIsRemoved`).

## Release intent

`release:patch` (default; no owner instruction to change it).

## Implementation result

As planned.

## Validation result

New and related unit tests passed locally on 2026-10-28; full results are in the PR. Phone check
pending on the preview build.
