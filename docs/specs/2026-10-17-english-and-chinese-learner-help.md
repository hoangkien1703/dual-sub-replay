# English and Chinese learner help (v1.6.0)

## Status

Approved for implementation. On 2026-10-17 the owner asked in the project thread for English
and Chinese learners to get the same help Japanese learners have (grammar, parts of speech and
so on), based on research into how others do it and which materials are best, and for the
release to be v1.6.0. After the research the owner chose **Both, on-device** on the decision
card. Everything ships in one PR. That does not authorize merging or publishing.

Implemented on branch `claude/project-thread-ncnye8` (2026-10-17); see
[Implementation notes](#implementation-notes-and-deviations) and [Results](#results).

## Context / problem

Japanese lines are split into learner words by Kuromoji, colored by part of speech, show a kana
reading, and list grammar points with JLPT levels in the word card
([grammar up to N1](2026-10-07-grammar-to-n1-and-auto-caption-language.md),
[more grammar](2026-10-17-more-grammar-and-stop-ai-reply.md)). Other languages get much less:

- **Chinese** has no analyzer. A line without kana goes through the Japanese script heuristic,
  so a run of hanzi such as 我在银行工作了三年 becomes one tap target, colored as one noun, with
  no pinyin and no grammar.
- **English** uses a word list plus suffix guesses: most words fall back to "Noun", "light" in
  "this light" and "light" in "light the fire" get the same color, and there are no grammar cards.

Research (2026-10-17):

- Chinese reading tools (Pleco, the Zhongwen popup, Du Chinese) split a line into dictionary
  words and show pinyin per word; grammar is a link to a grammar site, not detected. Language
  Reactor's Chinese splitting draws user complaints for grouping characters that are not words.
  The common open data is jieba's word list (word, frequency, part of speech), CC-CEDICT (pinyin
  and English glosses, CC BY-SA) and HanLP's tables.
- English tools tag parts of speech with a trained model (spaCy, UDPipe, OpenNLP) and leave
  grammar explanations to teachers or an LLM.
- Levels: HSK (Chinese) and CEFR A1–C2 (English). The CEFR-J Grammar Profile (Tono Laboratory,
  TUFS) may be used for research and commercial purposes with citation, so it guides the CEFR
  levels; the HSK standard's grammar list guides the HSK levels. Explanations are written for this
  project. Not copied: Chinese Grammar Wiki (AllSet Learning), Cambridge's English Grammar Profile
  and the British Council/EAQUALS Core Inventory (no reuse licence found), CC-CEDICT glosses
  (share-alike).

Data used, all permissive and fetched from Maven Central at build time:

| Data | Source artifact | Licence | Size in APK |
| --- | --- | --- | --- |
| English part-of-speech model (UD English EWT, maxent) | `org.apache.opennlp:opennlp-models-pos-en:1.3.0` | Apache-2.0 | ~1.1 MB |
| Chinese word list with frequencies and tags (`dict.txt`) | `com.huaban:jieba-analysis:1.0.2` (jieba's dictionary, MIT) | Apache-2.0 / MIT | ~2 MB compressed |
| Chinese word pinyin and traditional→simplified tables | `com.hankcs:hanlp:portable-1.8.6` (`pinyin.txt`, `t2s.txt`) | Apache-2.0 | ~0.3 MB compressed |

Only the data files are packaged; no OpenNLP, jieba or HanLP code is added. The app reads the
OpenNLP model itself, because OpenNLP's loader asks Android's XML parser for a feature it rejects.

## Goals

- **Chinese:** lines split into words with a dictionary (jieba's method: the most probable path
  through the word list), each word colored by part of speech and tappable, with tone-marked
  pinyin in the word card (yínháng, not yínxíng). Traditional lines are read through the
  simplified table so they split the same way and keep their own characters.
- **English:** each word tagged in context by the OpenNLP model (the same weights and features
  as OpenNLP 2.5.4), so colors follow the sentence, plus base forms (went → go).
- **Grammar cards** for English (CEFR level badge, e.g. B1) and Chinese (HSK level badge, e.g.
  HSK 2) in the word card, from pattern catalogues and a few context rules, like Japanese.
  Explanations are written for this project in all 8 interface languages.
- **Explain the grammar with AI** under English and Chinese cards too, with the found points.
- Measure coverage on 3,000 random Tatoeba sentences per language (CC BY 2.0 FR), as for Japanese.
- Release as **v1.6.0** (`Release-Version: 1.6.0` in the PR body).

## Non-goals

- No dictionary definitions from CC-CEDICT or Wiktionary; the meaning field keeps using the
  translator.
- No pinyin above the subtitle line (pinyin shows in the word card and phrase readings).
- No grammar cards for other languages (Korean, Spanish, …); they keep today's behavior.
- No English pronunciation (IPA) readings.
- Japanese behavior is unchanged.

## User-visible behavior

- **Chinese, before:** 我在银行工作了三年 is one tap target with no reading and no grammar.
  **After:** 我 / 在 / 银行 / 工作 / 了 / 三年 are separate words colored pronoun, preposition,
  noun, verb, particle; tapping 银行 shows "yínháng"; tapping 了 shows the card "了 · HSK 1 ·
  completed action" and the AI button.
- **English, before:** "You're going to love this light" colors most words as nouns.
  **After:** "going to" + verb shows the card "be going to · A2 · a plan or prediction", "love"
  is a verb and "light" a noun; "went" shows its base form "go".
- Grammar cards and the AI button appear only where a line's language is English, Chinese or
  Japanese (by the caption language code, or by kana for Japanese).

## Technical constraints / invariants

- User-visible text lives in new `strings_grammar_en.xml` and `strings_grammar_zh.xml` in every
  `values-*` folder (`AppLanguageTest`, lint `MissingTranslation`).
- Unit tests stay plain JUnit4: the same Maven artifacts are on the unit-test classpath, so tests
  run the real model and word list.
- The data is loaded on a background thread on first use, like the Japanese analyzer, and
  `tokenizerRevision()` changes when any analyzer becomes ready.
- `GrammarPattern` keeps its syntax for Japanese; levels become a `GrammarLevel` (JLPT, HSK or
  CEFR) shown by the existing badge.
- No network access is added; nothing leaves the device except what the AI button already sends.

## Proposed approach / plan

1. Gradle: a `learnerData` configuration with the three artifacts and a task that copies the
   three Chinese files and the English model into generated assets (`learner-data/`).
2. `EnglishPosTagger`: reads the GIS model, rebuilds OpenNLP's POS features (prefix/suffix,
   ±2-word window, sentence start, token class with word, previous tags) and picks the tags the
   way OpenNLP does with this model (see the notes below).
