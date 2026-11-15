# Offline German POS tagging for the Original Subtitle colors

Feature spec. Owner request 2026-10-29. Status: implementing.

## Problem

Word Learning colors each Original word by its part of speech (POS). English uses Apache
OpenNLP's model (`EnglishMorphology`). German falls through to `tokenizeSpacedLanguage()`, which
calls `classifyEnglishWord()`, so German nouns, verbs, adjectives and articles are colored
wrongly (for example "Haus" and "gehen" are not recognised). This is a new feature, not a fix
for Custom subtitle colors (the earlier investigation is
[2026-10-29-original-pos-colors-not-restored.md](2026-10-29-original-pos-colors-not-restored.md)).

## Requirements

1. When the Original language code is German (`de`, `de-DE`, `de-AT`, `de-CH`, `de_*`), tag the
   line in context with a real German model and map UD tags onto the existing `PartOfSpeech`
   enum and palette. Never guess German from Latin script when the language is unknown.
2. Offline: the model ships in the APK as generated learner data (like `en-pos.zip`). No
   download, server, key, permission or new runtime dependency.
3. Text, whitespace, punctuation, capitalisation, offsets and selection boundaries are unchanged.
4. Loads once on a background thread (`BackgroundLoaded`); `tokenizerRevision()` changes when it
   is ready so visible captions recompose; a load failure never crashes playback.
5. While the model loads, or if it failed, German words keep their selectable tokens but get no
   grammatical color (`OTHER`), because the English word lists are not German analysis.
6. English, Japanese and Chinese behaviour, selection, Google Translate, slots, Practice,
   Vocabulary, Anki and settings are untouched. Works with Word Learning Mode, Colored lines
   and Active sentence only.

## Model and licence

- `org.apache.opennlp:opennlp-models-pos-de:1.3.0`, entry `opennlp-de-ud-gsd-pos-1.3-2.5.4.bin`
  (1,269,178 bytes; jar 1.28 MB; sha256 in its `model.properties`
  `073fefbf…3742f`). Apache-2.0 (model package), trained on the Universal Dependencies German
  GSD treebank, CC BY-SA 4.0 (UD's README: Google dropped the NC restriction). No
  non-commercial asset. Credit goes into `THIRD_PARTY_NOTICES.md`.
- Format check: the package holds `pos.model` (GIS), `generator.featuregen` and
  `manifest.properties` (beam 3, `POSTaggerME`). Its `generator.featuregen` is identical, after
  removing comments and whitespace, to the English model's, so the existing OpenNLP reader and
  feature code (`EnglishPosTagger`) applies unchanged. That is a format fact, not proof of
  accuracy: tags are validated by tests with known German sentences.

## Design

- Rename `EnglishPosTagger` to `OpenNlpPosTagger` (no behaviour change; it is now shared).
- `data/GermanMorphology.kt`: `BackgroundLoaded("german-analyzer") { OpenNlpPosTagger.load(de-pos.zip) }`,
  `revision`, `warmUp`, `awaitReady`, `analyze(text)`.
- Tokens: words with hyphen compounds kept whole (`E-Mail`), `'s`-style clitics and each
  punctuation mark as their own token (the model expects punctuation), offsets from the regex.
- UD to app: NOUN/PROPN to NOUN, VERB/AUX to VERB, ADJ to ADJECTIVE, ADV to ADVERB, PRON to
  PRONOUN, ADP to PREPOSITION, CCONJ/SCONJ to CONJUNCTION, DET/PART to PARTICLE, else OTHER.
- `LanguageAwareTokenizer`: `isGermanCode` dispatch, analyzer revision includes German.
- `build.gradle.kts`: `learnerData("...pos-de:1.3.0")` and extraction `de-pos.zip`.
- No dictionary forms, gender, case or conjugation (out of scope).

## Acceptance criteria

- Real-model tests: nouns, verbs, adjectives, pronouns, articles, conjunctions, prepositions,
  an ambiguous word in two contexts, umlauts, ß, capitals, hyphenation, `de-AT`, offsets.
- Rendered Compose test shows differentiated German colors; evidence screenshot saved.
- English tests unchanged and passing; unknown/`auto` language still English-heuristic as before.
- Load time and APK size increase measured and recorded.

## Plan

1. Spec (this file). 2. Gradle packaging. 3. Rename and `GermanMorphology`. 4. Dispatch and
revision. 5. Tests. 6. Notices. 7. Run gates, device tests, review. 8. Record results here.

## Release intent

Patch (new data asset and analyzer; no release label change requested).

## Results

_To be filled in after implementation._
