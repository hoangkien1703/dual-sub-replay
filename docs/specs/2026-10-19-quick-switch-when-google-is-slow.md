# Quick switch to on-device translation when Google is slow

## Status

Implemented (physical-phone check on 5G pending). On 2026-10-19 the owner reported that Google
Translate still does not work on 5G (it works on home Wi-Fi) and asked for the app to switch after
about 2 seconds on mobile data (4 on Wi-Fi) while it keeps trying Google in the background, so users
always have a line, at least from the device. On the decision card the owner picked **"Switch +
setting"**: a line already showing the device's translation changes to Google's once Google answers,
and Settings → Translation gets a "Wait for Google" choice. The owner asked for it in the same PR as
the [YouTube translation fallback](2026-10-19-youtube-translation-fallback.md) (#132). That
authorizes this scope, not merge or release.

## Context / problem

- The owner's 5G screenshot reads "Google Translate could not be reached
  (translate.googleapis.com/translate_a/t)": the phone could not connect, Google did not refuse it.
- `GoogleWebTranslator` waits up to 15 s per try and retries 3 times (1 s, 3 s, 7 s), so a row can
  show "Translating…" for about a minute before the video moves to on-device translation.
- An address that cannot be reached throws at once; only a refusal (403/429) hands over to the next
  address, so `clients5.google.com` is never tried when `translate.googleapis.com` is unreachable.
- Every load clears the fallback, so each new video waits for Google all over again.
- On-device models download only when the device first translates, often over the same 5G link.

## Goals

- A row waits for Google at most the chosen time (Automatic: 2 s on mobile data, 4 s on Wi-Fi);
  then the video translates without Google (YouTube's translation, then the device) at once.
- Google's request keeps going in the background; as soon as it answers, the video goes back to
  Google and the shown lines change to Google's translation.
- After Google failed or was slow on mobile data, the next video starts without Google and checks
  Google in the background as soon as it plays; on Wi-Fi each video tries Google first, as before.
- An address that cannot be reached hands over to the next address at once.
- While Google works on Wi-Fi, the on-device model for the video's languages downloads in the
  background (only while "Preload translation models in background" is on), so a later switch on
  mobile data needs no download.

## Non-goals

- No change to Google's addresses, batching, retries or the 2-minute recheck after a real failure.
- No change to the on-device engine choice or to YouTube's translation fallback.

## User-visible behavior

- Before: Google slow or unreachable → "Translating…" for up to about a minute, then the device.
- After: "Translating…" for at most the chosen wait, then YouTube's or the device's line; when
  Google answers, the lines change to Google's. The top-right icon explains the switch while it lasts.
- Settings → Translation → Wait for Google (shown while Google Translate is on): Automatic
  (default), 2 s, 4 s, 8 s. The AI assistant reads and may change it (`google_wait`).

## Technical constraints / invariants

- Diagnostic text in `translation/` stays English; new strings in all 8 interface languages.
- One WebView; no new host. `ACCESS_NETWORK_STATE` (a normal permission) tells Wi-Fi from mobile data.
- Obsolete loads must not change state; the background request belongs to the load and is cancelled
  with it.

## Proposed approach / plan

0. Rows wait for YouTube's translation (#132) at most 1 s after the switch.
1. `translation/GoogleWait.kt`: the setting (`auto`, `2`, `4`, `8`) and `googleWaitMs(wait, onWifi)`;
   `awaitWithin` runs a request in a scope that outlives the row and waits at most the wait.
2. `GoogleWebTranslator.request`: an unreachable address (`unreachable` flag) hands over like a
   refusal; when every address failed and one was unreachable, that (retryable) error is thrown.
3. `AppViewModel`: `followStoredTranslation` owns a supervisor scope for the load. The Google branch
   wraps the window's batch and row requests in `awaitWithin`; a timeout moves the video off Google
   (`onDeviceFallback`) with a "did not answer within" detail and watches the request: success goes
   back to Google, failure replaces the detail. An engine-only restart keeps the shown rows.
   `googleFailedOnMobile` makes the next load on mobile data start without Google and check it as
   soon as a row plays. On Wi-Fi the Google branch warms the on-device model.
4. Settings row, state, preference, reset, AI setting `google_wait`, strings in 8 languages.

## Acceptance criteria

- [x] `googleWaitMs`: Automatic is 2 s on mobile data and 4 s on Wi-Fi; fixed choices ignore the
  network; unknown stored values read as Automatic (`GoogleWaitTest`).
- [x] `awaitWithin` returns a fast answer, rethrows a fast failure, returns null after the wait while
  the request keeps running and still completes (`GoogleWaitTest`).
- [x] An unreachable address hands over to the next one; all unreachable stays retryable
  (`GoogleWebTranslatorTest`).
- [x] The AI assistant lists, reads and changes `google_wait`; Reset all settings clears it.
- [x] Settings → Translation shows Wait for Google only while Google is on (`OnlineTranslationUiTest`, CI).
- [x] Existing translation, settings and assistant tests pass.
- [ ] Owner's phone on 5G: a line appears within about 2 s; on Wi-Fi Google's lines show as before.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `GoogleWaitTest`, `GoogleWebTranslatorTest`, AI settings tests pass | JVM |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Local + CI |
| Managed-device/emulator | Existing UI tests pass | CI `managed-device-tests` |
| Physical-device/manual | 5G and Wi-Fi, a Japanese video to Vietnamese | Owner's phone |

## Risks / edge cases

- A network where Google answers in 3 s on mobile data switches once per video: the first batch
  times out, then returns and the video goes back to Google with the next minute already cached.
- "Automatic" treats anything that is not Wi-Fi or Ethernet (including a VPN over mobile data) as
  mobile data.

## Release intent

`release:patch` (default; same PR as #132).

## Implementation result

As planned, with two additions:

- After a switch away from Google, rows wait for YouTube's own translation (#132) at most 1 s
  (`YOUTUBE_TRANSLATION_WAIT_MS`, was up to 15 s), so the switch adds little waiting; a later YouTube
  answer still serves the rows after it.
- Choosing an engine in Settings, or "Try Google again", clears the "failed on mobile data" memory, so
  Google is tried first again.

## Validation result

Local runs on 2026-10-19 (JDK 21, Android SDK 36, no emulator).

| Check | Result |
| --- | --- |
| `GoogleWaitTest` (7), `GoogleWebTranslatorTest` (2 new), `AiActionsTest`, `AiAssistantActionsTest`, `SubtitleHighlightTest` | Passed |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed (810 unit tests) |
| `OnlineTranslationUiTest` (2 new: Wait for Google chips) | Not run locally (no emulator); CI `managed-device-tests` |
| Physical phone on 5G and Wi-Fi | Not run; owner's preview test |
