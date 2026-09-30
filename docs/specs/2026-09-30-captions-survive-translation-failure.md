# Original captions keep playing when translation fails

## Status

Implemented. Approved for implementation by the owner on 2026-09-30 ("do what you recommend",
after the review of the external Sol 6.1 audit, item 2 and the dead-code part of item 10). Local
checks pass; see the PR for the final-head CI.

## Context / problem

`translatePlaybackWindow` (`ui/PlaybackTranslation.kt`) follows playback, publishes the original
caption window and translates rows in one loop. Any exception from `translate()` ended the loop.
`AppViewModel.runStoredTranslation` then set `stage = ERROR`, closed the display store, and the
panel showed `CompactErrorPanel` instead of the rows. The only action was "Retry captions", which
reloads the video and fetches its captions again.

Realistic triggers:

- First use offline, or a translation model download slower than 45 s (full build,
  `MODEL_DOWNLOAD_TIMEOUT_MS`). The models load lazily on the first `translate()` call, after the
  first original window has been published, so the originals appear and are then replaced by the error.
- A caption language the translator does not support: `withSession` throws before the loop starts,
  so no original captions appear at all.
- Any later translator error (ML Kit task failure, Bergamot native error).

In the full build, `withSession` also kept the ML Kit client even when its model download failed, so
a retry within the same session would have skipped the download.

## Goals

- Original captions, scrolling, seeking and tap-to-replay keep working whatever happens to
  translation, including before the first translation and for unsupported pairs.
- Translation failures are reported separately from caption failures, with a retry that reuses the
  stored captions.
- A failing translator is not retried in a loop.
- If only an optional sentence-prefix translation fails, the whole sentence translation is kept and
  split proportionally.
- Cancellation still ends the loop; results for old videos, languages or seeks never overwrite
  current state (existing generation checks stay).
- Remove the unused `nearestUntranslatedBatch` / `TRANSLATION_PUBLISH_BATCH` helper and its tests.

## Non-goals

- No coordinator rewrite, no per-row retry scheduling, no new settings.
- Caption fetch/parse/store errors keep today's behavior (live-caption fallback or the error panel).
- The learning-player overlay gets the "Translation unavailable" placeholder but no retry button.

## User-visible behavior

- **Before:** offline first use or a failed model download shows original captions briefly, then
  replaces them with an error and "Retry captions"; an unsupported language shows only the error.
- **After:** the transcript stays. A bar above it says why translation stopped and offers
  **Retry translation**. Rows without a translation show "Translation unavailable" instead of
  "Translating…". The panel status reads "Original captions only · translation unavailable".
  Retry clears the bar and translates again near playback without refetching captions.

## Technical constraints / invariants

- One WebView/player; no change to navigation, caption provider or storage bounds.
- Keep the loop testable as an `internal` suspend function with plain JUnit4 tests.
- Do not swallow cancellation: a `CancellationException` ends the loop only if the loop's own
  coroutine is cancelled; a cancelled translator task (not this coroutine) is a translation failure.

## Proposed approach / plan

1. `CaptionPlaybackRequest.translationAttempt`: raised by retry; `updatePlaybackRequest` keeps it.
2. `translatePlaybackWindow`: catch translator errors, report them once through
   `onTranslationFailure`, and stop translating until `translationAttempt` changes while the window
   keeps following playback. Prefix failures fall back to proportional slicing.
3. `AppViewModel`: `translationError` in the UI state and `showTranslationUnavailable`. The panels'
   existing retry action (`retryCaptions`) raises `translationAttempt` when only translation failed
   (`onlyTranslationFailed`), and reloads the captions otherwise. Run the loop with an always-failing
   translator when the session cannot start (unsupported pair). Store/IO errors still go to the
   error panel.
4. Full build `withSession`: keep the ML Kit client only after its model is ready.
5. UI: `TranslationUnavailableBar` above the timeline in both panels; `pendingTranslationText` for
   rows and the overlay.
6. Delete the unused batch helper and its tests.

## Acceptance criteria

- [x] Failure before the first translation: originals publish, a later seek to 300,000 ms publishes
  that window, the translator is called once and the failure is reported once.
- [x] Failure mid-window keeps rows already translated.
- [x] Raising `translationAttempt` translates again with the same store.
- [x] A failed prefix keeps the whole sentence translation split across its rows.
- [x] Cancelling the loop is not reported as a failure; a translator-side `CancellationException`
  is, and the loop keeps running.
- [x] The panel shows the rows, "Translation unavailable" and a working "Retry translation".
- [x] Existing translation-window tests pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest`, including `TranslationFailureTest` | Local Linux, CI |
| Regression proof | With errors rethrown as before, the new loop tests fail | Local Linux |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| Managed-device/emulator | `SubtitleUiTest.translationFailureKeepsOriginalRowsAndOffersRetry` | CI managed device |
| F-Droid build | `fdroid-build` / `fdroid-device-tests` jobs (shared code path) | CI |
| Physical-device/manual | Airplane mode on first use of a new language pair: captions stay, bar shows, retry works once online | Owner's phone (not run) |
| Live YouTube | Same scenario on a real video | Owner's phone (not run) |

## Risks / edge cases

- After a failure, translation stays off until the user retries, even if the network comes back.
  This is deliberate (no hot loop) and visible through the bar.
- An unsupported pair shows the bar with the translator's message; retry fails the same way.

## Release intent

`release:patch` (repository default for a bug fix). Estimated v1.3.2 or v1.3.3 depending on merge
order with the other audit PRs; numbers are estimates until reserved.

## Implementation result

As planned. `translateRow` was extracted from the loop to keep it readable. Instead of a new
retry callback threaded through four composables, the bar calls the panels' existing retry action and
the ViewModel decides what to retry (`onlyTranslationFailed`); this keeps the long composables within
the complexity limits.

## Validation result

See the PR description for local commands and the final-head CI result. Physical-device and live
YouTube checks are not run.
