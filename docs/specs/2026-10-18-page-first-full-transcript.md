# Full transcript from the YouTube page first

## Status

Implemented (physical-phone and live-YouTube checks pending). On 2026-10-18 the owner reported that the full dual-subtitle
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
