# Sentence clip keeps its tail

## Status

Implemented. Owner request 2026-11-25: find out why the device test `LineAudioWebViewTest.aLineReplayedAfterALongPauseStillGivesItsClip` failed ("expected:<2.65> but was:<2.34>") on `main` after PR #265 and twice on PR #267, and fix the code or the test without skipping it. Merge and release need the owner's OK.

## Context / problem

Since PR #240 (choppy clips, 2026-11-17) the page recorder (`ui/LineAudioScripts.kt`) takes its sound in 16384-sample blocks, about 0.7 s at 24 kHz. A block reaches the script only once it is full, so the newest kept sound is up to 0.7 s (plus the page's lag) behind the video's `currentTime`.

The app asks the page for a line's clip as soon as the video passed the line's tail (`lineAudioReady`, checked every 1.5 s). At that moment the last block often hasn't come in yet. `clip()` then found a run that stopped short of the window's end, and its "paused right at the line's end" rule (the run reaches the line's end minus 0.15 s) accepted it. So the clip was cut where the newest block ended: the 0.4 s tail was lost, and up to 0.15 s of the line's own end with it. The app keeps a clip once saved, so the card kept the short one.

This is a real sound bug, not emulator timing. The test asked at 6.7 s for the window 3.75-6.4 s; when the newest block ended between 5.85 s and 6.4 s it got 2.34 s instead of 2.65 s. Whether that happens depends on where the block edges fall, which is why it failed on some runs and passed on others. Reproduced in Chromium with the test's tone fixture: before the fix, asks at 6.45-6.69 s gave 2.32 s clips, later asks 2.65 s.

## Goals

- While the page video still plays, a run that ends at the newest kept block is not where the line's sound ends: `clip()` answers null and the app asks again on a later check (it already retries after 5 s, well inside the 90 s kept).
- A video paused at the line's end (Pause after each line, the muted replay that stops at the tail) still gives the line without its tail, but only once the sound played before the pause has come in (the audio clock of the newest sound, `state.heard`, has reached the pause, `state.cut`).
- An ended video, or a pause the recorder didn't hear, keeps today's behaviour.

## Non-goals

- No smaller blocks (they made clips choppy, PR #240).
- No change to when the app asks.

## Acceptance criteria

- [x] In Chromium with the test fixture, asks right after the window's end give null and then the whole 2.63-2.65 s clip; never a short one.
- [x] A video paused at 6.06 s still gives the line up to the pause (2.30 s, same as before).
- [x] Unit test `theRecorderWaitsForTheLastBlockBeforeCuttingAClip` pins the waiting rule.
- [x] Device tests `aLinePausedHalfWayAndReplayedStillGivesItsClip` and `aLineReplayedAfterALongPauseStillGivesItsClip` ask as the app does: from the window's end (6.4 s), again every 200 ms for up to 5 s, and still expect 2.65 s ± 0.1 s.
- [ ] Device tests pass in PR CI.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest --tests '*LineAudioScriptsTest'` passes | Cloud |
| Script check | Chromium fixture harness: asks from 6.4 s give null, then the full clip | Cloud (Playwright) |
| Managed-device/emulator | `LineAudioWebViewTest` passes | PR CI (no KVM in cloud) |
| Physical-device/manual | A card's Sentence sound ends with the line's last word and a short pause after it | Owner's phone |

## Release intent

`release:patch`: a bug fix in the page recorder.

## Implementation result

As planned: `state.heard` in `listen`, the `arriving()` check in `clip()`'s `runEnd`, and `clipOf` in the device test.
