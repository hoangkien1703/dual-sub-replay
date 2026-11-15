# Subtitles stay closed, and the bubble shows the app logo

## Status

Implemented (owner request 2026-11-15, release: patch). Emulator UI checks run in PR CI.

## Context / problem

In fullscreen, the X on the dual-subtitle box collapses it to the floating bubble. Leaving fullscreen
brought the transcript panel back, entering fullscreen again brought the box back, and opening another
video opened the panel again. The owner wants a close to be remembered until the app is fully closed.
The bubble showed a generic CC glyph; the owner wants the app logo from the README instead.

## Goals

- One "closed by the person" state for the panel and the box. It survives fullscreen on/off, rotation,
  another video and tab switches, and only the bubble (or picking a view in settings) undoes it.
- It lives in memory only (the activity's `AppViewModel`), never in preferences. Closing the app (swipe
  it away from Recents, or the system ending it) resets it. Back already leaves the app the way Home does
  (`leaveKeepingState`), so Back keeps it.
- The bubble and the menu header show the app logo instead of the CC glyph.

## Non-goals

- Changing the CC glyphs that mean "captions" elsewhere (language setup, TV home, panel headers).
- A setting for this; it is how closing works now.

## User-visible behavior

- Close the box in fullscreen, leave fullscreen: only the bubble shows; no panel.
- Enter fullscreen again or open another video: still only the bubble.
- Tap the bubble: the box shows (fullscreen) or the panel shows (portrait). After a tap in fullscreen
  the panel comes back when fullscreen ends.
- Closing the panel with its X in portrait is remembered the same way.
- The bubble shows the logo's dark player plate with the play triangle cut out, so it takes the bubble's
  accent color like the launcher icon. The menu header shows the logo as a rounded square in the accent color.

## Proposed approach / plan

- `DualSubUiState.subtitlesClosedByUser` (default false). `AppViewModel.closeSubtitles()` sets it and
  hides the panel; `reopenSubtitles(showPanel)` clears it.
- Automatic shows respect it: `showSubtitlePanel()` (restore after fullscreen/landscape) uses
  `withPanelShown()`, and `loadVideo` / `clearActiveVideo` open the panel only when it is not set.
- `LearningPlayerRoot`: the fullscreen box's X calls `closeSubtitles()`, replacing the old
  `fullscreenOverlayHiddenByUser` that reset when fullscreen ended. The non-fullscreen box (overlay
  view, landscape) shows the bubble while closed. `selectMode` reopens.
- `DualSubApp`: the panel X calls `closeSubtitles()`, the bubble calls `reopenSubtitles()`.
- New drawable `ic_app_logo_mark` (plate path from `ic_launcher_foreground` with the triangle as an
  even-odd hole) used by `MovableSubtitleFab` and the drawer header.

## Acceptance criteria

- [x] Closed in fullscreen stays closed after leaving and re-entering fullscreen and on another video
      (`SubtitlesStayClosedTest`).
- [x] The bubble reopens the panel and clears the state (`SubtitlesStayClosedTest`).
- [x] Automatic restore never reopens a closed panel (`SubtitlesClosedByUserTest`).
- [x] Nothing is stored in preferences; a new `AppViewModel` starts open.
- [ ] Bubble and menu header screenshots checked on the CI emulator.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `SubtitlesClosedByUserTest` | cloud / CI |
| Android lint/build | CI `verify-build` | CI |
| Managed-device/emulator | `SubtitlesStayClosedTest` (saves `subtitles-closed-fullscreen-bubble`, `subtitles-closed-portrait-bubble`); `NavigationRecoveryUiTest` saves `navigation-drawer` | CI |
| Physical-device/manual | Owner: close the box in fullscreen, leave, re-enter, switch video, then swipe the app away and reopen | owner's phone |

## Risks / edge cases

- A person who closed the panel and forgot may think subtitles broke; the bubble stays on the video,
  and the app resets once it is closed.
- Closing the non-fullscreen overlay view's box still switches to the panel view (unchanged).
