# Spoken-word highlight and sentence ends inside a piece

## Status

Implemented. The contributor asked for "subtitles like Android" on 2026-11-21.

## Context / problem

- Android underlines the word being spoken on auto-generated captions (json3 `tOffsetMs` word timing, `json3Words`).
  The extension threw that timing away.
- Auto captions often end one sentence and start the next inside one piece ("…the price. Even at, look at"). Android
  cuts such pieces (`splitAtSentenceEnds`) before joining sentences. The extension kept them whole, so a row could
  hold the end of one sentence and the start of the next.

## Non-goals

Android's "Short paired phrases" format, which splits a sentence into short rows and slices one translation across
them (`TranslationSlicing.kt`, with translations of each row prefix). Android's default is Whole sentence, which the
extension uses. That format is on the [roadmap](../../ROADMAP.md).

## Proposed approach / plan

- `data/captions.js`: `parseCaptions` keeps `words` ([{text, time}]) on timed pieces. `splitAtSentenceEnds` uses
  Android's `sentenceBreak` pattern and takes cut times from the timed words, or shares the time by length. Joining
  keeps both pieces' words. `wordTimes` maps the browser's word tokens onto those times.
- `ui/content.js`: words carry `data-t`. Every 150 ms the last word whose time has come is underlined in the overlay
  and the active row, in Android's default highlight color (mint).

## Acceptance criteria

- [x] Android's `interiorSentenceEndsStartANewTranslationUnit` case gives the same two rows, and the second starts
      inside the first piece's time (unit test).
- [x] Japanese splits after 。 with no space. Timed words give the cut time and stay with their sentence (unit tests).
- [x] Joined lines keep word times, and a line without timing gives none (unit tests).
- [x] Headless Chrome: "I went home. Then the" + "children stopped running." shows as "I went home." / "Then the
      children stopped running.", and at 3.7 s "stopped" is underlined.

## Version

0.4.0.

## Validation result

Passed as above. Live YouTube with real auto-generated captions: not run here.
