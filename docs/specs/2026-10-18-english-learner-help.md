# English learner help (v1.7.0)

## Status

Approved for implementation. On 2026-10-18 the owner asked in the project thread for English
learners to get the same help Japanese and Chinese learners have (grammar, parts of speech and so
on), based on research into how others have done it and the best materials, reusing PR #123's
code if useful, in a separate PR that works well. No version was named, so the release is the
next minor version after v1.6.1. That does not authorize merging or publishing.

## Context / problem

Japanese and Chinese lines are split into learner words, colored by part of speech, show a
reading in the word card and list grammar points with levels (JLPT, HSK 3.0) and an "Explain the
grammar with AI" button ([Chinese spec](2026-10-18-chinese-learner-help.md)). English uses a word
list plus suffix guesses: most words fall back to "Noun", "light" in "this light" and in "light
the fire" get the same color, the card shows no dictionary form, and there are no grammar cards.

[PR #123](https://github.com/hoangkien1703/dual-sub-replay/pull/123) has an English part (an
OpenNLP tagger run without the library, a rule-based lemmatizer, a CEFR grammar catalogue with
167 points and its text in 8 languages) mixed with caption-loading changes the owner reported as
still buggy. The owner asked for a separate PR from `main`; #123's English code may be reused.

### Research (2026-10-18)

- **How English tools do it.** Reading tools for English (Language Reactor, LingQ, Readlang)
  tag words with a statistical tagger, show the dictionary form, and mark how common or advanced
  a word is; grammar explanations are left to teachers, grammar references or an AI. Learner
  dictionaries and teachers single out phrasal verbs and fixed helper-verb patterns (going to,
  have to, used to) as the hardest parts to notice in speech.
- **Parts of speech.** Offline options: Apache OpenNLP's maxent model trained on the Universal
  Dependencies English Web Treebank (Apache-2.0, 1.2 MB), UDPipe models (CC BY-NC-SA, not usable
  in a released app), spaCy and transformer taggers (tens to hundreds of MB, need a runtime).
  OpenNLP's model is small, permissive and accurate enough (#123 matched OpenNLP 2.5.4's own tags
  on 26,671 Tatoeba tokens), so it is kept.
