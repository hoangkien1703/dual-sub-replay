# Lighter CPU use around the YouTube page

## Status

Implemented. On 2026-10-22 the owner asked how to make sure the app isn't too heavy on the
phone's CPU (project thread "Phone CPU usage"). After a code review of likely hotspots, the
owner picked "Fixes + CI CPU check": fix the four spots found, add a CI check with a CPU budget,
and give phone steps to compare before and after. That authorizes this scope, not merge or
release.

## Context / problem

Most of the CPU while watching goes to YouTube's own page and to decoding the video, which the
app cannot change. On top of that the app adds its own work. Reading the code (main at
5517b02) found:

1. **Snapshot poll every 100 ms on every YouTube page.** `WEB_PLAYBACK_SNAPSHOT_SCRIPT` ran ten
   times a second also on the home feed, search and channel pages (no video) and while a video
   was paused. Each run also ran the caption track sync (`sync.run()`, whose spoken-language
   guess scans the title and description with a dozen script patterns when the video has no
   auto-generated track) and `ensureCaptions()` (a document-wide caption button search, also
   when captions were already on).
2. **A callback on every video frame whose result nothing reads.** The live caption script's
   `frameLoop` used `requestVideoFrameCallback` 30 to 60 times a second only to store
   `state.latestMediaSecond`; captions have been timestamped from `video.currentTime` since the
   fullscreen clock fix.
3. **The karaoke clock ticked every 33 ms** also while paused or before a video played.
4. **Word splitting on the screen thread.** Subtitle rows run the Japanese, Chinese or English
   analyzer inside `remember` while composing; a row scrolled back into view ran it again.

Also: the media-session poll (once a second) ran its script on pages without a video.

Already fine: the 5-minute look-ahead translation (one request every 3 s, never while paused),
screenshots for the AI assistant (only when it asks), and all polling stops when the app leaves
the screen (except the media-session poll that background play needs).

## Goals

- Keep the page reads the word highlight needs while a video plays, and do less when nothing
  moves: paused, not started, or no video.
- Remove work whose result is unused.
- Keep the screen thread from running the language analyzers for rows it shows often.
- A CI check that measures CPU time on a fixed offline page, so later features cannot quietly
  make the app heavier.

## Non-goals

- Changing YouTube's page, video decoding, or the single-WebView polling bridge (an
  architecture invariant).
- Changing timing behavior while a video plays (100 ms reads, 33 ms karaoke clock, 250 ms
  staleness limit).
- A user-facing battery saver setting.

## User-visible behavior

No visible change. While a video plays, highlights move exactly as before. While paused, the
app notices a play, pause or seek made on YouTube's own controls within about a quarter
second (was a tenth); a play, pause or seek the app sends itself (tapping a line, earphone
buttons, the media notification) is read back at once.

## Technical constraints / invariants

- One WebView and the polling bridge stay ([AGENTS.md](../../AGENTS.md#architecture-invariants-enforced-by-tests)).
- Scripts keep re-checking the executing origin; `PlaybackArchitectureTest` asserts literal text.
- `PLAYBACK_CLOCK_STALE_MS` (250 ms) stays; while paused the clock may report no position
  between reads, which only skips ticks that would repeat the same position. Study play modes
  never act while paused (`StudyPlaybackDriver`).
- `LanguageAwareTokenizer.tokenize` keeps its synchronous API; results are cached by text,
  language and the analyzers' revision, so lines split by the stand-in heuristic before an
  analyzer loaded are split again afterwards.

## Proposed approach / plan

1. `ui/PagePollPacing.kt`: snapshot reads every 100 ms while a video plays (buffering counts as
   playing), 250 ms while paused or not started, 500 ms on pages without a video; the karaoke
   tick every 33 ms while playing, else 100 ms. A URL change and every script the app sends
   (replay, play, pause, speed) trigger a read at once.
2. `YouTubeWebScripts.kt`: remove `frameLoop` and `latestMediaSecond`; `ensureCaptions()`
   returns before the caption button search once captions are on; `sync.choose()` works out
   the spoken language once per video and track list.
3. The media-session poll runs its script only on watch pages (other pages already published
   nothing).
4. `LanguageAwareTokenizer`: a 512-line cache; `PrepareUpcomingTokens` splits the 12 rows from
   the playing line on a background thread while word learning is on.
5. `PageCpuUseTest` (managed device): the complete player on an offline YouTube-like page
   (150 feed items, time display every 250 ms, a caption every 2 s, 300 transcript lines with
   word learning and word highlight on). It measures CPU milliseconds a second of the app's
   process and WebView's page processes for 30 s playing, 20 s paused and 20 s on a page
   without a video, logs them, and fails over budget. CI prints the numbers in the job summary.

## Acceptance criteria

- [ ] While playing, page reads stay every 100 ms and the karaoke clock every 33 ms.
- [ ] Paused or not-started videos are read every 250 ms, pages without a video every 500 ms,
  and app-sent commands are read back at once.
- [ ] No `requestVideoFrameCallback` loop; captions keep their `video.currentTime` timestamps.
- [ ] Existing unit, WebView fixture and player tests pass.
- [ ] `PageCpuUseTest` reports numbers for main (before) and this change (after) and stays
  within its budget.
- [ ] Headless Chromium on the YouTube-like page shows less page-side work paused and without a
  video, and no more while playing.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`, including `PagePollPacingTest`, `PlaybackArchitectureTest`, `LanguageAwareTokenizerTest` | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container / PR CI |
| Managed-device/emulator | All instrumented tests, including `PageCpuUseTest` (before = main code, after = this change) | PR CI `managed-device-tests` |
| Performance (baseline/target) | Headless Chromium, mobile viewport, 4x CPU slowdown, 20 s per scenario on the YouTube-like page with a real (canvas) video stream: script, style+layout and total task time a second, old vs new scripts and pacing | Cloud container (Playwright) |
| Physical-device/manual | Owner compares Settings › Battery usage after 30 min, or `adb shell top` with the app and WebView's sandboxed process, on the released app vs the preview | Owner's phone |
| Live YouTube | Not measurable from the cloud (Google bot check) | Owner's phone |

## Risks / edge cases

- A play started from YouTube's own controls while paused is noticed up to 250 ms later.
- The tokenizer cache holds at most 512 lines' tokens (a few hundred KB).
- Emulator CPU numbers are noisy and include software rendering; the budget has headroom and
  is a guard against large regressions, not a precise measurement.

## Release intent

`release:patch` (default; no owner instruction otherwise). Performance work with no visible
change.

## Implementation result

To be filled after CI.

## Validation result

To be filled after CI.
