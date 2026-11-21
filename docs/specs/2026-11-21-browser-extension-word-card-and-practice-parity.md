# Word card, Practice and AI cache closer to Android

## Status

Implemented. The owner asked on 2026-11-21 to do the remaining work toward the Android app, and picked this group
first.

## Context / problem

Compared with Android's word card and Practice: there was no Pronounce, no Copy and no phrase selection; Practice had
no notes, no video filter, and no export or import, so words could not move between the phone and the browser; and AI
translations were asked again on every page load, which spends free keys' daily limits.

## Goals

- **Word card:** 🔊 Pronounce with Google's voice (Android's `GoogleWebSpeech`), falling back to the browser's voice;
  Copy; and phrase selection, where a second word clicked in the same line selects the phrase between the two.
- **Practice:** Notes on each word (searchable, shown on the answer side), a video filter as Android's "Video: All
  videos", Export backup (Android's JSON v1), Export for Anki (Android's TSV columns), and Import backup (Android's
  JSON, keep-existing).
- **AI cache:** each AI-translated sentence is kept, keyed by prompt version, service, model and languages.

## Non-goals

TSV import, decks, editing a word's fields, Android's preview dialog before import, and pruning the AI cache.

## Technical constraints / invariants

- Transfer follows Android's `PracticeTransfer.kt` and `validateTransferWord`: 8 MiB and 20,000 records at most,
  invalid records counted and skipped, and ids kept, so a round trip adds no copies.
- [Saved words rule](../../AGENTS.md#browser-extension): new fields (`end`, `notes`, `base`, `ipa`, `level`) are
  optional, so older entries still load.

## Acceptance criteria

- [x] Export gives Android's JSON fields exactly. A round trip keeps every field the extension uses. An Android
      record with `personalNotes` imports. Invalid language, video id or empty meaning are skipped and counted. A
      repeated import adds nothing (unit tests).
- [x] TSV has Android's header lines and 15 columns, quotes every field and doubles quotes (unit test).
- [x] Headless Chrome: importing an Android backup adds the word and its video to the filter, the filter shows only
      that video's words, and a note is saved.
- [x] Headless Chrome: Google's voice returns MP3 audio that plays on the YouTube page. A second word in the same line
      opens "children stopped".
- [x] Headless Chrome: the same AI request twice sends one request, and a request with one new sentence sends only
      that sentence.

## Version

0.4.0 (with the other two parts of this round).

## Validation result

All of the above passed. Not run: real Anki import of the TSV, and importing a backup exported by the owner's phone.
