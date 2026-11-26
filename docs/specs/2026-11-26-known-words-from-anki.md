# Known words from Anki

## Status

Implemented (owner pick 2026-11-26: build the competitor research's "known words from your existing Anki cards").

## Context / problem

Known words (Settings → Word learning → Known words) start empty, so a learner who already knows thousands of words
from Anki sees every word as new until Automatic known words catches up. asbplayer and Fushi colour words from the
learner's Anki collection; jidoujisho users moving to DualSub Replay arrive with big AnkiDroid decks.

## Goals

- One tap reads the words of one AnkiDroid deck and adds them to the known words of one language.

## Non-goals

- No continuous sync, nothing written to Anki, no AnkiConnect (desktop) import, no per-card status colours.

## User-visible behavior

- With Known words on (Automatic or Manual) and AnkiDroid installed, Settings → Word learning shows **Add words from
  Anki** under the known-words switches.
- The dialog asks AnkiDroid's permission when it is missing, lists AnkiDroid's decks, and offers **Cards to count**:
  Well known (21+ days, default) or Every studied card, and the language when more than one fits (the language picked
  at the start, the preferred caption language, languages that already have known words).
- **Add words** adds each card's first-field word, in its dictionary form, and says how many were added from how many
  cards, or why nothing was (no permission, AnkiDroid not answering, no single words).

## Technical constraints / invariants

- Study tools and Anki rule: AnkiDroid only through its content provider (`notes` with Anki search syntax, `decks`),
  never its library; nothing is written to AnkiDroid.
- Known words stay in `KnownWordsRepository` files; added words reset their seen counts like a manual mark.

## Proposed approach / plan

1. `data/AnkiKnownWords.kt`: `ankiDeckSearch` (`"deck:<name>" prop:ivl>=21` or `-is:new`, quotes and wildcards
   escaped), `ankiFieldPieces` (HTML, `[sound:]`, ruby and bracket furigana, entities removed; split on `, ; / |`),
   `ankiFieldKeys` (only pieces that are one word for the tokenizer, keyed like subtitle words), and `AnkiKnownWords`
   reading the sort field `sfld` of the matching notes.
2. `ui/AnkiKnownWordsDialog.kt`: the button and dialog (X at the top right, no Cancel).

## Acceptance criteria

- [x] Search, field cleaning and single-word keys behave as described (`AnkiKnownWordsTest`).
- [ ] Phone: with AnkiDroid and a studied deck, Add words adds words, and they show as known in the next video.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `AnkiKnownWordsTest` | cloud container |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` | cloud container, PR CI |
| Physical-device/manual | scenario in the last criterion | owner's phone (no AnkiDroid in CI) |

## Risks / edge cases

- Decks whose first field is a sentence or a picture add nothing; the dialog says so.
- On some phones AnkiDroid's provider answers only while AnkiDroid runs; the message says to open it and try again.
- Japanese words are matched by their dictionary form, so the deck's word must be the dictionary form (usual in Anki).

## Release intent

`release:patch` (changes the APK; owner rule: patch releases only).

## Implementation result

As planned. Strings in all 9 interface languages.

## Validation result

`AnkiKnownWordsTest` passed in the cloud container on 2026-11-26. Phone check not run yet (owner).
