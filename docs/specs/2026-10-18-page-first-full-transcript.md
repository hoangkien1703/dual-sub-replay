# Full transcript from the YouTube page first

## Status

Implemented, revised three times (physical-phone and live-YouTube checks pending). The first version
failed on the owner's phone; see [Revision 1](#revision-1-keep-the-players-own-download). Revision 1
then stopped on an empty track list; see
[Revision 2](#revision-2-no-dead-end-on-an-empty-track-list-and-the-transcript-panel).
[Revision 3](#revision-3-what-lingotube-does-differently) adds what LingoTube does differently. On 2026-10-18 the owner reported that the full dual-subtitle
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

## Revision 2: no dead end on an empty track list, and the transcript panel

### Problem

On 2026-10-18 the owner sent a screenshot (signed in to YouTube Premium, an English video on
`m.youtube.com`): live subtitles showed the video's English captions, and the sheet said "Full
transcript: YouTube lists no captions for this video" with "App download: Caption discovery stopped
after 20 seconds... Last stage: player request (Web embedded, youtubei.googleapis.com)". So the page
player had captions and had downloaded them, but `PageTranscriptSource.offerTracks` ended the request
the first time the page's player details listed no track (or no trusted track address), before the
kept copy of the player's download was ever looked at. Which of those it was is not known: the page's
reason did not say where it looked.

The owner asked for a lasting fix without another server or PC. Research on 2026-10-18 (sources in
the project thread) found that the tools that still work in September 2026 reuse the captions the
page's own player downloads: caption track addresses without the player's `pot` answer an empty 200,
`/youtubei/v1/get_transcript` answers `FAILED_PRECONDITION`, and requests from outside a browser get
the bot check. Revision 1's capture is that method; this revision removes the step that stopped it.
The owner was offered three options on a decision card (use the player's download; that plus
reading YouTube's own transcript panel as a last resort; minting our own tokens like NewPipe) and
picked "Plus transcript panel". Both parts are built here. The panel matters for videos whose player
never downloads captions (captions off, not played yet): one report from September 2026 found the
transcript panel still loading where hand-built requests were refused, because the panel's own
request comes from YouTube's code.

### Approach

1. `WEB_PAGE_CAPTION_TRACKS_SCRIPT` prefers a player response for the page's own video that lists
   tracks (the player's, else `ytInitialPlayerResponse`), resolves relative track addresses against
   the page, and, when the details list none, takes the tracks from the player's captions menu
   (`getOption('captions', 'tracklist')`, only when the player's video is the same) and from the
   tracks the capture already kept for that video. It installs the capture too and still changes
   nothing. It reports `found` counts per source and YouTube's playability status.
2. `PageTranscriptSource.offerTracks` takes a track without a trusted address (the job then waits for
   the player's own download), and keeps asking while the page lists no track. After
   `PAGE_NO_TRACKS_GRACE_MS` (8 s) it stops the provider's wait with `NO_TRACKS` but keeps watching,
   so a track the player lists or downloads later still gives a late transcript.
3. The sheet adds a "Page check" line with those counts and the job's last step (English diagnostic
   text, like the download error), so the next phone test shows which source was empty.
4. Transcript panel, last resort. When the job's caption window (`PAGE_CAPTION_SWITCH_MS`) ends
   without the track, or at once for a page that listed no track (`PageCaptionTarget.panelOnly`,
   set when the grace time ends), the job presses YouTube's own "Show transcript" button
   (`*-video-description-transcript-section-renderer button`), first unfolding the description
   when the button is not there. Each press gets time before the next, at most `PANEL_OPEN_TRIES`.
   The capture keeps the panel's `/youtubei/v1/get_transcript` reply (read-only, YouTube hosts,
   last four videos); if that is missed, the job reads the shown lines
   (`transcript-segment-view-model`, `ytm-`/`ytd-transcript-segment-renderer`). Afterwards it
   presses only the close button inside that panel. `parseTranscriptPanel` turns either form into
   lines (line timing only, no word timing). A panel-only target's language is a guess (the chosen
   or learned language, else English) replaced by the lines' writing system when it names one.

### Acceptance criteria

- [x] When the player's details list no track but the player downloaded one, the full transcript is
  built from that download (`PageTranscriptSourceTest`, headless Chromium run).
- [x] Tracks from the player's captions menu and relative track addresses are used
  (headless Chromium run).
- [x] A page with no track at all stops the wait after the grace time, keeps watching, and names
  where it looked (`PageTranscriptSourceTest`).
- [x] The track script only reads: no `fetch`, `setOption` or `loadModule`
  (`PageTranscriptScriptsTest`).
- [x] When no captions come, the job unfolds the description, opens the transcript panel, takes its
  reply or its shown lines, and closes the panel (`TranscriptPanelParserTest`,
  `PageTranscriptWebViewTest.youTubesTranscriptPanelIsTheLastResort`, headless Chromium run).
- [ ] On the owner's phone, the English video from the screenshot shows the full transcript.

### Validation result (revision 2)

Local runs on 2026-10-18 (JDK 21, Android SDK 36, no emulator).

| Check | Result |
| --- | --- |
| `PageTranscriptSourceTest`, `PageTranscriptScriptsTest`, `TranscriptPanelParserTest`, `PlaybackArchitectureTest` | Passed |
| Scripts in headless Chromium 1194 on an offline `m.youtube.com` page whose track answers only with the player's `pot`, player details without tracks: (a) player already downloaded the track: listed from the download, job done; (b) only the captions menu lists it: job switched captions on, took the player's download, done; (c) relative track address: resolved, done; (d) nothing anywhere: empty list, counts reported | Passed (scratch Playwright run, not committed) |
| Transcript panel in headless Chromium on an offline page with a folded description: (a) panel-only job: unfolded, opened, kept the `get_transcript` reply, closed, done in 4 s; (b) reply not seen: read the shown lines, done in 4 s; (c) normal job with captions never downloaded: panel after the caption window, done in 19 s | Passed (scratch Playwright run, not committed) |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| `PageTranscriptWebViewTest` (four tests, two new) | Not run locally (no emulator); runs in CI's `managed-device-tests` |
| Physical phone and live YouTube | Not run; owner's preview test. The panel's button and line selectors are taken from public reports, not checked on live `m.youtube.com` (the build machine gets YouTube's bot check) |

### Risks

- The panel is found by YouTube's element names, which change; the "Page check" line names the step
  it stopped at (`panel-missing`, `panel-opening`, `panel-reading`).
- Opening the panel unfolds the description on the page under the app's sheet; when the panel has
  no close button the app can find, it stays open.
- A video with no captions at all now waits the 8 s grace time (once) before the download's error
  shows, and the panel is tried once per attempt.

### Release intent

`release:patch` (default; no other intent from the owner).

## Revision 3: what LingoTube does differently

### Problem

Seeing the same screenshot as Revision 2 ("YouTube lists no captions" while live subtitles showed
the captions), the owner noted that LingoTube shows the full dual transcript of the same video and
asked why it works and ours does not. On 2026-10-19 the owner supplied LingoTube's APK (1.8.4,
`com.springwalk.lingotube`); it was decompiled with JADX and its injected `lingo.js` was decoded.
LingoTube uses the same WebView setup as ours (default user agent, third-party cookies, no request
interception, no requests to YouTube from its own code) and reads the same
`movie_player.getPlayerResponse()` track list. Revision 2 already covers most of the differences
(no dead end on one read, relative addresses, keep watching). Two remain:

- LingoTube reads the track list only once the page's `#player` element says `loading="false"` and
  `playable="true"`, and reads it again whenever that element changes. Revision 2 starts its 8 s
  no-tracks grace time on the first read, even while the player is still loading, and may give up
  (and go to the transcript panel) before the player has listed its tracks.
- LingoTube fetches the track's `baseUrl` exactly as YouTube gave it (default XML, no changed
  parameters), from the page. Ours only fetches a rewritten `json3` address before waiting for the
  player's own download.

Other LingoTube safety nets were seen but are out of scope: it can replace its whole `lingo.js`
through Firebase Remote Config (fixes without an app update), it asks its own server for captions
users saved, and its second line is YouTube's own translation (`&tlang=`).

The owner asked for the fix ("yes create PR for this") and chose to build it as a follow-up on top of
#130 (Revision 2) rather than a competing PR.

### Approach

1. `WEB_PAGE_CAPTION_TRACKS_SCRIPT` reports `ready`: false while `#player` has `loading` other than
   `false` or `playable` other than `true`; a page without those attributes counts as ready.
2. `PageTranscriptSource.offerTracks` does not start (and resets) the no-tracks grace time while the
   page is not ready; tracks listed while loading are still used. A request whose wait ends while
   the player is still loading reports `NO_PLAYER` ("the YouTube player isn't ready yet").
3. The page job tries the `json3` address first (it carries word timings), then the track's address
   unchanged with the page's default credentials, before waiting for the player's download. Only a
   reply with caption lines (`segs`, `<text>` or `<p>`) finishes the job, so YouTube's empty or
   malformed answer without a token does not end it.

### Acceptance criteria

- [x] Reads while the player is loading do not start the grace time or open the transcript panel,
  and tracks listed after loading are used (`PageTranscriptSourceTest`).
- [x] The track list reports readiness from `#player`'s `loading`/`playable` attributes, and the job
  tries the unchanged address on YouTube's `/api/timedtext` only after `json3` gave no lines
  (`PageTranscriptScriptsTest`, headless Chromium run).
- [ ] On the owner's phone, the English video from the screenshot shows the full transcript.

### Validation result (revision 3)

Local runs on 2026-10-19 (JDK 21, Android SDK 36, no emulator).

| Check | Result |
| --- | --- |
| `PageTranscriptSourceTest`, `PageTranscriptScriptsTest` | Passed |
| Scripts in headless Chromium 1194 on an offline `m.youtube.com` page: `#player` loading → `ready` false, then true; relative address resolved to `m.youtube.com`; `json3` answered empty, the unchanged address answered XML, job done with 2 requests; `example.com`: both scripts return null | Passed (scratch Playwright run, not committed) |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| `PageTranscriptWebViewTest` | Not run locally (no emulator); runs in CI's `managed-device-tests` |
| Physical phone and live YouTube | Not run; owner's preview test |

### Release intent

`release:patch` (default; no other intent from the owner). Merge after #130.