3. `EnglishMorphology`: tokenizes (punctuation kept for the tagger, contractions kept whole as in
   the model), tags, then splits contractions into their words for grammar (ca + n't, i + 'm),
   adds base forms and forms (BASE, PRES, S3, PAST, PP, ING, MODAL, COMP, SUP, LY, PL) from suffix
   rules and an irregular-verb table, and maps tags to the app's parts of speech.
4. `ChineseDictionary`/`ChineseMorphology`: compact sorted word list (one string, offset and
   frequency arrays), traditional→simplified by character, most-probable segmentation over the
   word DAG, pinyin per word with particle overrides (了 le, 的/得/地 de, 着 zhe, 吧 ba).
5. `LanguageAwareTokenizer` uses them for `en*` and `zh*` codes; Chinese falls back to one
   character per word while the list loads, English to today's heuristic.
6. `EnglishGrammarCatalogue` / `ChineseGrammarCatalogue` + rules, sharing the Japanese matcher.
7. `GrammarExplanations`: picks the language's matcher, shows HSK/CEFR badges, AI button for all.
8. Strings in 8 languages, notices in THIRD_PARTY_NOTICES.md and the licence screen, README.

## Acceptance criteria

- [x] Every English and Chinese catalogue point is found in its own example sentence with its own
  meaning (`EnglishGrammarCatalogueTest`, `ChineseGrammarCatalogueTest`).
- [x] The English tagger gives the same tags as OpenNLP 2.5.4 on 3,000 Tatoeba sentences
  (scratch comparison: 0 of 26,671 tokens differ), and named sentences tag as expected
  (`EnglishMorphologyTest`).
- [x] 我在银行工作了三年 splits into 我/在/银行/工作/了/三年 with pinyin "yínháng" for 银行;
  a traditional line splits the same way and keeps its characters (`ChineseMorphologyTest`,
  and on the emulator `LearnerDataDeviceTest`).
- [x] Japanese grammar and tokenizer tests still pass unchanged.
- [x] Coverage on 3,000 random Tatoeba sentences per language is measured and recorded below.
- [ ] Word card shows the HSK/CEFR badge for English and Chinese (`GrammarExplanationsUiTest`,
  CI). The AI button is the existing one; it now shows for any line with grammar.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
  pass locally and both CI jobs pass on the final head.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the word-card test | CI |
| Physical-device/manual | Open an English and a Chinese video, tap words, check colors, pinyin, grammar cards and the AI button | Owner's phone, preview APK |
| Performance | Load time and heap of each analyzer on the JVM recorded below | Local JVM |

## Risks / edge cases

