# Translate the current minute while paused, and the fullscreen menu shift

## Status

Implemented. In the "Fix fullscreen settings glitch and paused translation" project thread
(2026-10-27) Kien reported that after opening a video or changing its quality, a paused video
showed "Translating…" under every line until he pressed play, and asked for the translation to
show as soon as possible even while paused ("just the first minute, for example"). Merge and
release stay with the owner.

## Context / problem

`nextWindowTranslation` returned nothing while `CaptionPlaybackRequest.paused` was true, so
`translatePlaybackWindow` slept until playback started and the status line read "Paused ·
translation resumes on play". A video opened from a link does not autoplay
(`mediaPlaybackRequiresUserGesture`), and a quality change reloads the video, so both left the
panel full of "Translating…" rows.

## Goals

- While paused, the playback window is translated as it is during playback: the current row
  first, sending the next minute's sentences with it in one request
  (`upcomingTranslationTexts`, `PREFETCH_AHEAD_MS`), then the rest of the window (60 s ahead,
  30 s behind), which the engine then finds cached.
- The background look-ahead of minutes 2 to 5 (`prefetchInBackground`) still runs only while the
  video plays, so a paused video sends one request per position, not a stream.

## Non-goals

- Translating the whole transcript up front.
- Translating before the page reports a playback position (`playbackKnown`) or while the app is
  in the background (`CaptionPlaybackRequest.enabled` is unchanged).

## User-visible behavior

Before: paused rows read "Translating…" and the status line "Paused · translation resumes on
play" until play. After: the rows around the paused position fill in within one Google request;
the status line shows the usual "Preparing nearby translations" / "Subtitles ready". The
"Paused · translation resumes on play" text is removed from every language.

## Technical constraints / invariants

[Translation invariants](../../AGENTS.md#architecture-invariants) are unchanged: the same
engine, retries, quick switch and on-device fallback apply; failures still stop translation
until Retry (`translationAttempt`).

## Proposed approach / plan

1. `ui/PlaybackTranslation.kt`: `nextWindowTranslation` no longer returns null for a paused
   request.
2. `ui/AppViewModel.kt`: drop the paused status branch; remove `status_paused_translation_resumes`.
3. Tests: replace the pause test in `CaptionTranslationCancellationTest` with one that a paused
   video translates the next minute and then sleeps; a paused, enabled request starts work while an
   unknown position still does not.

## Acceptance criteria

- [x] A paused request translates the current row first and every row starting within the next
  minute, then sleeps until the position changes
  (`CaptionTranslationCancellationTest.pausedVideoTranslatesTheNextMinuteAndThenSleeps`).
- [x] An unknown playback position (disabled request) starts no work
  (`unknownPlaybackDoesNotStartWorkButAPausedVideoDoes`).
- [x] Background prefetch of minutes 2 to 5 still stops while paused (`BackgroundPrefetchTest`).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` for `CaptionTranslationCancellationTest`, `TranslationFailureTest`, `BackgroundPrefetchTest` pass | Cloud container and PR CI |
| Android lint/build | CI `verify-build` (lint catches a leftover reference to the removed string) | PR CI |
| Physical-device/manual | Open a video without playing it, and change quality while paused: lines translate within a few seconds | Owner's phone (preview build) |

## Risks / edge cases

- Scrubbing while paused sends one request per position it settles on, as playing does after a
  seek.
- With the on-device engine, a paused video spends a moment of CPU translating its window once.

## Part 2: YouTube's settings menu in fullscreen

### Problem

In fullscreen, tapping YouTube's gear showed the video pushed up: only its bottom ~40% (with the
controls and the menu) stayed on screen and the rest was black. Kien checked that it does not
happen when the phone is already sideways before tapping Fullscreen, so it comes from the turn to
landscape added on 2026-10-26 ([spec](2026-10-26-fullscreen-free-drag-and-rotation.md)). The
shift in his screenshot is about half the difference between the portrait and landscape screen
heights. A first guess (2026-10-27) took that for a leftover page scroll and reset it with
`window.scrollTo(0,0)`; it did not help, and the 2026-10-28 debugging showed the page never
scrolls (`scrollY` stays 0), so that script is removed.

### Cause (found 2026-10-28 on the laptop emulator with WebView DevTools)

It depends on the WebView version, not on ColorOS: an Android 16 emulator with WebView 133 never
showed it, an Android 17 emulator with WebView 149 did every time (Kien's OnePlus Ace 5 has
WebView 156). Numbers from the page (CSS px, Pixel 7 size, 2.625 px per CSS px):

| Moment | `innerWidth`×`innerHeight` | `visualViewport` width×height | `visualViewport.offsetTop` |
| --- | --- | --- | --- |
| Upright, Fullscreen tapped | 411×914 | 411×914 | 0 |
| After the app turns the screen | 914×411 | **914×0** | 0 |
| After tapping the gear | 914×411 | 914×0 | **234** (614 device px) |
| Already sideways, Fullscreen, gear | 914×411 | 914×411 | 0 |

The Android side was correct throughout: the Dialog window and the fullscreen view were
2400×1080 at (0,0) and the IME inset was 0. Since M139, WebView shrinks the visual viewport by
the part of the fullscreen view that hangs below the window
([WebView insets doc](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/insets.md);
`AwDisplayCutoutController.recalculateBottomImeInset`: `viewBottom − (windowHeight − ime)`), but
it recalculates only when insets reach the page's own WebView (in the activity window) or the
fullscreen view moves. Turning the screen gives the activity its landscape insets while the
Dialog still has the portrait size, so the view counts as 2400 px tall in a 1080 px window: the
inset is 1320 px (503 CSS px, more than the 411 px height) and the visible height becomes 0. The
view then gets its landscape size without moving, so nothing recalculates. YouTube's menu focuses
its first item and Chromium scrolls the 0-high visual viewport down to it, which draws the video
pushed up with black below. Re-laying out the Dialog, re-applying the Dialog's insets, a real
1 px resize and re-attaching the view all left the height at 0; asking the page's WebView for
insets again restored 914×411.

### Fix

`RemeasureFullscreenInsets` (`ui/FullscreenRotation.kt`) listens to layout passes of the
fullscreen view and calls `webView.requestApplyInsets()` whenever its size changes
(`fullscreenViewResized`), so WebView measures it again with the new size. On the emulator the
visible height came back 29 ms after the turn, and the gear menu opened over the whole video.

## Release intent

`release:patch` (default for app behavior fixes).

## Implementation result

Part 1 as planned. Part 2: the scroll reset was replaced by the insets fix above.

## Validation result

Unit tests listed above and `FullscreenRotationTest` passed in the cloud container.
Part 2 on the Android 17 / WebView 149 emulator (2026-10-28): upright → Fullscreen → gear opens
the menu over the full video (`visualViewport` 914×411, offset 0); already sideways → Fullscreen
→ gear unchanged; leaving fullscreen from either orientation restores the watch page. Phone
check (WebView 156) pending on the preview build.
