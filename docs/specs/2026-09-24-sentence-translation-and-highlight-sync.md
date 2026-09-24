# Whole-sentence translation and steadier spoken-word highlight

## Status

Implemented. Local unit tests, lint and the debug builds pass. Final-head GitHub Android CI and
physical-phone acceptance are pending. The owner asked for issue #57 to be solved and a PR
opened. That request does not authorize merging or publishing.

## Context / problem

- [#57](https://github.com/hoangkien1703/dual-sub-replay/issues/57): dual-subtitle translations
  read unnaturally. **Short paired phrases** is the default format, and it translated each
  display row of 48 characters or fewer on its own
  (`translatePlaybackWindow` → `translate(row.originalText)`). ML Kit never saw the whole
  sentence.
- Auto-caption cues often end one sentence and start the next inside the same cue. For example,
  "…a fraction of the price. Even at, look at" is followed by "This.". `SubtitleMerger.merge`
  only ends a segment when the text *ends* with punctuation, so translation units straddled
  sentences.
- The owner compared the app with the 4you app. 4you translates the whole sentence and shows a
  matching part under each short line: "The French Revolution temporarily" / "stalled
  relocation efforts." becomes "Cuộc Cách mạng Pháp tạm thời" / "làm đình trệ các nỗ lực di
  dời.".
- The owner also reports that the spoken-word underline is still sometimes off. There are
  three causes in the code:
  1. When YouTube reveals several auto-caption words in one DOM update, the live path jumps to
     the newest one. The resolver never moves backward, so the underline stays ahead.
  2. json3 auto-caption lines overlap the next line, so the words of their last chunk were
     stretched to the display end rather than to the start of the next speech.
  3. A wrong live match (a repeated "the" or "you") was held until a seek.

## Goals

- Every translation request sends a complete sentence, as far as the caption punctuation
  allows.
- Short rows show consecutive slices of that one translation.
- The underline stays with the spoken word when YouTube reveals several words at once, and it
  recovers from a wrong match without a seek.

## Non-goals

- No new translation engine, cloud service, punctuation-restoration model or new setting.
- No change to the v0.9.5 live/timestamp timing path beyond the bounded corrections below.
- No colouring of the translation line by timing.

## User-visible behavior

- **Short paired phrases:** each short line shows its own part of one whole-sentence
  translation, the way 4you does.
- **Whole sentence:** a row now ends at a real sentence end, including an end found in the
  middle of a cue.
- **Captions without punctuation:** grouping is unchanged.
- **Highlight:**
  - After a multi-word caption update, the underline moves through the revealed words using
    their timestamps.
  - A position that stays behind the held underline for 1 s of playback is accepted.
  - Brief jitter still never moves the underline backward.

## Technical constraints / invariants

- Translation stays on-device through the ML Kit session; the extra prefix requests use the
  same memory and disk caches ([tech stack](../project/tech-stack.md)).
- The per-session `SubtitleStore` binary format gains an optional sentence record. The stores
  are temporary files recreated for every load, so no migration is needed.
- Unit tests stay plain JUnit4.

## Proposed approach / plan

1. `SubtitleMerger.splitAtSentenceEnds` cuts segments at sentence ends inside a cue. It reuses
   `buildSplitSegments`, so real word timings are kept. It runs before `sentenceCaptionUnits`
   whenever natural captions are on (the default), including in `prepareCaptionDisplayStore`.
2. `SubtitleSegment.sentence` (`SentenceSlice`: the sentence text, its row cut offsets, and this
   row's index) is set on rows produced by short-phrase splitting.
3. `translatePlaybackWindow` translates the full sentence once, plus the text before each cut.
   `translationSlices` places each cut after as many translated words as its prefix produced,
   or proportionally when no prefix is available. It snaps to nearby punctuation, otherwise to
   a word edge, or to a character edge for scripts without spaces. `withTranslation` fills
   every row of the sentence.
4. `CaptionDocumentParser.speechEndMs` ends a json3 event's word timings at the next event's
   start when the two overlap.
5. `estimateWordTimings` weights each word by its letters and digits plus 1, ignoring
   punctuation.
6. Live progress records the first token revealed by an update.
   - `liveBoundedPosition` limits the timestamp word to the range of words revealed by that
     update.
   - `CaptionHighlightResolver` accepts a backward correction after
     `HIGHLIGHT_BACKWARD_CORRECTION_MS` (1000 ms) of playback.

## Acceptance criteria

- [x] "…price. Even at, look at" + "this." produces the rows "…price." and "Even at, look at
  this." (`TranslationCoordinatorTest`).
- [x] Unpunctuated captions keep their previous units (`TranslationCoordinatorTest`).
- [x] Short rows carry the whole sentence and the cuts. One translation request for the
  sentence plus one per prefix fills every row, in order (`TranslationSlicingTest`,
  `CaptionTranslationCancellationTest`).
- [x] The slices, joined, reproduce the translation. There are no empty slices while the
  translation has enough words. Targets without spaces split on characters
  (`TranslationSlicingTest`).
- [x] Overlapping json3 lines end their word timings at the next line's start; the cue's own
  display end is unchanged (`CaptionDocumentParserTest`).
- [x] Punctuation does not lengthen an estimated word (`WordTimingTest`).
- [x] A multi-word live update follows the timestamp word within the revealed range. A
  one-word update keeps the live word. A backward correction happens only after 1 s
  (`KaraokeTimingTest`).
- [x] Existing seek, pause, window and highlight regression tests pass unchanged.
- [ ] Final-head CI `verify-build` and `managed-device-tests` pass.
- [ ] Owner phone check (see [QA notes](../qa/sentence-translation-highlight.md)).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local Linux + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux + CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest`; existing highlight/caption-format device tests pass | CI only |
| Physical-device/manual | Scenarios in [QA notes](../qa/sentence-translation-highlight.md) | Owner's phone |
| Live YouTube | Same as above; offline tests cannot establish it | Owner's phone |
| Performance | Up to *n* extra cached ML Kit calls per *n*-row sentence; no new baseline | Not measured |

## Risks / edge cases

- **Word order:** when the target language reorders words (for example English to Japanese),
  a slice may not match its source row word for word. The whole sentence is still complete and
  natural across its rows.
- **More rows than words:** if the translation has fewer words than the sentence has rows,
  trailing rows can be empty.
- **Prefix mismatch:** a prefix translation can differ from the corresponding part of the full
  translation. Only its word count is used, and nearby punctuation still wins.
- **Mapping errors:** live mapping can still choose the wrong word. It now self-corrects after
  1 s rather than waiting for a seek.

## Release intent

`release:patch`. This is a user-visible bug fix with no new setting. The owner did not state an
intent, so the project default applies.

## Implementation result

Implemented as planned. One deviation: the first version sliced the translation by source
character proportion. The tests showed this was off by about one word for Vietnamese, so the
prefix-translation word count was added as the primary estimate, with proportion as the
fallback.

## Validation result

- Local (Linux, Android SDK 36, JDK 21):
  - `formatCheck`, `complexityCheck`, `testDebugUnitTest` (270 tests), `lintDebug`,
    `assembleDebug` and `assembleDebugAndroidTest`: passed.
  - `python3 -m unittest discover -s tools/tests`: passed.
- Managed-device tests: not run locally (no emulator); CI runs them.
- Physical phone / live YouTube: not run; pending owner acceptance.
- Final-head CI status is reported on the PR.