- Dictionary segmentation can still group or split differently from a teacher (看电视 is one
  entry in jieba's list); unknown names split into single characters.
- English contractions are tagged as one token, as the model expects, then split into their
  words for grammar; a wrong tag on the whole token (isn't, you're) carries over to its parts.
- The OpenNLP model was trained on the UD English Web Treebank (CC BY-SA 4.0); the Apache
  OpenNLP project releases the trained model under Apache-2.0, and the notices credit the
  treebank.
- Grammar rules use word patterns and can fire on tagger mistakes; each point is checked on its
  example and on the Tatoeba sample.
- The APK grows by about 3.7 MB of assets before compression; the alternative (download on first use, as for Japanese) was
  rejected so English and Chinese work offline from the first video without a setup step.

## Implementation notes and deviations

- **Tagger search.** OpenNLP runs a beam of 3, but this model's feature generators are cached
  (`cache="true"`): each word's features, previous tags included, come from the first and best
  sequence that reaches it, so every sequence gets the same scores there. The beam therefore
  always ends on the best tag after the best sequence so far, and the app picks the best tag
  word by word. The token class feature also adds `w&c=` (word and class), which the first
  version missed. With both, the tags equal OpenNLP 2.5.4's on all 3,000 Tatoeba sentences.
- **Fixes after tagging.** The model often tags a word that is also a noun as a noun after a
  modal or after "to" (it may rain, going to rain). After a modal, and after "to" that follows
  going, ought, used, want, need, have, got or try, such a word becomes a verb (not after "be
  used to" or "going to school"). A subject pronoun before 'd (I'd) is a pronoun.
  Word colors use the fixed tags too: the first CI run's `LearnerDataDeviceTest` showed "rain" in
  "It's going to rain" colored as a noun because colors still read the model's raw tag.
- **Chinese splits.** jieba's list keeps some phrases whole that hide grammar. 在家, 我会, 往前,
  往后, a verb with 过 or 着 (看过, 坐着) and 太 or 很 with a one-character adjective (太贵) are
  split. A few tags are corrected (等 v, 回家 v, 别 d, 朝 p, 毕业 v and others in
  `TAG_OVERRIDES`).
- **Pinyin.** A one-character word uses its commonest reading, or the one its part of speech needs
  (还 huán as a verb, hái otherwise). Particles and about 40 common words have fixed readings
  (喜欢 xǐhuan, 东西 dōngxi). A word ending in 儿 is read with r (一点儿 yīdiǎnr), except 女儿 and
  similar.
- **Grammar points.** 167 English points (A1 to C1; 7 of them found by 4 context rules:
  conditionals and when-clauses, question words, yes/no questions, commands) and 151 Chinese
  points (HSK 1 to 6; 7 found by 6 rules: 了 twice, 是…的, 的, V不V, doubled verbs, 一…就…). The catalogues, strings and test examples are
  generated from one table per language. The 21 points added last (this/that, not, all, like,
  place words, as; 们, 用, 为, 向/朝, 以为, 而, 并, 由, 所, 只是, 由于, 根据/通过/按照, 于, 以, 之)
  came from the words the first coverage run missed most.
- **`GrammarPattern`** gains a prefix condition (`第~`), and `matchGrammar` lets rules win ties
  (Chinese 了 and 的 need that).
- **Tests and loading.** Tests that tokenize English or Chinese now wait for the analyzers first,
  so they no longer depend on whether the background load has finished.

## Results

Measured on 2026-10-17 with 3,000 random Tatoeba sentences per language (scratch test, not
committed). "Fully explained" means every grammar word in the line (English AUX, PART, ADP,
SCONJ, CCONJ, DET and inflected verbs; Chinese particles, prepositions, conjunctions, measure
words and suffixes) belongs to a grammar point.

| | English | Chinese |
| --- | --- | --- |
| Lines fully explained | 89% (75% before the last 6 points) | 97% (87% before the last 15 points) |
| Grammar words covered | 96% | 97% |
| Points per line | 3.2 | 2.5 |
| Time for 3,000 lines (JVM) | under 1 s | under 0.5 s |

Load on the JVM: English model about 0.1 s and 6 MB of heap; Chinese word list and tables about
0.2 s and 7 MB. The data adds about 3.7 MB to the assets (1.2 MB model, 2.1 MB word list, 0.5 MB
tables) before APK compression.

Remaining misses are mostly helper verbs outside a pattern (do, has, 'll), -ing forms after nouns
(English) and less common conjunctions and prepositions (Chinese: 既, 自, 至, 因, 如此). Physical-device and live-YouTube checks are left to
the owner's preview test.

## Release intent

`Release-Version: 1.6.0` (owner asked for v1.6.0; exact-version directive in the PR body, no label).
