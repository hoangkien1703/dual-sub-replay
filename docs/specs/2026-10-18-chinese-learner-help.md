# Chinese learner help (v1.6.0)

## Status

Approved for implementation. On 2026-10-18 the owner asked in the project thread for Chinese
learners to get the same help Japanese learners have (grammar, parts of speech and so on), based
on research into how others do it and which materials are best, for the release to be v1.6.0, and
for this to be a separate PR from #123, whose code may be reused. That does not authorize merging
or publishing.

Implemented on branch `claude/chinese-learner-support-v1.6.0-gct5a5`; see
[Implementation result](#implementation-result) and [Validation result](#validation-result).

## Context / problem

Japanese lines are split into learner words by Kuromoji, colored by part of speech, show a kana
reading in the word card, and list grammar points with JLPT levels and an "Explain the grammar
with AI" button ([grammar up to N1](2026-10-07-grammar-to-n1-and-auto-caption-language.md),
[more grammar](2026-10-17-more-grammar-and-stop-ai-reply.md)). Chinese has no analyzer: a line
without kana goes through the Japanese script heuristic, so 我在银行工作了三年 is one tap target,
colored as one noun, with no pinyin and no grammar.

[PR #123](https://github.com/hoangkien1703/dual-sub-replay/pull/123) adds English and Chinese
help together with caption-loading changes. The owner asked for Chinese on its own, in a separate
PR from `main`, reusing #123's Chinese code where it works.

### Research (2026-10-18)

- **How reading tools do it.** Pleco, the Zhongwen popup, Du Chinese and Language Reactor split a
  line into dictionary words and show pinyin per word; Language Reactor also marks words by level.
  Grammar is usually a link to a grammar reference, not detected in the line. Learners complain
  when splitting groups characters that are not words, and when pinyin over every character
  distracts from reading, so pinyin stays in the word card (as kana readings do for Japanese).
- **Word splitting and parts of speech.** Offline segmenters: jieba (dictionary DAG + most
  probable path, word list with frequencies and ICTCLAS tags, MIT), pkuseg and THULAC (trained
  models, more accurate on news text, but larger and slower), LTP and HanLP (full pipelines, heavy
  for a phone). jieba's method needs only its word list, runs in well under a millisecond per line,
  and its tags map directly to the app's colors, so it is used, with corrections for the grammar
  words its tags get wrong.
- **Pinyin.** HanLP's pinyin and traditional→simplified tables (Apache-2.0) give word-level
  readings, so 银行 reads yínháng, not yínxíng. CC-CEDICT also has readings and glosses but is CC
  BY-SA; glosses are not needed because the meaning comes from the translator, as for Japanese.
- **Levels.** HSK 3.0, defined by the 2021 national standard *Chinese Proficiency Grading
  Standards for International Chinese Language Education* (GF0025-2021), replaces the old six-level
  HSK with nine levels (1–6, and 7–9 as one advanced band). Its grammar syllabus lists 572 points
  by level; the new HSK 1–6 exams started pilot sittings in 2026. ivankra/hsk30 (MIT, derived from
  the official lists) publishes the grammar syllabus and the 11,092-word vocabulary list as CSV.
  The grammar levels here follow that syllabus: a point it lists takes its level, any other point
  takes the HSK 3.0 vocabulary level of its first word. Only the level numbers are used; no list is
  packaged.
- **Explanations** are written for this project. Not copied: Chinese Grammar Wiki (AllSet
  Learning) and other grammar sites (no reuse licence), CC-CEDICT glosses (share-alike).

Data used, both permissive and fetched from Maven Central at build time:

| Data | Source artifact | Licence | Size in APK assets |
| --- | --- | --- | --- |
| Chinese word list with frequencies and tags (`dict.txt`) | `com.huaban:jieba-analysis:1.0.2` (jieba's dictionary, MIT) | Apache-2.0 / MIT | ~2.1 MB before compression |
| Word pinyin and traditional→simplified tables | `com.hankcs:hanlp:portable-1.8.6` (`pinyin.txt`, `t2s.txt`) | Apache-2.0 | ~0.5 MB before compression |

Only data files are packaged; no jieba or HanLP code is added.

## Goals

- Chinese lines (caption language `zh*`) split into words with jieba's method, each word colored by
  part of speech and tappable, with tone-marked pinyin in the word card and phrase bar. Traditional
  lines are read through the simplified table so they split the same way and keep their own
  characters.
- Grammar cards for Chinese in the word card, with an HSK 3.0 level badge (HSK 1 … HSK 6, HSK 7–9),
  from a pattern catalogue and a few context rules sharing the Japanese matcher, and the existing
  "Explain the grammar with AI" button.
- Chinese words are spoken by a Mandarin voice (zh-Hans → China, zh-Hant → Taiwan, `cmn` voices
  accepted, best offline voice first).
- Release as **v1.6.0**.

## Non-goals

- No English learner help and no caption-loading changes from #123; they stay in #123.
- No dictionary definitions (the meaning keeps using the translator), no pinyin above the
  subtitle line, no tone colors, no per-word HSK vocabulary level.
- Japanese behavior is unchanged.

## User-visible behavior

- **Before:** 我在银行工作了三年 is one tap target with no reading and no grammar.
  **After:** 我 / 在 / 银行 / 工作 / 了 / 三年 are separate words colored pronoun, preposition,
  noun, verb, particle; tapping 银行 shows "yínháng"; tapping 了 shows the card "V + 了 le ·
  HSK 1 · completed action" and the AI button.
- Grammar cards and the AI button appear for Chinese only when the caption language is Chinese
  (a line of hanzi with an unknown language still goes through the Japanese heuristic, as today).

## Technical constraints / invariants

- User-visible text lives in a new `strings_grammar_zh.xml` in every `values-*` folder
  (`AppLanguageTest`, lint `MissingTranslation`).
- Unit tests stay plain JUnit4: the same data files are on the unit-test classpath, so tests run
  the real word list.
- Data loads on a background thread on first use, like the Japanese analyzer, and
  `tokenizerRevision()` changes when it is ready. No network access is added.
- `GrammarPattern` keeps its syntax for Japanese; levels become a `GrammarLevel` (JLPT or HSK)
  shown by the existing badge.

## Proposed approach / plan

1. Gradle: a `learnerData` configuration with the two artifacts and a task copying the three files
   into generated assets (`learner-data/`), also on the unit-test classpath.
2. From #123: `LearnerData`/`BackgroundLoaded`, `ChineseMorphology` (dictionary, segmentation,
   pinyin), `GrammarMatcher` (shared matcher and `GrammarLevel`), `ChineseGrammar`,
   `ChineseGrammarCatalogue`, `LearnerGrammar`, the Chinese strings in 8 languages, the Chinese
   speech fixes, and their tests. English parts are left out.
3. Re-level the catalogue and rules to HSK 3.0 (`HskLevel` 1–6 and 7–9).
4. `LanguageAwareTokenizer` uses the analyzer for `zh*` codes; the word card picks the language's
   grammar; the AI context names HSK levels.
5. Notices, licence screen, README, tech stack.

## Acceptance criteria

- [ ] Every Chinese catalogue point and rule is found in its own example sentence with its own
  meaning (`ChineseGrammarCatalogueTest`), and every point's level is the HSK 3.0 level recorded in
  the catalogue.
- [ ] 我在银行工作了三年 splits into 我/在/银行/工作/了/三年 with pinyin "yínháng" for 银行; a
  traditional line splits the same way and keeps its characters (`ChineseMorphologyTest`, and on
  the emulator `LearnerDataDeviceTest`).
- [ ] The word card shows the "HSK 1" badge for a Chinese point (`GrammarExplanationsUiTest`).
- [ ] Japanese grammar and tokenizer tests still pass unchanged.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
  pass locally and both CI jobs pass on the final head.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the word-card and data-loading tests | CI |
| Physical-device/manual | Open a Chinese video, tap words, check colors, pinyin, grammar cards, speech and the AI button | Owner's phone, preview APK |
| Live YouTube | Not testable from the cloud (YouTube blocks cloud addresses) | Owner's preview test |
| Performance | Load time and heap of the analyzer on the JVM recorded below | Local JVM |

## Risks / edge cases

- Dictionary segmentation can still group or split differently from a teacher; unknown names
  split into single characters.
- Grammar rules use word patterns and can fire on tagging mistakes; each point is checked on its
  example.
- HSK 3.0 levels differ from the old HSK 1–6 levels many textbooks still use (不得不 is HSK 3 in
  3.0); the badge says HSK, and the spec records which standard.
- The APK grows by about 2.6 MB of assets before compression; downloading on first use was not
  chosen so Chinese works offline from the first video.

## Release intent

`Release-Version: 1.6.0` (the owner asked for v1.6.0; exact-version directive in the PR body, no
label). The latest stable release is v1.5.9 with no newer reservation. PR #123 asks for the same
version; whichever merges first takes 1.6.0, and the other then needs a new version.

## Implementation result

- **Reused from #123** (its first five commits, Chinese parts only): `LearnerData` and
  `BackgroundLoaded`, `ChineseMorphology` (compact sorted word list, most-probable segmentation,
  traditional→simplified by character, splits of phrases that hide grammar such as 在家, 看过 and
  太贵, tag corrections for grammar words, word-level pinyin with particle and neutral-tone
  readings and erhua), `GrammarMatcher` (the Japanese matcher made shared, `GrammarLevel`),
  `GrammarPattern`'s `第~` prefix condition, `ChineseGrammar` (了 twice, 是…的, 的, V不V, doubled
  verbs, 一…就), `ChineseGrammarCatalogue`, the Chinese strings in 8 languages, the word card's
  level badge and long-form layout, and the Chinese speech fixes (Mandarin by country, `cmn`
  voices, highest-quality offline voice first, language fallback when no voice can be selected).
