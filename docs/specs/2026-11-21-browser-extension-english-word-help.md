# English word help: dictionary form, CEFR level and IPA

## Status

Implemented (first part). Its spelling-only dictionary forms were replaced by WordNet's in
[English grammar points](2026-11-21-browser-extension-english-grammar-points.md). The contributor asked for Android's English grammar and word help on 2026-11-21.

## Context / problem

Android's English card shows the dictionary form (went → go), the CEFR-J word level (A1–B2), IPA, and grammar points
with their CEFR levels ([Android spec](https://github.com/hoangkien1703/dual-sub-replay/blob/main/docs/specs/2026-10-18-english-learner-help.md)).
Its grammar points and accurate part-of-speech colors rest on an OpenNLP maxent tagger model and WordNet lists that
the Android build downloads from Maven Central, plus about 1,500 lines of morphology and pattern code.

## Goals (this part)

- The card shows IPA, the dictionary form when it differs, and the CEFR-J level for English words. Saved words keep
  all three, and the Saved words page shows them.
- The data files are Android's own: `en-levels.txt` (CEFR-J 1.5) and `en-ipa.txt`, copied unchanged with their
  credits in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

## Non-goals (next part)

The OpenNLP tagger, WordNet's morphy, contractions split into parts, and the 172 grammar points and their texts.

## Proposed approach / plan

`data/english.js` ports Android's irregular verb, plural and comparative tables and `spelledVerbLemma`. With no
WordNet, a form made by spelling rules counts only when the word data knows it. A form in the CEFR-J list wins over
one only the IPA list has, since that list also holds names and fragments ("di", "nic"). For a verb, the dictionary
form is looked for before taking a listed -ing/-ed word as is (running → run, but news and during stay). The service
worker loads the files once per life and answers `english` messages.

## Acceptance criteria

- [x] went → go, children → child, stopped → stop, living → live, agreed → agree, invited → invite, died → die,
      years → year, studies → study, bigger → big, happier → happy, nicer → nice, taller → tall, better → good;
      news, during, focus and don't stay as they are (unit test with the real data files).
- [x] Levels depend on part of speech (light: A1 noun, B1 verb), the lowest level is used when that part is not
      listed, and contractions have no level (unit test).
- [x] Headless Chrome: the card for "went" shows "/wɛnt/ → go A1", and the saved word shows them on the Saved words
      page.

## Version

0.4.0.

## Validation result

Passed as above.
