# Whole-sentence lines for fluent translation

## Status

Implemented. The owner asked on 2026-11-21, after comparing the Persian translation with the Android app's.

## Context / problem

The extension sent Google each YouTube caption piece on its own. Pieces, especially auto-generated ones, break
sentences in the middle ("so what I want to" / "tell you about today is"), so Google never saw a whole sentence and
the Persian read as broken fragments. The Android app hit the same problem
([#57](https://github.com/hoangkien1703/dual-sub-replay/issues/57)) and fixed it by joining pieces into sentences
before translating (`sentenceCaptionUnits` in `ui/TranslationCoordinator.kt`).

## Goals

- Every translation request carries a whole sentence, as far as the caption punctuation and timing allow, using
  Android's joining rules.

## Non-goals

- Android's default "Short paired phrases" display, which splits one sentence translation across short rows.
- Splitting a caption piece at a sentence end inside it (`splitAtSentenceEnds`). Such a piece can carry the start of
  the next sentence into its row, as it did on Android before that change.

## User-visible behavior

Before: each caption piece was its own row and its own translation. After: rows are sentences, as in Android's
Whole sentence format. The overlay, transcript, replay and word card all use these rows, so a row click replays the
whole sentence.

## Technical constraints / invariants

[Android parity](../../AGENTS.md#browser-extension): the limits are Android's constants. Joining runs in
`data/captions.js`, a data module that Node can test.

## Proposed approach / plan

Port `sentenceCaptionUnits` for the case where display rows are the source rows, as `sentenceLines(lines)` in
`data/captions.js`, and apply it in `ui/content.js` right after `parseCaptions`. A piece joins the one before it when
both of these hold:

- The previous piece does not end a sentence (`. ! ? 。 ！ ？ …`, optionally followed by a closing quote or
  bracket), and the pause between them is at most 1.2 s. When at least one piece in five ends with 。！？, the limit
  after a Japanese or Chinese piece is 3 s.
- Together they fit in 120 characters and 8 s. A short piece that starts with a lowercase letter and ends the
  sentence (32 characters or fewer) joins even past those limits.

The separator is a space, except between two Japanese or Chinese characters.

## Acceptance criteria

- [x] Android's `TranslationCoordinatorTest` cases give the same rows: punctuation and a long pause keep pieces
      apart; Japanese joins without spaces; an unfinished Japanese piece joins after a pause of up to 3 s when the
      captions use 。; without 。 the limit stays 1.2 s; a pause over 3 s still splits (unit test).
- [x] Unpunctuated pieces stop joining at 120 characters, and a short lowercase sentence tail still joins (unit
      test).
- [x] On YouTube with a sample track of fragments, the panel shows one row per sentence, each with one translation.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `cd browser-extension && node --test` | Linux, Node 22 |
| Headless Chrome | Sample track of auto-caption fragments; panel rows and translations read through DevTools | Chrome 156 |
| Live YouTube | Persian translation reads naturally on a real video | Owner's Chrome |

## Risks / edge cases

- Long rows on the overlay: up to 120 characters for unpunctuated captions, and longer for punctuated ones.
- A video's lines change, so any word saved before is still linked by its old sentence. Saved words keep their own
  copy of the sentence, so nothing breaks.

## Version

0.2.1 (patch).

## Implementation result

As planned. Follow-up in 0.2.2: requests now send the caption language as `sl` (Android's `googleLanguageCode`:
the base code, with Chinese as `zh-CN` or `zh-TW`) instead of `auto`. With Google chosen, this was the last
difference from Android's request; Android's default display is also Whole sentence.

## Validation result

- Unit tests: passed.
- Headless Chrome: passed. Five fragments became two sentence rows, each with one translation.
- Live YouTube: not run; the owner judges the Persian.
