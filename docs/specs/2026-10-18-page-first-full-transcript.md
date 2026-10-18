# Full transcript from the YouTube page first

## Status

Implemented, revised once (physical-phone and live-YouTube checks pending). The first version
failed on the owner's phone; see [Revision 1](#revision-1-keep-the-players-own-download). On 2026-10-18 the owner reported that the full dual-subtitle
transcript still does not load (PR #123's attempt did not fix it) and asked why other projects
still work. After the cause was explained in the project thread, the owner picked "Page first"
on the decision card: the YouTube page in the app's WebView fetches the whole caption track, and
today's download becomes the fallback. That authorizes this scope, not merge or release.

## Context / problem

- The app downloads captions with its own OkHttp requests ([`YouTubeCaptionProvider.kt`](../../app/src/main/java/com/kienhoang/dualsubreplay/data/YouTubeCaptionProvider.kt)):
  watch page, then Innertube `/youtubei/v1/player` with eight client profiles, then the
  track's `/api/timedtext` address. These requests carry no cookies, no visitor data and no
  proof-of-origin token (`pot`).
- On many networks YouTube now refuses such requests: the watch page redirects to
  `google.com/sorry`, players answer `LOGIN_REQUIRED` "Sign in to confirm you're not a bot",
  and web track addresses marked `exp=xpe` return an empty 200 without `pot`. Probes from the
  build machine on 2026-10-18 for a Chinese video: VISIONOS, ANDROID, WEB and TVHTML5 got the
  bot check, ANDROID_VR got HTTP 403, only IOS returned tracks. The owner's phone tests in #123
  recorded the same bot check.
- When the lookup fails the app falls back to live subtitles read from the page's shown
  captions, and the sheet says "Waiting for YouTube captions and their language".
- Projects that keep working run inside a real browser and reuse the page player's own caption
  request, which already carries `pot` and the browser's cookies (browser extensions), or run
  from clean home networks (yt-dlp, youtube-transcript-api, with PO-token plugins).
- #123 copied the page's caption requests, but only after the app's own lookup had failed and
  live mode had turned captions on, and through polling that could miss the moment; the copied
  `pot` was then used from OkHttp, a different client from the page.

## Goals

- When a video opens, the page in the existing WebView fetches the chosen caption track itself
  (same cookies, same `pot`, same browser as the playing video) and hands the document to the
  app, which builds the full transcript from it as before.
- The current download runs at the same time as the fallback; whichever succeeds first wins.

## Non-goals

- No second WebView, no hidden player, no change to how the transcript is built, translated or
  shown, no change to live subtitles.
- No PO-token generation in the app; no new network host.

## User-visible behavior

- Before: on refused networks the full transcript never loads; only live subtitles appear after
  the video plays with captions.
- After: the full transcript loads from the page within a few seconds of the video opening.
  While the page fetches it, YouTube's captions may be switched on for a moment (hidden by the
  app's overlay where it already hides them) and are switched back off. If the page cannot get
  the captions and the download fails too, behavior is unchanged (live subtitles).

## Technical constraints / invariants

- One WebView ([tech-stack invariants](../project/tech-stack.md)); page scripts re-check the
  `https:` + `*.youtube.com` origin before doing anything and only fetch `/api/timedtext` on a
  YouTube host; documents over 8 MiB are dropped (the provider's cap).
- The `CaptionProvider` boundary stays: a new `PageFirstCaptionProvider` wraps
  `YouTubeCaptionProvider`; `RecentCaptionTracks` keeps caching whatever wins.
- No `addJavascriptInterface`: results come back through `evaluateJavascript` polling like the
  playback snapshot.

## Proposed approach / plan

1. `data/PageTranscriptSource.kt`: the shared hand-off between the provider and the page. The
   provider registers a request (video, language choice) and waits; the page side reads the
   page player's track list, picks the track with the existing `selectCaptionTrack`, and later
   delivers the document or a failure. Requests for another video or after cancellation are
   ignored.
2. `data/PageFirstCaptionProvider.kt`: starts the page request and the download together and
   returns the first success; when both fail it throws the download's error, so messages and
   the live fallback stay as today.
3. `ui/YouTubeWebScripts.kt`: `WEB_PAGE_CAPTION_TRACKS_SCRIPT` (read-only track list) and
   `webPageTranscriptScript(target)`, an idempotent job per track: try the track's own address
   from the page; if that is empty, switch the page's captions to that track so the player makes
   its own `/api/timedtext` request, take that request's address (with `pot`) from the browser's
   resource timing list, switch captions back if the app turned them on, and fetch the track as
   `json3` from the page with the page's cookies. Each poll returns working, done (with the
   document) or failed.
4. `ui/PageTranscriptReader.kt`: while a request is pending for the video on the page, runs those
   scripts every 600 ms and hands results to `PageTranscriptSource`.
5. `AppViewModel` uses `RecentCaptionTracks(PageFirstCaptionProvider(...))`.

## Acceptance criteria

- [ ] With a page whose track address returns an empty body unless `pot` is present, and a
  download that fails, the app shows the full transcript (offline WebView device test).
- [ ] The page attempt and the download race; the first success wins, the page's failure falls
  back to the download, and both failing throws the download's error (unit tests).
- [ ] The page's track list picks the same track as the download would for the same response,
  and requests for other videos are ignored (unit tests).
- [ ] Page scripts keep the origin check and only fetch `/api/timedtext` on YouTube hosts
  (`PlaybackArchitectureTest`).
- [ ] Existing caption, live-subtitle and playback tests pass unchanged.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `PageTranscriptSourceTest`, `PageFirstCaptionProviderTest`, `PlaybackArchitectureTest` pass | JVM |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passes | Local + CI |
| Managed-device/emulator | `PageTranscriptWebViewTest`: offline page needing `pot`, failing download, transcript appears | CI `managed-device-tests` |
| Physical-device/manual | Open a Chinese and an English video on the preview APK; the full transcript appears without playing first | Owner's phone |
| Live YouTube | Same as above; cannot run from the build machine (YouTube answers with its bot check) | Owner's phone |

## Risks / edge cases

- YouTube's mobile player internals (`getPlayerResponse`, `loadModule`, `setOption`) can change;
  the download remains as fallback.
- If the page player only requests captions while playing, the page path waits for playback.
- The resource timing list can fill up on long sessions; the script also listens with a
  `PerformanceObserver` and raises the buffer size.

## Release intent

`release:patch` default (no explicit owner intent for this fix). #124 asks for 1.6.0; if it
merges first, this becomes 1.6.1.

## Implementation result

- As planned: `PageTranscriptSource`, `PageFirstCaptionProvider`, `PageTranscriptReader`,
  `WEB_PAGE_CAPTION_TRACKS_SCRIPT` and `webPageTranscriptScript`, wired under
  `RecentCaptionTracks` in `AppViewModel`. `availableLanguages` moved to a top-level helper so the
  page and the download name languages the same way.
- The provider waits up to 25 s for the page; the page job itself gives up 15 s after its first
  try, so a page that cannot help ends the wait sooner. A video whose page has no track ends it
  at once.
- No `fetch`/`XMLHttpRequest` wrapping (unlike #123): the page player's request is found in the
  resource timing list, which also covers requests answered through YouTube's service worker, which
  `shouldInterceptRequest` does not see.

## Validation result

Local runs on 2026-10-18 (JDK 21, Android SDK 36, no emulator: the cloud machine has no KVM).

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| `PageTranscriptSourceTest` (track choice, other videos ignored, no tracks, timeout, replaced request, race outcomes) | Passed (11 tests) |
| `PageTranscriptScriptsTest` (origin checks, only `/api/timedtext`, size cap, captions restored, reply parsing) | Passed |
| The transcript script in headless Chromium 1194 on an offline `m.youtube.com` page whose track answers only with `pot`: tried the track address (empty), switched captions on, took the page's own request, fetched `json3` with `pot`, switched captions back off, reported done | Passed (scratch Playwright run, not committed) |
| `PageTranscriptWebViewTest` (same offline page in the app's WebView, download refused, transcript loads) | Not run locally (no emulator); runs in CI's `managed-device-tests` |
| Physical phone and live YouTube | Not run; owner's preview test |

## Revision 1: keep the player's own download

### Problem

On 2026-10-18 the owner tested PR build 435 on a phone with a Japanese video: the sheet stayed on
live subtitles ("Translating live captions…") with "Retry full transcript". Live subtitles showing
the video's captions prove the page player had already downloaded the whole track, so the page
had the data and the first version failed to take it. Reading the code found these causes (the
phone gave no detail, so which one hit is not known):

- The page asked YouTube a second time, changing the player's address (format, sometimes host,
  with cookies), and any change could get an empty answer.
- The page job gave up 15 s after it started. Phone players often load captions only once the
  video plays, so the job could end before the player ever asked; live subtitles turned captions
  on later, after the job was over.
- "Retry full transcript" reused the failed job: jobs were kept per video on the page, so a retry
  got the old failure back at once.
- Only player requests carrying `pot` were used.
- The reason stayed in logcat, so phone tests could not say which step failed.

The owner picked keeping the player's own download, changed in this PR (#125).

### Approach

1. `WEB_CAPTION_CAPTURE_SCRIPT`, added as a document-start script (and run again inside every job
   for WebViews without document-start scripts), wraps `XMLHttpRequest` and `fetch` on YouTube
   pages. When a `/api/timedtext` reply for a YouTube host arrives, it keeps a copy of the text
   (skipping auto-translated `tlang` tracks, replies over 8 MiB, and keeping the last eight
   tracks). It never changes a request or a reply; `fetch` replies are read through `clone()`.
2. `webPageTranscriptScript` is one job per attempt (`PageCaptionTarget.attempt`): it fetches the
   track's own address from the page (enough where YouTube asks no token), then waits for the kept
   copy of the target track. For the first 15 s it switches the page's captions to that track so
   the player downloads it, and switches them back only if nobody changed them since. After that
   it keeps watching and also accepts the same language as another kind (auto-generated or not).
   A job never fails on its own; it reports its step (`page-address`, `no-player`,
   `waiting-for-captions`).
3. `PageTranscriptSource`: when the provider's 25 s wait ends, the request stays open as a late
   watch (polled every 1.5 s). A document that arrives later is kept and announced on
   `lateTranscripts`; `AppViewModel` reloads the video while live subtitles are on, and the next
   request uses the kept document. A retry is a new attempt. The reason the page gave nothing
   (`PageTranscriptProblem`) is recorded per video.
4. The live-subtitles sheet shows why the full transcript is missing: the page's reason (localized)
   and the app download's English error text.

### Acceptance criteria

- [x] The transcript is built from the page player's own download; the app sends no second request
  with the page's token (`PageTranscriptWebViewTest`, headless Chromium run).
- [x] Captions the player loads after live subtitles took over replace them with the full transcript
  without a tap (`PageTranscriptWebViewTest.captionsThePageLoadsLaterReplaceLiveSubtitles`,
  `PageTranscriptSourceTest`).
- [x] A retry starts a new attempt on the page (`PageTranscriptSourceTest`, `PageTranscriptScriptsTest`,
  headless Chromium run).
- [x] The capture only reads `/api/timedtext` replies on YouTube hosts, skips `tlang` and oversized
  replies, and does nothing on other sites (`PageTranscriptScriptsTest`, headless Chromium run).
- [x] The sheet names the page's reason and the download's error, in all 8 interface languages.
- [ ] On the owner's phone, a Japanese and an English video show the full transcript, at the latest
  a few seconds after playback starts.

### Validation result (revision 1)

Local runs on 2026-10-18 (JDK 21, Android SDK 36, no emulator: the cloud machine has no KVM).

| Check | Result |
| --- | --- |
| `PageTranscriptSourceTest`, `PageTranscriptScriptsTest` | Passed |
| Scripts in headless Chromium 1194 on an offline `m.youtube.com` page whose track answers only with `pot`: (a) captions switched on, player XHR kept, 2 requests in total (address without token, player's own), captions switched back off; (b) player loads captions only later (XHR `arraybuffer`): job waits, then finishes, and a retry (new attempt) finishes at once; (c) player uses `fetch(new Request(...))`: kept, `tlang` reply ignored; (d) `example.com`: nothing installed | Passed (scratch Playwright run, not committed) |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| `PageTranscriptWebViewTest` (both tests) | Not run locally (no emulator); runs in CI's `managed-device-tests` |
| Physical phone and live YouTube | Not run; owner's preview test |

