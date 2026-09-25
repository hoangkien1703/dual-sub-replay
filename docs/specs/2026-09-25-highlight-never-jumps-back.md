# Spoken-word highlight never jumps backward during playback

## Status

Implemented. Local checks pass. Final-head CI and the owner's phone check are pending. The owner
asked for a separate PR from `main`. That request does not authorize merging or publishing.

## Context / problem

On the phone build of PR #76, the owner saw the spoken-word underline sometimes jump back to the
first word of the line or to the previous sentence. It felt jerky. The same highlight code is on
`main` (see [sentence translation and highlight sync](2026-09-24-sentence-translation-and-highlight-sync.md)).

There are two causes in the code:

1. **Every playback-window slide wiped the highlight state.**
   - `SubtitleStore.windowIndices` keeps rows from 30 s before playback, so the window's first row
     changes every few seconds.
   - When it did, `AppViewModel.publishSubtitleWindow` reset `CaptionHighlightResolver` and
     `LiveCaptionTracker`, then set the row and word from timestamps alone.
   - Auto-caption lines overlap in time, so the timestamp row was often the previous sentence.
   - The reset tracker also restarted live progress at word 0 of YouTube's line, which is the jump
     to the first word.
   - The reset only existed because highlight positions are indices into the current window.
2. **One signal could move the highlight back.** The 1 s backward correction inside a row trusted
   live progress alone. `liveBoundedPosition` clamps the timestamp word down to the live word, so
   live progress at word 0 pulled the underline back to the start of the row even while the
   timestamps were ahead.

## Goals

- During normal playback, the highlight only moves forward.
- It moves backward only for a real reason:
  - a seek back;
  - a new video;
  - rebuilt rows (caption format, highlight toggle, reload, settings reset);
  - live progress **and** timestamps both agreeing, for 1 s, that it is ahead in the same row.

## Non-goals

- No change to live-caption matching, timestamps, window size or translation scheduling.

## User-visible behavior

- **Before:** every few seconds the underline could jump back to the previous sentence or to the
  first word of the line, then catch up.
- **After:** the underline stays in place or moves forward. A backward seek still moves it back
  at once. A wrong live match still recovers after 1 s, because the timestamps also point earlier.

## Technical constraints / invariants

- Row ids are store indices (`SubtitleMerger.merge`, `prepareCaptionDisplayStore`). The same id in
  two windows is the same row.
- Unit tests stay plain JUnit4.

## Proposed approach / plan

1. `windowShift(previous, rows)` in `PlaybackTranslation.kt` returns how many rows the window
   dropped from its start: 0 for the same window, null when the new window does not start inside
   the old one (seek, reload, first window).
2. `CaptionHighlightResolver.shift` and `LiveCaptionTracker.shift` move their held positions by
   that amount. They reset only when the held row left the window. The tracker keeps its live
   progress, so there is no 2-revision warm-up after a slide.
3. `publishSubtitleWindow` shifts the held position instead of resetting it, and keeps the
   current row and word. It still resets and recomputes from timestamps when `windowShift` is null.
4. `CaptionHighlightResolver` accepts a backward correction only when the timestamp word is in the
   held row and before the held word, in addition to the existing 1 s, same-row, live-only rules.

## Acceptance criteria

- [x] Live progress at word 0 while timestamps are ahead, or in the previous row, never moves the
  highlight back, however long it lasts
  (`KaraokeTimingTest.liveProgressAloneCannotPullTheHighlightBackToTheFirstWord`; fails with the
  old rule).
- [x] After a window slide, timestamps pointing at the overlapping previous row do not move the
  highlight back (`windowShiftKeepsHoldingTheSameRow`).
- [x] A slide past the held row resets it (`windowShiftPastTheHeldRowResets`).
- [x] The live tracker continues after a slide without the warm-up
  (`liveTrackerShiftKeepsProgressWithoutWarmUp`).
- [x] `windowShift` handles the same window, a forward slide, a backward window and empty lists
  (`windowShiftFindsTheNewFirstRowInThePreviousWindow`).
- [x] A wrong live match still recovers after 1 s when the timestamps agree, and existing highlight
  tests pass unchanged (`wrongLiveWordMatchRecoversInsideTheSentenceAfterSustainedDisagreementOnly`).
- [ ] Final-head CI `verify-build` and `managed-device-tests` pass.
- [ ] Owner phone check: two minutes or more of an auto-captioned video with no backward jump.
  A backward seek still moves the highlight back at once.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including the new `KaraokeTimingTest` cases | Local Windows + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Windows + CI |
| Managed-device/emulator | Existing `pixel2Api36DebugAndroidTest` suite | CI |
| Physical-device / live YouTube | Scenario in the last acceptance criterion | Owner's phone |

## Risks / edge cases

- A wrong live match whose timestamps also point ahead is now held until the speech catches up,
  or until a seek. Before, it corrected after 1 s. Staying slightly ahead is less jarring than
  jumping back.

## Release intent

`release:patch`: a user-visible bug fix with no new setting (project default).

## Implementation result

Implemented as planned.

## Validation result

- Local (Windows, JDK 17, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed, with 281 unit tests and 0 failures.
- With the old backward-correction rule put back, the new first-word test fails (1 of 20 in
  `KaraokeTimingTest`), so it covers the reported jump.
- Managed-device tests: not run locally. CI runs them.
- Physical phone / live YouTube: not run. Pending owner acceptance.
