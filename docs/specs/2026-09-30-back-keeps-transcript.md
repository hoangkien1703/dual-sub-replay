# Leaving with Back keeps the video and its transcript

## Status

Implemented. The owner reported the bug on 2026-09-30 ("when I leave the app for a moment and
come right back, it has to load the transcript again") and asked for a fix. Local checks pass;
see the PR for the final-head CI and the phone check.

## Context / problem

`SingleYouTubePage` (`ui/YouTubeBrowserScreen.kt`) handles Back itself: it goes back in the
WebView's history, and when there is no earlier page it calls `Activity.finish()`.

The app opens straight to the last page it showed (`last_browser_url`) and opens shared links
directly, so a video page is often the first page in the WebView's history. Back on it closed the
activity, which cleared `AppViewModel` and destroyed the WebView. Returning then started from
scratch: the page reloaded, a new `AppViewModel` deleted the old transcript files
(`subtitle-transcripts`), fetched the captions from YouTube again, rebuilt the transcript and
translated the visible window again. Android 12 and later stopped closing root activities on Back
for this reason, but the app's own Back handler bypassed that default.

Leaving with Home or the app switcher keeps the activity, so it does not reload the transcript
while Android keeps the app in memory. If Android closes the app in the background to reclaim
memory, it still reloads; that case needs a disk cache of prepared transcripts and is out of scope
here.

## Goals

- Back on the first page sends the app to the background like Home, so returning shows the same
  page, video position, transcript and translations without loading them again.
- Back still goes to the previous page when there is one.

## Non-goals

- Keeping transcripts after Android closes the app's process (a possible follow-up).
- Any change to the WebView, caption loading or translation.

## User-visible behavior

- **Before:** Back on a video you opened directly (or that the app reopened on start) closed the
  app; coming back reloaded the page and showed "Finding the best caption track…" again.
- **After:** Back leaves the app the way Home does; coming back continues where you were.

## Technical constraints / invariants

- One WebView (`SingleYouTubePage`); navigation classification unchanged.
- Plain JUnit4 test through an `internal` function.

## Proposed approach / plan

1. `Activity.leaveKeepingState()`: `moveTaskToBack(true)`, falling back to `finish()` only if
   Android refuses to move the task.
2. Use it in `SingleYouTubePage`'s Back handler instead of `finish()`.

## Acceptance criteria

- [x] Back with no earlier page moves the task to the back and does not finish the activity
  (`LeaveAppTest`).
- [x] If moving the task fails, the activity still closes, so Back never does nothing.
- [ ] Owner's phone: open a video, press Back until the app leaves, reopen it from recents or the
  launcher; the transcript is still there without "Finding the best caption track…".

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest`, including `LeaveAppTest` | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| Managed-device/emulator | Existing suite (no test drives system Back out of the app) | CI |
| Physical-device/manual | The scenario in the last acceptance criterion | Owner's phone |

## Risks / edge cases

- The app now stays in memory after Back, like after Home; Android can still reclaim it.
- Opened from a share, Back returns to the sharing app as before, with this app kept in recents.

## Release intent

`release:patch` (repository default for a bug fix); the number depends on merge order with the
open audit PRs and is an estimate until reserved.

## Implementation result

As planned.

## Validation result

- `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
  passed locally (Linux, JDK 21, Android SDK 36), including `LeaveAppTest`.
- Final-head CI and the owner's phone check: see the PR.
