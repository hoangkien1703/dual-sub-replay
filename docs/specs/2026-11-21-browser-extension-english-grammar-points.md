# English grammar points and tagged parts of speech

## Status

Implemented. The owner asked on 2026-11-21 to port the Android app's English grammar help, after the
[first part](2026-11-21-browser-extension-english-word-help.md).

## Context / problem

Android tags each English line with OpenNLP's UD English EWT model, finds dictionary forms with WordNet, splits
contractions, and matches 165 grammar patterns plus 4 rules (172 meanings with CEFR levels) against the result
([Android spec](https://github.com/hoangkien1703/dual-sub-replay/blob/main/docs/specs/2026-10-18-english-learner-help.md)).
The extension had heuristic word lists only: "light" was the same color in "this light" and "light the fire", and the
card showed no grammar.

## Goals

- English lines are tagged with the same model and the same features as Android's `OpenNlpPosTagger`, so the colors
  match Android's.
- The card lists the grammar points that belong to the selected word (Android's `grammarForSelection`), each with its
  form, CEFR level and explanation.
- The level and dictionary form use the tag and WordNet, as Android's `EnglishLexicon`.

## Non-goals

Translating the explanations: Android has them in 9 interface languages, not Persian, so the English text is shown.
"Explain the grammar with AI" is also out of scope.

## Data

| File | Source | Licence |
| --- | --- | --- |
| `en-pos.model` | `pos.model` in `opennlp-en-ud-ewt-pos-1.3-2.5.4.bin` of `org.apache.opennlp:opennlp-models-pos-en:1.3.0` (Maven Central, SHA-1 checked) | Apache-2.0 |
| `en-words.txt`, `en-exceptions.txt` | WordNet 3.1 `index.*` and `*.exc` in `net.sf.extjwnl:extjwnl-data-wn31:1.2` | WordNet License |
| `data/english-grammar.js`, `test/english-grammar-examples.json` | Generated from the Android source by `tools/english/build_extension_english_grammar.py` | The project's own (MIT source) |

`tools/english/build_extension_english_data.py` extracts the data files as Android's `build.gradle.kts` does.

## Proposed approach / plan

1. `data/english-tagger.js`: the GIS model reader and tagger (features, token class and greedy best tag, which equals
   OpenNLP's beam with cached features, as Android notes); `EnglishLexicon` (morphy with WordNet); and the
   morphemes (contraction split, verb fixes after helpers, lemmas, forms BASE/PRES/S3/PAST/PP/ING/MODAL/COMP/SUP/LY/PL).
2. `data/english-grammar-match.js`: Android's pattern syntax (`GrammarPattern`), `matchGrammar`,
   `grammarForSelection` and the 4 English rules.
3. The service worker loads the data on first use and answers `englishLine` with each word's tag, part of speech,
   dictionary form and level, plus the line's grammar points. The content script recolors the line's words and lists
   the points in the card.

## Acceptance criteria

- [x] Every one of Android's 172 `EnglishGrammarCatalogueTest` examples finds its own point (unit test with the real
      model).
- [x] "light" is a noun in "Turn on the light." and a verb in "They light the fire." (unit test).
- [x] Dictionary forms through WordNet: leaves → leaf, went → go, happier → happy, singing → sing, hoping → hope
      (unit test).
- [x] Headless Chrome: the card for a word in an English line lists its grammar points with their levels.

## Risks / edge cases

The model (1.6 MB) and WordNet (1 MB) load in the service worker on the first English word and again after Chrome
stops it when idle (about 30 s). The first card after that is slower.

## Version

0.5.0.

## Implementation result

As planned. `data/english.js` now holds Android's lexicon and morphology, so the [first part's](2026-11-21-browser-extension-english-word-help.md)
spelling-only dictionary forms were replaced by WordNet's. The service worker tags all lines of an English track once
they load (`englishLines`), and the panel and overlay are drawn again in the tagger's colors. Saved words keep the
card's grammar points, and Practice shows them with the answer.

## Validation result

- Unit tests: passed. All 172 Android examples find their point with the real model (38 tags, 33,518 predicates), in
  about 0.2 s for all of them.
- Headless Chrome: passed. In "It's going to rain, so turn on the light." / "They light the fire when it gets dark.",
  "light" is blue (noun) and then green (verb). The card for "going" shows "/ɡoʊɪn/ → go A1" and "be going to + verb A2"
  with its explanation.
- Live YouTube: not run.