- **Left out of #123:** English (OpenNLP model, tagger, CEFR catalogue), the caption-loading
  changes (page track list, automatic transcript retries, the full-transcript problem icon) and the
  unrelated website and theme work. The APK therefore carries the two Chinese data artifacts only.
- **Changed from #123: HSK 3.0 levels.** #123 used the old six HSK levels. `HskLevel` now has
  HSK 1–6 and HSK 7–9, and every catalogue point and rule takes its level from the GF0025-2021
  grammar syllabus, or the HSK 3.0 word list for its first word when the syllabus does not list
  the point. Examples of changes: 一边…一边 HSK 1 (was 3), 不但…而且 HSK 2 (was 3), 不得不 HSK 3
  (was 5), 与其…不如 / 宁可 / 何必 / 以免 / 固然 / 况且 / 以…为 HSK 7–9 (were 6), 了 at the end
  HSK 1 (was 2), 是…的 and 一…就 HSK 2 (were 3). The badge reads "HSK 7–9" for the advanced band.
- **New point 之一 (HSK 4).** #123 put 之一 under 之 (a written HSK 7–9 particle); 之一 "one of" is
  an everyday HSK 4 word, so it is its own point with its own text in 8 languages, and 之 has a
  new example (这是友谊之桥) because jieba keeps 三分之二 as one word.