- **Dictionary forms.** OpenNLP's lemmatizer model was compared with #123's rules on 3,000
  random Tatoeba sentences: it keeps many irregular forms as they are (drank, people, children),
  and the rules miss plurals (years) and some verbs (invited → invit, died → di). The standard
  approach is WordNet's *morphy*: exception lists plus suffix rules, keeping only results that are
  real words in WordNet (as NLTK's lemmatizer does). WordNet 3.1 is under the permissive WordNet
  licence and on Maven Central (`net.sf.extjwnl:extjwnl-data-wn31`), so its word lists and
  exception lists are used, after the app's own irregular-verb table.
- **Levels.** CEFR (A1–C2) is the scale English learners know. The CEFR-J project (Tono
  Laboratory, Tokyo University of Foreign Studies) publishes a Grammar Profile and a Vocabulary
  Profile (7,799 words, A1–B2) that "can be used for research and commercial purposes with no
  charge, provided that you cite the dataset" ([Open Language Profiles](https://github.com/openlanguageprofiles/olp-en-cefrj)).
  The Grammar Profile also lists the levels the British Council/EAQUALS Core Inventory,
  Cambridge's English Grammar Profile and Pearson's GSE give each item. Not used: the English
  Vocabulary Profile and English Grammar Profile directly (no reuse licence found), the Octanove
  C1/C2 list (CC BY-SA). Explanations stay written for this project.

Data used:

| Data | Source | Licence | Size in APK assets (before compression) |
| --- | --- | --- | --- |
| English part-of-speech model (UD English EWT, maxent) | `org.apache.opennlp:opennlp-models-pos-en:1.3.0` (Maven Central, build time) | Apache-2.0 | ~1.2 MB |
| English word lists and exception lists | `net.sf.extjwnl:extjwnl-data-wn31:1.2` (WordNet 3.1, Maven Central, build time) | WordNet licence | ~0.9 MB |
| Word levels A1–B2 | CEFR-J Vocabulary Profile 1.5, reduced to word, part of speech and level, committed in `app/src/main/learner-data/` | Free for commercial use with citation | ~0.1 MB |

## Goals

- English lines (caption language `en*`) are tagged in context, so each word is colored by its
  part of speech in this sentence and tappable.
- The word card shows the dictionary form when it differs (went → go, years → year, bigger →
  big) and the word's CEFR-J level (A1–B2) when the Vocabulary Profile lists it.
- Grammar cards for English in the word card with CEFR level badges (A1 … B2), from #123's
  catalogue and rules, re-levelled from the CEFR-J Grammar Profile, and the existing "Explain the
  grammar with AI" button.
- English words keep being spoken by the phone's English voice (unchanged).
- Release as **v1.7.0**.

## Non-goals

- No definitions (the meaning keeps using the translator), no IPA, no levels above B2 for words.
- No caption-loading changes from #123.
- Japanese and Chinese behavior is unchanged.

## User-visible behavior

- **Before:** "You're going to love this light" colors most words as nouns, a tap on "went"
  shows only "Verb", and there are no grammar cards.
  **After:** "love" is a verb and "light" a noun; tapping "going" shows "be going to + verb · A2 ·
  a plan or prediction" and the AI button; tapping "went" shows "Verb", "Dictionary form: go" and
  an "A1" word badge.
- Grammar cards and the AI button appear for English only when the caption (or translation)
  language is English.

## Technical constraints / invariants

- User-visible text lives in `strings_grammar_en.xml` and the existing practice strings, in every
  `values-*` folder (`AppLanguageTest`, lint `MissingTranslation`).
- Unit tests stay plain JUnit4 and read the same data files from the classpath.
- Data loads on a background thread on first use; `tokenizerRevision()` changes when it is ready.
  No network access is added.

## Proposed approach / plan

1. From #123: `EnglishPosTagger`, `EnglishMorphology`, `EnglishGrammar`, `EnglishGrammarCatalogue`,
   `CefrLevel`, the `MID` pattern macro, the English strings in 8 languages and their tests.
2. Gradle: the WordNet artifact in `learnerData`; the task writes `en-words.txt` (single words
   with their WordNet parts of speech) and `en-exceptions.txt`, and copies the committed
   `en-levels.txt`.
3. `EnglishLexicon`: morphy-style dictionary forms after the irregular table; comparative and
   plural forms follow from the lemma; word levels by dictionary form and part of speech.
4. Word card: dictionary form and word level for English words.
5. Re-level the catalogue and rules from the CEFR-J Grammar Profile (rule below).
6. Measure coverage on 3,000 Tatoeba sentences; notices, licence screen, README, tech stack,
   AGENTS.md.

**Level rule.** A point listed in the Grammar Profile takes the median of the levels the profile
gives its item: CEFR-J's own level, its frequency-and-dispersion level, the Core Inventory, the
English Grammar Profile and GSE (sub-levels dropped: A1.3 → A1; the lowest level where a column
lists several; the easier level on a tie), so no single source decides alone. A point covering
several items takes the lowest. A point the profile does not list takes the Vocabulary Profile
level of its key word (prepositions, conjunctions, adverbs). Points with no source in either keep
#123's level: the word-ending points (-ness, -ment, -tion, -ful, -less, -able, -ly), the
possessive 's, have got, be used to, in case, look forward to, as long as, not only … but also and
due to.

## Acceptance criteria

- [x] Every English catalogue point and rule is found in its own example sentence with its own
  meaning (`EnglishGrammarCatalogueTest`), and spot-checked levels follow the rule above.
- [x] Tags equal OpenNLP 2.5.4's on named sentences, contractions split into their words, and
  dictionary forms are right for went, years, invited, died, children, bigger, glasses
  (`EnglishMorphologyTest`).
- [x] The word card shows "Dictionary form: go" and the A1 badge for "went"
  (`WordLearningDialogTest` or a Compose test), and the "A2" grammar badge (`GrammarExplanationsUiTest`).
- [x] Japanese and Chinese grammar and tokenizer tests still pass unchanged.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
  pass locally and both CI jobs pass on the final head.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including `LearnerDataDeviceTest` and the word-card tests | CI |
| Physical-device/manual | Open an English video, tap words, check colors, dictionary forms, levels, grammar cards and the AI button | Owner's phone, preview APK |
| Live YouTube | Not testable from the cloud (YouTube blocks cloud addresses) | Owner's preview test |
| Coverage/performance | 3,000 random Tatoeba sentences: share of lines whose grammar words all belong to a point, lemma agreement, time and heap, recorded below | Local JVM (scratch, not committed) |

## Risks / edge cases

- The tagger makes mistakes on short, informal subtitle lines; grammar rules can fire on them.
- WordNet lists some plurals as words of their own (glasses, means); a plural the Vocabulary
  Profile lists as a headword stays as it is, otherwise the singular is shown.
- CEFR-J levels come from Japanese textbooks and differ in places from other syllabi (the
  present passive is A1); the badge says CEFR and the spec records the source.
- The APK grows by about 2.2 MB of assets before compression.

## Release intent

`release:minor` label: the owner named no version, the coordinator's brief says to default to the
next minor version, and the latest release is v1.6.1 with no newer reservation, so the expected
version is **v1.7.0**.

## Implementation result

- Ported from #123: `EnglishPosTagger`, `EnglishMorphology`, `EnglishGrammar`,
  `EnglishGrammarCatalogue`, the `MID` pattern macro, `strings_grammar_en.xml` in 8 languages and
  their tests. None of #123's caption-loading or Chinese changes came along.
- `CefrLevel` (A1–C2) sits next to `JlptLevel` and `HskLevel` in `GrammarMatcher.kt`;
  `LevelBadge` shows it as "CEFR level A2" for screen readers.
- `EnglishLexicon` holds WordNet's 80,000 single words (one string with offsets, about 2 MB of
  heap), its exception lists and the 7,752 CEFR-J levels. Modal verbs keep their own form (WordNet
  maps might → may). A plural the CEFR-J list has as a noun of its own (glasses, news, clothes)
  stays as it is; people stays people.
