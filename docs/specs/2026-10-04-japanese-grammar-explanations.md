# Japanese grammar explanations on Translate

## Status

Implemented. The owner asked for this in the project thread on 2026-10-04, asked for research
into how other apps do it, and chose "On-device rules" on the decision card after it. The owner
asked for this, phrase translation and language downloads to ship in one PR (#99).

## Context / problem

Tapping Translate on a Japanese word shows its meaning, but not the grammar around it. Renshuu
shows, for example, 体育館に → "に: In A; on A; at A" with "Show alternate grammar". Learners need
the same for particles and verb endings (〜てくれました) to understand subtitle lines.

Research (Renshuu, ichi.moe/Ichiran, Yomitan grammar dictionaries, Bunpro, LLM readers such as
Wakatta! and Oyomi): the reliable approach is a morphological analyzer plus a curated list of
grammar patterns. LLMs are used for free-form explanations, but need a network call, a key and
per-request cost, which conflicts with the project's on-device, no-key rule and the F-Droid build.
Grammar text from Renshuu, Bunpro and the DOJG book is copyrighted, and Tae Kim's guide is
CC BY-NC-SA, so the explanations here are written for this project.

## Goals

- When Translate opens the learning card for Japanese, show a **Grammar** section listing the
  grammar points of the selection, each with its likeliest meaning and **Show other meanings**.
- Include the point right after the selection, so tapping 体育館 explains the において after it.
- Longer patterns hide the parts inside them: 〜てくれました lists 〜てくれる and 〜ました, not て or た.
- Fully on-device, both builds, explanations in every interface language.

## Non-goals

- No LLM or network service.
- No whole-sentence structure diagrams; points are listed per selection.
- No grammar for languages other than Japanese.

## User-visible behavior

- **Before:** the card shows the word, its reading, part of speech and meaning.
- **After:** under the meaning, a **Grammar** heading lists cards like "に  In A; on A; at A
  (where something is)" and "〜ました  Polite past: did A". Points with several uses have a
  **Show other meanings** button. No section appears when nothing is recognized, for non-Japanese
  words, or while the Japanese dictionary is not ready.

## Technical constraints / invariants

- Uses the existing Kuromoji IPADIC analyzer (`data/JapaneseMorphology.kt`); no new dependency.
- `data/` stays free of learner-facing text: `GrammarMeaning` ids map to `strings_grammar.xml`
  in `ui/GrammarExplanations.kt`, with every `values-*` copy.

## Proposed approach / plan

1. `JapaneseMorphology.morphemes(text)` exposes raw morphemes.
2. `data/JapaneseGrammar.kt`: about 50 patterns as small rule functions in priority order
   (obligation, て-combinations, ことができる, て + helper verbs, ます forms, auxiliaries, verb
   endings, compound particles, nominalizer の, particles). Hits fully inside an earlier hit are
   dropped. に picks its likeliest meaning from neighbours (purpose, passive agent, time,
   destination, recipient, location).
3. `grammarForSelection` keeps points inside the selection plus the one starting right after it.
4. `ui/GrammarExplanations.kt` shows them in `WordLearningDialog`.

## Acceptance criteria

- [x] 体育館 in the owner's Renshuu sentence shows において; 見送りしてくれました shows 〜てくれる
  and 〜ました only (`JapaneseGrammarTest`).
- [x] に picks destination, purpose, passive agent, time, recipient or location from context and
  keeps the rest as alternatives (`JapaneseGrammarTest`).
- [x] Obligation, permission, prohibition, ability, conditionals, polite and casual endings,
  passive/causative and common particles are recognized (`JapaneseGrammarTest`).
- [x] Every meaning has text (`GrammarExplanationsTest`), and every language has the strings
  (`AppLanguageTest`, lint `MissingTranslation`).
- [x] The section shows the likeliest meaning and reveals the others (`GrammarExplanationsUiTest`).
- [ ] On a phone with live YouTube Japanese captions, Translate shows sensible grammar (owner).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` incl. `JapaneseGrammarTest` with the real IPADIC analyzer | Local, JDK 21 |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local and CI |
| Managed-device/emulator | `GrammarExplanationsUiTest` | CI (no KVM locally) |
| Physical-device/manual | Translate on Japanese captions in the preview APK | Owner |

## Risks / edge cases

- IPADIC sometimes mis-tags casual speech; a wrong or missing point is possible, so the likeliest
  meaning is a guess and alternatives stay one tap away.
- Context rules for に are heuristics; they can pick the wrong use in unusual sentences.

## Release intent

`release:patch`, the default; the owner gave no other intent. It ships with PR #99.

## Implementation result

As planned. Rules live in `data/JapaneseGrammar.kt`; texts in `strings_grammar.xml` for all eight
interface languages.

## Validation result

See PR #99 for local results and final-head CI.