- **Eleven more points from the HSK 3.0 syllabus**, picked from the grammar words a first coverage
  run missed most: 对于, 因此, 然而, 除非, 要么…要么, 既…又, 同时, 像…似的, 自从 / 自, 以及, 替.
  The catalogue has 156 points plus 7 rules (163 in all): 44 at HSK 1, 44 at HSK 2, 27 at HSK 3,
  21 at HSK 4, 7 at HSK 5, 4 at HSK 6 and 9 at HSK 7–9 in the catalogue.
- `grammarLanguage` returns Chinese only for `zh*` caption codes; a line of hanzi with no language
  code keeps the Japanese heuristic, as before.

## Validation result

Local runs on 2026-10-18 (JDK 21, Android SDK 36, no emulator: the cloud machine has no KVM).

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| `ChineseGrammarCatalogueTest` (every point found in its example, levels follow HSK 3.0, every level covered) | Passed |
| `ChineseMorphologyTest` (我/在/银行/工作/了/三年, yínháng, traditional offsets, 太 + 贵, 看 + 过) | Passed |
| Japanese grammar, tokenizer and pronunciation tests | Passed unchanged |
| The debug APK carries `assets/learner-data/zh-words.txt`, `zh-pinyin.txt`, `zh-t2s.txt` (2.6 MB before compression) | Checked with `unzip -l` |
| `LearnerDataDeviceTest`, `GrammarExplanationsUiTest.showsHskLevels` | Not run locally (no emulator); run in CI's `managed-device-tests` |
| Physical phone and live YouTube | Not run; owner's preview test |

Coverage on 3,000 random Chinese Tatoeba sentences (CC BY 2.0 FR, scratch test, not committed).
"Fully explained" means every grammar word in the line (particles, prepositions, conjunctions,
measure words and the suffix 们) belongs to a grammar point.

| | Before the 11 new points | Final |
| --- | --- | --- |
| Lines fully explained | 97.0% | 98.0% |
| Grammar words covered | 97.5% | 98.4% |
| Points per line | 2.5 | 2.5 |
| Time for 3,000 lines (JVM) | 0.65 s | 0.61 s |

Loading the word list and tables on the JVM takes about 0.27 s and 8 MB of heap. Remaining misses
are mostly rare or written words (只不过, 至, 及, 若, 如此, 以至于, 罢了).
