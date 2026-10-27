# Translate the current minute while paused

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

## Release intent

`release:patch` (default for app behavior fixes).

## Implementation result

As planned.

## Validation result

Unit tests listed above passed in the cloud container. Phone check pending on the preview build.
