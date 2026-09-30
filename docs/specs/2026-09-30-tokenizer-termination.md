# Tokenizer always finishes on rare Chinese characters

## Status

Implemented. Approved for implementation by the owner on 2026-09-30 ("do what you recommend",
after the review of the external Sol 6.1 audit, item 1). Local checks pass; see the PR for the
final-head CI.

## Context / problem

`LanguageAwareTokenizer` treats U+4E00–U+9FFF as CJK (`CJK_REGEX`), but its script-boundary
fallback treats only U+4E00–U+9FAF as kanji. A character in U+9FB0–U+9FFF (for example 鿏,
U+9FCF, the character for meitnerium) matches no branch, and the final "Latin" branch refuses to
consume a CJK character. The loop then adds an empty token at the same position forever until
the heap runs out.

The fallback is used for all Chinese text, and for Japanese until the dictionary loads. Caption
loading also calls it: `CaptionDocumentParser` and `SubtitleMerger` estimate word timings with
`estimateWordTimings`, which tokenizes CJK text. So a Chinese caption track containing one such
character runs the app out of memory while loading captions. `OutOfMemoryError` is not an
`Exception`, so the caption loader's error handling does not catch it.

Reproduced on 2026-09-30 by compiling the unchanged sources with a harness (64 MB heap):
`tokenize("龰", "zh")`, `tokenize("鿏")`, `tokenize("鿿", "zh")` and
`estimateWordTimings("第109号元素鿏是人工合成的", 0, 3000)` each threw `OutOfMemoryError`
within 2 s. The review is in the project's `audit-review/sol-6.1-audit-verdict.md`.

## Goals

- Every tokenizer loop iteration consumes input, so tokenizing always finishes.
- Kanji detection covers the same range as CJK detection.
- Every emitted token is a non-empty, in-bounds, ordered span whose text equals its substring,
  and no span boundary splits a surrogate pair.

## Non-goals

- No new script support (CJK Extension A, compatibility ideographs, supplementary Han keep
  their current treatment as non-CJK runs).
- No change to part-of-speech rules, Kuromoji grouping, or spaced-language tokenizing.

## User-visible behavior

- **Before:** a Chinese video whose captions contain a character in U+9FB0–U+9FFF crashes the
  app while captions load; the same happens when such text reaches tap-to-learn.
- **After:** those characters are grouped as kanji like their neighbors, captions load, and
  words can be tapped.

## Technical constraints / invariants

- Keep logic in `data/LanguageAwareTokenizer.kt`; tests are plain JUnit4 calling the public or
  `internal` functions.
- Regression tests must fail rather than hang CI if the old behavior returns (JUnit timeouts).

## Proposed approach / plan

1. Make `isKanji` use U+4E00–U+9FFF, the range `CJK_REGEX` uses, and classify single characters
   without allocating a string per character.
2. Guard the fallback branch: if it consumed nothing, consume one code point (a whole surrogate
   pair when present) so the loop always advances.
3. Add tests with timeouts for U+9FAF, U+9FB0, U+9FCF, U+9FFF (Chinese, unknown and Japanese
   language codes), a realistic Chinese sentence, `estimateWordTimings` on it, supplementary Han,
   emoji, combining marks, mixed scripts, punctuation-only input and lone surrogates, each
   asserting the span invariants above.

## Acceptance criteria

- [x] `tokenize` returns within the test timeout for each listed input and every token passes
  the span invariants.
- [x] `estimateWordTimings` on a Chinese cue containing U+9FCF returns word timings.
- [x] Existing tokenizer, morphology, word-timing and caption tests still pass.
- [x] The new tests fail against the old code (verified locally by reverting the fix).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes, including the new `TokenizerTerminationTest` | Local Linux, CI |
| Regression proof | New tests fail (timeout or assertion) with the fix reverted | Local Linux |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| Managed-device/emulator | Existing suite unchanged; no new device test needed for a pure function | CI |
| Physical-device/manual | Not required; the change is a pure text function | — |
| Live YouTube | Not required | — |

## Risks / edge cases

- U+9FB0–U+9FFF characters now join adjacent kanji runs, which changes word boundaries only for
  text that previously crashed.

## Release intent

`release:patch` (the repository default for a bug fix). Next patch is estimated as v1.3.2
(latest release v1.3.1 on 2026-09-30, no draft reservation seen); the number is an estimate until
reserved.

## Implementation result

- `isKanji` now matches `CJK_REGEX`'s Han range; character checks use a `Char` helper instead of
  building a one-character string.
- The fallback branch consumes at least one code point when nothing else matched.
- New `TokenizerTerminationTest` covers the inputs above with 5-second timeouts.

## Validation result

See the PR description for the commands run locally and the final-head CI result.
