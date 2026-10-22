# Lighter CPU use around the YouTube page

## Status

Validated (offline and emulator; the owner's phone check is still open). On 2026-10-22 the owner asked how to make sure the app isn't too heavy on the
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
   process and WebView's page processes for 30 s playing, 30 s paused and 30 s on a page
   without a video, logs them, and fails over budget. CI prints the numbers in the job summary.

## Acceptance criteria

- [x] While playing, page reads stay every 100 ms and the karaoke clock every 33 ms.
- [x] Paused or not-started videos are read every 250 ms, pages without a video every 500 ms,
  and app-sent commands are read back at once.
- [x] No `requestVideoFrameCallback` loop; captions keep their `video.currentTime` timestamps.
- [x] Existing unit, WebView fixture and player tests pass.
- [x] `PageCpuUseTest` reports numbers for main (before) and this change (after) and stays
  within its budget.
- [x] Headless Chromium on the YouTube-like page shows less page-side work paused and without a
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

As planned. The CPU test went in first (commits 767c031 and 45b63d9) so CI measured main's code
before the fixes (0b62bbd). The first run rounded to whole milliseconds, which showed the paused
and no-video scenarios as 0, so the test now reports tenths and measures those for 30 s.
Budgets: 40 ms a second playing, 5 ms a second paused or without a video, about three times
what CI measured.

## Validation result

Cloud container: `formatCheck`, `complexityCheck`, `testDebugUnitTest` (including the new
`PagePollPacingTest`, the cache test in `LanguageAwareTokenizerTest` and the new
`PlaybackArchitectureTest` assertions), `lintDebug`, `assembleDebug` and
`assembleDebugAndroidTest` passed.

Headless Chromium (Playwright 1.56, mobile viewport, 4x CPU slowdown, canvas video stream,
YouTube-like page, the app's old and new scripts with the old and new pacing), page main thread
in ms a second, two runs each:

| Scenario | Script before | Script after | All page tasks before | All page tasks after |
| --- | --- | --- | --- | --- |
| Playing | 24.5, 25.6 | 9.2, 7.2 | 110.7, 111.9 | 79.9, 73.9 |
| Paused | 7.3, 5.4 | 7.3, 6.2 | 76.0, 69.5 | 55.1, 51.6 |
| No video | 0.0, 0.0 | 0.0, 0.0 | 33.8, 34.8 | 8.5, 9.9 |

"All page tasks" includes the test page's own work (canvas animation, time display), which did
not change; the app's share of it fell by about a third while playing and three quarters without
a video.

PR CI emulator (`PageCpuUseTest`, API 36 x86_64, swiftshader), CPU ms a second of the app's
process + WebView's page process:

| Scenario | main (45b63d9) | this change (0b62bbd) |
| --- | --- | --- |
| Playing | 6.1 + 9.3 = 15.4 | 4.6 + 8.7 = 13.2 |
| Paused | 0.4 + 1.0 = 1.4 | 0.3 + 1.0 = 1.3 |
| No video | 0.3 + 0.7 = 1.0 | 0.3 + 0.3 = 0.6 |

An earlier run on main (767c031, whole milliseconds) gave 11 playing, so runs vary by about a
third. Conclusion: on CI hardware the app's own work around the page is about 1.5% of one CPU
core while playing and close to nothing otherwise; the fixes trim it, but most of a phone's CPU
use while watching is YouTube's page and video decoding. Phones are slower than the CI host, so
expect a few times these numbers there.

Not verified: a physical phone and live YouTube (owner's phone check, steps in
[quality-and-performance.md](../quality-and-performance.md#cpu-use-around-the-youtube-page)).
Final-head CI and preview: see PR #144.
