# App no longer freezes when a video reaches its end

## Status

Implemented. Requested by the owner on 2026-10-01 ("there is a bug that make app crashed when i
watch to the end of video in YouTube. can you fix that"). Local checks pass; see the PR for the
final-head CI. Not yet confirmed on the owner's phone.

## Context / problem

The owner reports that the app crashes when a YouTube video plays to its end. No stack trace was
available, so the cause below is inferred from the code and the Compose library sources, not
reproduced on a device.

With "Translated captions: Only when paused" (the setting from
[active line stays visible on pause](2026-09-29-active-line-stays-visible-on-pause.md)), the
video's automatic pause at its end shows the translations. Rows grow taller and push the active
row, which is one of the last transcript rows, out of the list. `KeepActiveRowOnScreen` then calls
`bringBackActiveRow`:

1. `scrollToItem(target)` is clamped near the end of the list, so the row lands near the bottom,
   not at the top as the code assumed.
2. The follow-up `scrollBy(row.size - viewportEnd)` assumed the row sat at offset 0. It scrolls
   back by nearly a whole viewport, which pushes the row out again.
3. That layout change re-triggers the same correction. Snapshot apply notifications and the
   resumed coroutine run on `AndroidUiDispatcher`, whose trampoline runs newly queued work in the
   same pass, so the loop never returns to the main `Looper`. The UI stops drawing and Android
   shows "app isn't responding", which reads as a crash.

The same freeze can happen on any pause while the active line is within roughly the last screen
of the transcript.

## Goals

- Bringing back a pushed-down active row puts it in the bottom slot from where the row really is.
- A correction that still cannot fit the row never re-triggers itself.

## Non-goals

- No change to which row is active, to caption timing, to translation, or to the WebView bridge.
- No change to the behavior agreed in the pause-visibility spec for rows away from the end.

## User-visible behavior

Before: with translations shown only when paused, reaching the end of a video (or pausing near
the end of the transcript) can freeze the app until Android offers to close it.

After: the last line and its translation stay on screen and the app keeps responding.

## Technical constraints / invariants

- Single WebView and origin re-verification invariants are untouched
  ([tech stack](../project/tech-stack.md#architecture-invariants)).
- Logic stays in small `internal` functions tested by plain JUnit
  (`PlaybackArchitectureTest`).

## Proposed approach / plan

1. In `ui/DualSubApp.kt`, compute the bottom-slot scroll from the row's measured offset after
   `scrollToItem` (`bottomSlotScrollDelta`) and skip a zero scroll.
2. After each correction, set `activeFitted` from whether the row now fits, so a correction that
   could not fit it waits for real user or playback changes instead of answering its own layout.
3. Unit test for the scroll math; instrumented test for pausing on the last line of a long
   transcript with translations shown only when paused.

## Acceptance criteria

- [x] `bottomSlotScrollDelta` returns the distance from the row's real position to the bottom slot
      (unit test `bringingBackAPushedRowMovesItFromWhereScrollToItemLeftIt`).
- [ ] Pausing on the last of 16 lines with "Only when paused" keeps that line and its translation
      inside the viewport and the UI goes idle
      (`SubtitleUiTest.pausingOnTheLastLineAtTheEndOfAVideoKeepsItOnScreen`, managed device).
- [ ] Existing pause-visibility and auto-scroll instrumented tests still pass (managed device).
- [ ] Watching a video to its end on the owner's phone no longer freezes the app.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local and CI |
| Android lint/build | `./gradlew formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` passes | Local and CI |
| Managed-device/emulator | `managed-device-tests` and `fdroid-device-tests` CI jobs pass, including the new test | CI only (no KVM locally) |
| Physical-device/manual | Play a video to its end with "Only when paused" on; app stays responsive and the last line shows | Owner's phone |
| Live YouTube | Same as above on a live watch page | Owner's phone |

## Risks / edge cases

- If the cause on the owner's phone is different, this fix will not help; the owner's settings
  and whether the whole app closed are asked in the thread.
- After a failed fit the list stops auto-correcting until the row fits again or playback moves
  on. That is the intended safety stop; a user scroll or the next line resumes normal behavior.

## Release intent

`release:patch` (the repository default for a bug fix; no label needed). The next patch is
estimated as v1.3.3 (latest release v1.3.2 on 2026-09-30, no draft reservation seen); the number
is an estimate until reserved.

## Implementation result

As planned: `bringBackActiveRow` uses `bottomSlotScrollDelta`, and both scroll branches of
`KeepActiveRowOnScreen` refresh `activeFitted` from the new `activeRowFits` helper. No deviations.

## Validation result

- Passed locally on 2026-10-01: `formatCheck`, `complexityCheck`, `testDebugUnitTest`,
  `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`.
- Managed-device tests: not run locally (no KVM); left to CI. See the PR for final-head CI.
- Physical device and live YouTube: not verified; needs the owner's phone.
