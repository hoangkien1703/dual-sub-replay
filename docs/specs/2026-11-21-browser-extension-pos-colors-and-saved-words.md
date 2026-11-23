# Part-of-speech colors and a Saved words page

## Status

Implemented. The contributor asked on 2026-11-21: words colored as in the Android app, and a proper place for saved
words. The same request moved the project to the Android repository's layout and workflow.

## Context / problem

- Every subtitle word had the same color. Android colors the original line by part of speech
  (`PartOfSpeech` in `data/LanguageAwareTokenizer.kt`).
- Saved words were only a short list in the toolbar popup, with no way to review them. Android has Saved words
  with Practice (`ui/SavedWordsScreen.kt`, `reviewInterval` in `data/SavedWord.kt`).

## Goals

- Original-line words on the overlay and in the transcript take Android's part-of-speech colors, for English and
  Japanese.
- The word card shows the word's part of speech.
- A Saved words page with search, delete, a link back to the line on YouTube, and Practice with Android's intervals.

## Non-goals

- Android's OpenNLP, Kuromoji, jieba, Spanish and German analyzers. Other languages stay neutral (`other`).
- Export and import, decks, notes, known words.

## User-visible behavior

- Words are colored on the overlay. In the transcript they are darkened on YouTube's light theme and bright on its
  dark theme.
- The word card's title has the part-of-speech color and tag. After Save, the card links to **Open saved words**.
- The toolbar popup has **Saved words & practice (N)**, which opens the page in a tab.
- Saved words: newest first, searchable. Each word shows its translation and sentence with the word marked, the
  sentence translation, a ▶ link to the video at that line, its next review, and × to delete (after a confirm).
- Practice: due words, oldest first. The front is the word and sentence. **Show answer** (or Space) reveals the
  translation, meanings and sentence translation. Again, Hard, Good and Easy (or keys 1–4) show their next intervals.
  When nothing is due, it says when the next word is due. Words saved in a YouTube tab appear at once.

## Technical constraints / invariants

The [Data modules, Saved words and Android parity rules](../../AGENTS.md#browser-extension). Words saved by 0.1.0
(no `id`, `pos`, `dueAt` or `intervalMs`) must still load.

## Proposed approach / plan

1. Split `captions.js` into `data/captions.js` and `data/words.js` (tokens, part of speech, meanings). Add
   `data/vocabulary.js` (storage and `reviewInterval`). Move `hook.js` to `page/` and the UI to `ui/`.
2. Port `PartOfSpeech` colors and the English and Japanese fallback rules. Color words with a `--pos` CSS variable.
3. `ui/saved-words.html|css|js` with the list and Practice. The popup links to it, and background `openSavedWords`
   opens it from the card.
4. Add AGENTS.md, ROADMAP.md, docs/project and docs/specs.

## Acceptance criteria

- [x] "The lazy dog quickly ran to them and barked" gives particle, adjective, noun, adverb, noun, preposition, pronoun,
      conjunction and verb, as Android's fallback rules do (unit test).
- [x] Japanese は, 食べる and 楽しい give particle, verb and adjective. Spanish gives other (unit test).
- [x] Review intervals match Android for new and 10-day words, including the 10-year cap (unit test).
- [x] On YouTube with a sample track, the overlay words have their part-of-speech colors, and the card shows "Noun"
      for procrastination.
- [x] Save, then Open saved words, then Practice: the word shows, Space reveals it, 3 (Good) schedules it 3 days out,
      and the list shows "Next review in 3 d".
- [x] A word card opened above a word never extends past the top of the window.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `cd browser-extension && node --test`: captions, words and vocabulary pass | Linux, Node 22 |
| Headless Chrome | Sample track, colors read through `getComputedStyle`, card, save, saved words page and practice keys | Chrome 156 |
| Live YouTube | Colors and saving on real captions; dark and light YouTube themes | Contributor's Chrome |

## Risks / edge cases

- The English rules are heuristics: "ran" is a noun, as on Android's fallback, and Android's OpenNLP is more accurate.
- Data scripts and page scripts share one global scope. A repeated `const` name stops a page from loading, which
  happened once during this change.

## Version

0.2.0. A new page and a storage format change.

## Implementation result

As planned. Found and fixed during validation: the saved words page redeclared `DAY_MS`, and the word card could
run past the top of the window (`box-sizing: border-box` and a max height for the room available).

## Validation result

- Unit tests: passed (3 files).
- Headless Chrome: passed for every criterion above.
- Live YouTube and YouTube's dark theme: not run. The contributor checks them in their own Chrome.
