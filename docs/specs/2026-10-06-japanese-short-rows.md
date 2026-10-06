# Shorter Japanese and Chinese subtitle rows

## Status

Implemented. The owner asked on 2026-10-06, with a screenshot of a 40-character Japanese row: "in
Japanese the sentence seems long, can you break it into smaller sentences and still translate
correctly, so users can learn easily?" Shipped in the same PR as the
[Google Translate engine](2026-10-05-opt-in-google-translate.md) so one preview APK tests both.
That request does not authorize merging or publishing.

## Context / problem

- Short-phrase rows are cut by `SubtitleMerger.splitSentenceChunks` at 48 characters. Its clause
  marks are `, ; :` followed by a space, so Japanese 「、」 never cuts a row, and a long run without
  spaces is cut every 48 characters, even in the middle of a word.
- A Japanese or Chinese glyph is about twice as wide as a Latin letter, so 48 characters is two or
  three phone lines. 「皆さんもあると思うんですけど、日本語を話しているときに、すごい間違えたりとか、」
  (40 characters) stayed one row.

## Goals

- Japanese and Chinese rows hold at most 24 characters (half the Latin limit).
- Rows end after 、，。！？ first; very short clauses (「でも、」) share a row with a neighbour.
- A clause still too long is cut where a kanji or katakana word follows hiragana, which usually
  starts a new word, and only otherwise at the fixed width.
- Translation stays a slice of the whole sentence's translation (existing sentence-level design).

## Non-goals

- No change for spaced languages, including Korean, or for the Natural flow (whole sentence) format.
- No morphological analysis (Kuromoji) in the splitter; it is downloaded later and only for Japanese.

## User-visible behavior

In the default short-phrase format, the screenshot's row becomes three rows:
「皆さんもあると思うんですけど、」「日本語を話しているときに、」「すごい間違えたりとか、」, each with its
slice of the one sentence translation.

## Technical constraints / invariants

- Rows must stay exact slices of the sentence (`withSentenceSlices` finds them with `indexOf`), so
  pieces keep their spaces until the final trim.
- Regex lookbehinds stay fixed width for Android's ICU engine.

## Proposed approach / plan

1. `splitSentenceChunks`: when more than half the letters are Han, hiragana or katakana, use
   `splitCjkChunks` with `cjkMaxCharacters(48) = 24`.
2. `splitCjkChunks`: split after 、，,；;：:。！？!?, cut over-long clauses with `cutCjkClause`, join a
   clause shorter than a third of the limit to its neighbour when they fit and the first does not
   end a sentence.
3. Unit tests in `SubtitleMergerTest`.

## Acceptance criteria

- [x] The screenshot sentence splits into the three clauses above (`SubtitleMergerTest`).
- [x] 「でも、」「やっぱり、」 share a row; joined rows reproduce the text (`SubtitleMergerTest`).
- [x] A long run without commas is cut before a kanji word and no row exceeds 24 characters.
- [x] Captions with spaces after 、 still give exact slices; Korean and short rows are unchanged.
- [x] Existing splitter tests (including the long Chinese sentence) pass.
- [ ] Owner phone check: Japanese rows are shorter and their translations line up.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local + CI |
| Android lint/build | CI-parity Gradle command passes | Local + CI |
| Managed-device/emulator | `SubtitleSplitterDeviceTest` still runs the splitter on Android's regex engine | CI |
| Physical-device/manual | Owner plays a Japanese video in short-phrase format | Owner's phone |

## Risks / edge cases

- Slicing one translation into more rows makes each slice rely more on prefix translations; when
  word order differs a lot (Japanese → English), a slice can hold words from a neighbouring clause.
- The kana-to-kanji heuristic can still cut inside a compound written in mixed script.

## Validation result

- Local (Linux, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed; 448 unit tests, 0 failures.
- Managed-device tests: CI. Physical phone: pending the owner's check.

## Revision: Japanese sentence ends without a space (2026-10-06)

The owner saw one translation spread across the wrong rows: "And I often use it accidentally.
This is quite" / "natural" / "Japanese." under the rows "…使うことが" / "多いです。" /
"これはかなり自然な日本語な" / "ので是非覚えておいてください。". Auto-generated Japanese captions
put no space after 。, and `sentenceBreak` only split on whitespace, so "…多いです。これは…"
stayed one translation unit holding two sentences; the 8-second unit cap then cut it at "な|ので".

- `sentenceBreak` now also breaks right after 。！？ when no space follows, unless a closing quote
  or bracket (」』）"'’”) or another end mark follows.
- Acceptance: each Japanese sentence gets its own translation, and a quote closing after 。 stays
  in its sentence.
- Tests: `SubtitleMergerTest.japaneseSentenceEndsWithoutASpaceStartANewSentence`,
  `SubtitleMergerTest.aQuoteClosingAfterTheSentenceMarkStaysTogether`,
  `TranslationSlicingTest.japaneseTranslationUnitsFollowSentenceEndsNotCueEnds`.
- Validation: the CI-parity Gradle command and `tools/tests` passed locally. Managed-device tests:
  CI. Physical phone: pending the owner's check.
- Still open: inside one sentence, verb-final Japanese can still put an English word on the
  neighbouring row (see Risks).
