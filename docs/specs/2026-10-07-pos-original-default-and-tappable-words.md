# POS colors on the original line by default, and words that could not be tapped

## Status

Implemented. The owner asked for both changes in the project thread on 2026-10-07.

## Context / problem

1. Word learning mode colored both subtitle lines by default ("Colored subtitle lines" = Both).
   The owner wants the parts of speech colored only on the original line unless the learner
   picks otherwise.
2. In the transcript, some words could not be selected. In the owner's screenshot the active row
   "there's plenty of situations of people" showed "plenty" and "people" uncolored, and tapping
   them did nothing. Both words end a caption chunk, and a visible gap follows each one.

Cause: the original line is split into words by `\b[\w'-]+\b`. On Android, `java.util.regex` is
ICU, and ICU reports no word boundary in front of an invisible format character (category Cf,
such as a zero-width space, word joiner, byte-order mark or direction mark). When caption text
carries one right after a word, the trailing `\b` fails and the whole word is skipped, so it gets
no color and no tap target. The JVM regex used by unit tests treats those characters as
boundaries, which is why tests never caught it. Checked against the system ICU 74 library:
`"there's plenty​ of situations of people​"` gave `there's, of, situations, of` with the
old pattern. The translation line uses a different pattern without `\b`, so it was unaffected,
which matches the screenshot (the Vietnamese line is fully colored).

## Goals

- New installs, and learners who never chose a value, color the original line only.
- Every word on an original line is a tap target and gets a color, whatever invisible characters
  the caption text carries.

## Non-goals

- Overwriting a value the learner already saved. The target is written to preferences only when
  the learner picks a chip, so an explicit choice stays.
- Removing invisible characters from caption text or changing the gaps they render as.

## User-visible behavior

- Before: the chip row showed Both selected on a fresh install. After: Original is selected; Reset
  settings also returns to Original.
- Before: words followed by an invisible caption character could not be tapped or colored. After:
  they behave like every other word. Words with a curly apostrophe ("there’s") are now one word
  instead of "there" and "s".
- The floating overlay's translation line stays tappable with the new default (it used to need
  Translation or Both colors to be tappable, unlike the transcript rows).

## Technical constraints / invariants

User-visible strings are unchanged. No preference key or migration changes.

## Proposed approach / plan

- Add `DEFAULT_WORD_LEARNING_TARGET = "original"` and use it for the state default, the stored
  preference fallback, Reset settings and composable parameter defaults.
- Replace the spaced-language word pattern with
  `[\p{L}\p{M}\p{N}_]+(?:['’-]+[\p{L}\p{M}\p{N}_]+)*`, which needs no `\b` and behaves the same on
  ICU and the JVM. Normalize `’` to `'` before the English word lists are checked.
- Let the overlay translation line accept taps whenever word learning and tap-to-learn are on.

## Acceptance criteria

- [x] `DualSubUiState()` defaults to `"original"`; the stored fallback and Reset use the same constant.
- [x] Tokenizing `there's plenty<mark> of situations of people<mark>` yields all six words for
      U+200B, U+2060, U+200E and U+FEFF (unit test) and on Android's regex engine (device test).
- [x] Curly-apostrophe and accented words stay whole.
- [x] Existing tokenizer, highlight and phrase-selection tests still pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes, including the new tokenizer tests | Linux, JDK 21 |
| Android lint/build | `./gradlew formatCheck complexityCheck lintDebug assembleDebugAndroidTest` passes | Linux |
| Managed-device/emulator | `TokenizerDeviceTest` passes on the API 36 managed device | PR CI |
| Physical-device/manual | Tap "plenty" and "people" in the screenshot's video; Original chip selected on a fresh install | Owner's phone |
| Live YouTube | Same as above with live captions | Owner's phone |

## Risks / edge cases

- Learners who never touched the setting will see translation colors disappear after updating.
  That is the requested default; they can pick Both again.

## Release intent

`release:patch`: app code changes, no explicit owner intent, so the default patch applies.

## Implementation result

As planned. The overlay's parameter default was already `"original"` and is left as a literal so
the detekt baseline entry for that function still matches.

## Validation result

- Passed locally: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebugAndroidTest`
  (11/11 tokenizer unit tests).
- ICU behavior reproduced with the system ICU 74 library for the old and new patterns.
- Not run here: managed-device tests (in PR CI), physical device and live YouTube (owner).
