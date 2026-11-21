# Chrome extension MVP: dual subtitles, replay and word card

## Status

Implemented. The owner asked for the MVP, the word card, faster lookups and the spacing fix on 2026-11-21.
This record was written after the code, when the project took on the Android repository's workflow.

## Context / problem

DualSub Replay exists only for Android. The owner wants the same learning on a computer. The Android app already
works as a YouTube page with injected scripts, so a content script can do the same job.

## Goals

- The original line and its translation over the video, and a clickable transcript beside it.
- Click a line to jump back to its start.
- Click a word for its translation and meaning, then save it or send it to Anki.

## Non-goals

Grammar help, known words, study play modes, Shorts and live streams, and caption formats other than json3.

## User-visible behavior

- On a watch page with captions, the transcript appears at the top of the right column, and the overlay shows the
  active line and its translation. YouTube's own caption window is hidden while DualSub shows.
- If CC is off, DualSub presses it once. Without captions after 8 s, the panel says "No captions found for this
  video."
- Clicking a word pauses the video and opens a card. Esc or a click outside closes it. Failures show in the card or
  the panel header.

## Technical constraints / invariants

The [Captions, Network and Page text rules](../../AGENTS.md#browser-extension).

## Proposed approach / plan

`page/hook.js` copies timedtext replies. `data/captions.js` parses them. `background.js` translates, looks up
Wiktionary and talks to AnkiConnect. `ui/content.js` draws the UI.

## Acceptance criteria

- [x] An asr json3 track parses into lines whose overlapping ends are trimmed (unit test).
- [x] With a sample track: the panel lists the lines with Persian translations, and a row click seeks to its start.
- [x] On the overlay, words are spaced. `display: table` dropped the spaces between word spans; `width: fit-content`
      keeps them.
- [x] The word card shows the translation within about 50 ms of the click once the line has played.
- [x] Anki not running shows "Anki is not reachable…" in the card.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `cd browser-extension && node --test` | Linux, Node 22 |
| Headless Chrome | Sample track through `postMessage`; panel, overlay, seek and card checked through DevTools | Chrome 156 |
| Live YouTube | Real captions on a TED talk | Owner's Chrome |

## Risks / edge cases

- YouTube can change how its player fetches captions; the hook is the only thing to fix then.
- Auto-generated captions are fragments, not sentences, so their translations are weaker.
- Wiktionary can take seconds through slow proxies.

## Version

0.1.0 (first version).

## Implementation result

As planned. Google's dictionary endpoint answered 429 on the owner's network, so meanings come from Wiktionary.
Lookups were sped up: the translation shows before the dictionary does, each line's words are pre-translated, a hover
starts the lookup, and the connection opens when captions load.

## Validation result

- Unit tests: passed.
- Headless Chrome: passed with a sample track. The real caption request was made with `fmt=json3` and a `pot` token,
  but YouTube answered headless Chrome with an empty body.
- Live YouTube: the owner reported on 2026-11-21 that everything works.
- Real Anki note creation: not run, because Anki was not available here.
