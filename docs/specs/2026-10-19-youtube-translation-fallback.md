# YouTube's own translation when Google Translate fails

## Status

Implemented (physical-phone and live-YouTube checks pending). On 2026-10-19 the owner reported that
on home Wi-Fi translation works, but on 5G "the Google Translate cloud didn't work": Japanese rows
stayed on "Translating…" and then "Downloading translation model…". The owner asked for a fix that
works like LingoTube and, on the decision card, picked **"Fallback before on-device"**: Google stays
first; when Google is refused, the video uses YouTube's own translated captions, and on-device ML Kit
only translates what YouTube does not. That authorizes this scope, not merge or release.

## Context / problem

- `translation/GoogleWebTranslator.kt` calls Google Translate's unofficial web addresses
  (`translate.googleapis.com/translate_a/t` and `/single`, `clients5.google.com/translate_a/t`).
  Google refuses traffic it takes for automated queries with HTTP 403/429. Mobile networks put many
  phones behind a few shared public addresses (carrier-grade NAT), so the whole address gets
  refused while a home connection, with its own address, keeps working. That fits "Wi-Fi works,
  5G does not". The phone's top-right icon names the address and status; this was not seen yet.
- After a refusal the video moves to on-device translation, which first downloads two ML Kit models
  (tens of MB) over the same mobile connection: the screenshot's "Downloading translation model…".
- LingoTube 1.8.4 (decompiled 2026-10-19, see the
  [page-first spec, Revision 3](2026-10-18-page-first-full-transcript.md#revision-3-what-lingotube-does-differently))
  never calls Google Translate. Its second line is YouTube's own translation of the caption track:
  the same `/api/timedtext` address with `&tlang=<language>`, fetched from the YouTube page. That
  traffic goes to YouTube, from the page that plays the video, not to Google Translate's endpoints.

## Goals

- When Google is refused, the video's rows show YouTube's translation of the caption track within a
  few seconds, without downloading ML Kit models, whenever YouTube offers one.
- Rows YouTube's translation does not cover are translated on the device, as today.
- The top-right icon says YouTube's translation is in use, and Google is still checked again every
  2 minutes and on the next video.

## Non-goals

- No change while Google works; no new setting; no PO-token generation; no new host (only YouTube's
  `/api/timedtext` from the page, already allowed).
- Not a guarantee that Google works on every network; YouTube's translation can be missing for a
  track (`isTranslatable` false, or the language not in `translationLanguages`).

## User-visible behavior

- Before: Google refused → every row "Translating…", then on-device model download, then on-device
  translation.
- After: Google refused → rows stay visible while the page asks YouTube for its translation, then show
  it; a row YouTube left out translates on the device. The top-right icon reads "Using YouTube's
  translation" with the reason and "Try Google again".

## Technical constraints / invariants

- One WebView; page scripts re-check `https:` + `*.youtube.com` and only fetch YouTube's
  `/api/timedtext` (the source address passes `capture.timedText` before `tlang` is added).
- `WEB_CAPTION_CAPTURE_SCRIPT` still never changes a request; translated replies (`tlang`) are kept
  apart from original tracks so they are never taken for the transcript.
- No `addJavascriptInterface`: results come back through `evaluateJavascript` polling.
- User-visible strings in all 8 interface languages.

## Proposed approach / plan

1. `data/PageTranslationSource.kt`: one open request (video, source track, target language); waits
   up to 15 s; parses the document with `CaptionDocumentParser`; keeps the last result.
2. `ui/YouTubeWebScripts.kt`: capture keeps `tlang` replies in `capture.translations`;
   `webPageTranslationScript` finds the source track and YouTube's code for the target language in
   the page player's details, fetches the address with `tlang` (as `json3` with the page's cookies,
   then as YouTube gave it, as LingoTube does), otherwise switches the player's captions to the
   translated track for up to 15 s so the player downloads it, and switches them back.
3. `ui/PageTranscriptReader.kt`: `PageTranslationReader` polls while a request is open.
4. `ui/YouTubeTranslation.kt`: `youTubeRowTranslations` gives each translated cue to the row it
   overlaps most and builds the sentence and row-prefix texts the window translates.
5. `AppViewModel.runStoredTranslation`: in the Google fallback, asks the page in parallel and
   translates each row with Google's cache, then YouTube's lines, then the device;
   `DualSubUiState.youTubeTranslationFallback` drives the icon and the assistant's status.

## Acceptance criteria

- [x] A YouTube translation document becomes cues; "not translatable" ends at once; no answer times
  out; another video's or a replaced request's answers are ignored (`PageTranslationSourceTest`).
- [x] Translated cues map to the rows they overlap most, sentences and their row prefixes join their
  rows' cues, Japanese/Chinese join without spaces, rows without cues are left for the device
  (`YouTubeTranslationTest`).
- [x] The translation job stays on YouTube, adds `tlang` only after the address check, falls back to
  the player's own download and switches captions back; the capture keeps translations apart
  (`PageTranscriptScriptsTest`, headless Chromium run).
- [x] Existing translation, caption and playback tests pass.
- [ ] On the owner's phone on 5G, a Japanese video shows Vietnamese lines from YouTube when Google is
  refused, and the icon says so.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `PageTranslationSourceTest`, `YouTubeTranslationTest`, `PageTranscriptScriptsTest` pass | JVM |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passes | Local + CI |
| Managed-device/emulator | Existing WebView and UI tests pass | CI `managed-device-tests` |
| Script behavior | Headless Chromium on offline `m.youtube.com`: `tlang` address answers; only the player's download answers; language not offered; `example.com` | Scratch Playwright run |
| Physical-device/manual | 5G, a Japanese video, target Vietnamese: lines appear from YouTube | Owner's phone |
| Live YouTube | Same; cannot run from the build machine (YouTube's bot check) | Owner's phone |

## Risks / edge cases

- YouTube's translation is machine translation cut at caption boundaries, so a sentence row can
  read less naturally than Google's sentence translation; it is only used while Google is refused.
- Translated tracks may need the player's proof-of-origin token; then only the player's own download
  works, which needs the player to load captions (usually within seconds, sometimes only while the
  video plays). Rows still waiting fall back to the device after 15 s.
- The mobile player's `translationLanguage` option shape is YouTube's internal API and may change.

## Release intent

`release:patch` (default; no other intent from the owner).

## Implementation result

As planned. Deviation: the on-device session opens at once (its models download only when a row
needs them), and each row waits for YouTube's answer first, so the rows stay visible while the page
is asked.

## Validation result

Local runs on 2026-10-19 (JDK 21, Android SDK 36, no emulator).

| Check | Result |
| --- | --- |
| `PageTranslationSourceTest`, `YouTubeTranslationTest`, `PageTranscriptScriptsTest`, `PageTranscriptSourceTest` | Passed |
| Headless Chromium 1194 on offline `m.youtube.com`: (a) `json3` with `tlang` empty, the address as given with `tlang` answered XML: done in 0.3 s, no caption switch; (b) both empty, the player's own `tlang` download with `pot` kept: done in 0.6 s, captions switched to the translated track and back; (c) language not in `translationLanguages`: unavailable at once, no request; (d) `example.com`: null | Passed (scratch Playwright run, not committed) |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed |
| Physical phone and live YouTube | Not run; owner's preview test |
