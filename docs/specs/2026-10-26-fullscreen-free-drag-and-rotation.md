# Free-moving fullscreen subtitles and landscape fullscreen

## Status

Implemented. In the "Free-moving subtitles and auto-rotate in fullscreen" project thread
(2026-10-26) Kien asked for two changes in one PR: the fullscreen dual-subtitle box should move
in any direction, and tapping Fullscreen should turn the video to landscape like the YouTube app.
After the first preview Kien also asked for the fullscreen box to be narrower and centered "like
YouTube does": the box sits in the middle and is only as wide as its text. Merge and release
stay with the owner.

## Context / problem

1. **The subtitle box moves along one axis per drag.** `LearningSubtitleOverlay` stacked two
   `Modifier.draggable` gestures, one vertical and one horizontal. Whichever passed its touch slop
   first took the whole gesture, so a diagonal drag only moved the box up/down or left/right.
2. **Fullscreen stays upright.** YouTube's fullscreen button only hands the app a fullscreen view
   (`WebChromeClient.onShowCustomView`); the app showed it in a dialog without asking for
   landscape, so a held-upright phone showed a small letterboxed video.

## Goals

- One drag moves the subtitle box up/down and left/right at the same time, in fullscreen and in
  the regular overlay, keeping the existing limits (the box stays fully on screen) and saving the
  position when the finger lifts.
- Tapping a word, tapping the box to show its buttons, and phrase selection keep working.
- In fullscreen the box is only as wide as its text (up to 80% of the screen, 720dp at most),
  its lines are centered, and it starts in the middle. The sideways position is saved under a
  new key (`overlay_side_position`), so a position saved for the old full-width box doesn't
  park the new narrow box far off to one side; everyone starts centered once.
- Fullscreen turns the screen to landscape (either side, following the phone) for wide videos.
  Upright videos (Shorts, phone recordings) stay upright.
- Leaving fullscreen turns the screen upright again if it was upright before, then follows the
  phone's rotation again once the phone is held upright (like the YouTube app, so a phone still
  held sideways doesn't flip straight back to landscape). If fullscreen started in landscape, the
  previous setting comes back at once.

## Non-goals

- A setting to turn the rotation off (can follow if wanted).
- Entering fullscreen by turning the phone sideways.
- Changing the regular (not fullscreen) overlay's size or alignment.

## Design

- `LearningPlayerRoot.kt`: the two `draggable`s become one `pointerInput { detectDragGestures }`
  that applies `dragAmount.y` through `overlayPositionAfterDrag` and `dragAmount.x` through
  `normalizeOverlayHorizontalPosition`, exactly as the two separate handlers did.
- `FullscreenRotation.kt`: `FullscreenVideoRotation(fullscreen, webView)` runs in
  `YouTubeBrowserScreen` while the fullscreen view is shown. It reads the playing video's size
  from the page; unless the video is taller than wide it sets
  `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`. On exit it restores the previous requested orientation,
  going through `SCREEN_ORIENTATION_PORTRAIT` plus an `OrientationEventListener` when the screen
  was upright before. When the player leaves the screen the orientation is restored at once.

## Acceptance criteria and validation

- Unit: `OverlayDragRegressionTest` covers the fullscreen line width limit (with and without the
  buttons shown). `FullscreenRotationTest` covers wide/upright/unknown video sizes and the upright angle
  range, and that only an activity handling rotation itself is turned (a test host that would be
  recreated keeps its orientation).
- Instrumented: `LearningPlayerUiTest.learningOverlayMovesDiagonallyInOneDrag` drags down-left in
  one gesture and checks both positions changed; `learningOverlayHidesActionsUntilTapped` still
  shows the buttons on a tap.
- Phone (owner): fullscreen turns to landscape for a normal video, stays upright for a Short,
  returns upright on exit; the subtitle box follows a diagonal drag.

## Follow-up

2026-10-27: turning the screen after fullscreen started left the page scrolled, which showed when
YouTube's settings menu opened; see [the follow-up spec](2026-10-27-translate-while-paused-and-fullscreen-menu.md).