- Word card: a "Dictionary form: go" line when the dictionary form differs, and a CEFR word badge
  next to the part of speech. Both read the English analyzer only for `en*` words; possessives and
  contractions get no word level.
- Catalogue: 165 points plus 7 rule-based meanings, all A1–B2 after re-levelling (71 A1, 36 A2,
  34 B1, 24 B2 in the catalogue; #123 had a few C1 points, which the profile puts at B2 or lower).
  68 levels changed. New points: than, another, such (a), except (for), noun + -ing ("a dog
  running"). Present continuous, past continuous and present perfect also match question order
  ("Are you going…?", "Have you seen…?"); "to + verb" allows an adverb between. Negative inversion
  comes first in the list so it beats the question forms.
- Deviation: the level rule changed from "CEFR-J level first" to the median of all the profile's
  columns described above, so a point no longer rests on one syllabus where the others disagree.

## Validation result

Local (Linux, JDK 21), on the final code:

- `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
  see the PR's checks for the final head; both CI jobs must pass before merge.
- Dictionary forms against OpenNLP's lemmatizer on 3,000 random Tatoeba sentences (seed
  20261018, 20,199 tokens): disagreements fell from 902 with #123's rules to 245, leaving aside
  pronoun and determiner conventions. Most of the rest are the reference's own misses (drank,
  sang, children kept as they are).
- Grammar coverage on the same 3,000 lines: lines whose grammar words all belong to a point
  went from 89.1% to 92.2%; grammar words covered from 96.4% to 97.5%; 3.2 points per line.
- Performance on the JVM: the English analyzer loads in about 0.1–0.2 s and holds about 9 MB;
  3,000 lines are analyzed and matched in about 0.9 s.
- Not verified here: the managed-device tests (CI), a physical phone and live YouTube (the
  owner's preview test).
