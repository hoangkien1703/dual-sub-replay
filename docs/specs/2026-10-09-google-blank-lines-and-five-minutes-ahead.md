# Keep Google's good lines and translate five minutes ahead

## Status

Implemented. On 2026-10-09 the owner reported that Google Translate "stop working
after watching video for like 30 minutes" and asked to discuss first. On a decision card they chose
**Keep good lines**, then asked whether translating 5 minutes ahead would still work with Google and
said "do all of this in one PR", covering both the blank-line fix and the background five-minute
look-ahead Claude suggested. This does not authorize merging or publishing.

## Context / problem

- The owner's screenshot shows the on-device fallback with the detail "Google Translate returned an
  empty translation."
- That message comes from `parseGoogleBatchTranslation` (or `parseGoogleTranslation` for the
  one-text endpoint): Google answered HTTP 200, but at least one line in the reply was blank.
- The app sends the next minute of subtitles, up to 128 texts, in one request
  ([fewer-requests revision](2026-10-05-opt-in-google-translate.md#one-minute-ahead-2026-10-06)).
  One blank line throws away the whole reply.
- The error is neither `retryable` nor `blocked`, so it skips the 1 s / 3 s / 7 s retries and the
  other Google addresses, and the video moves to on-device translation at once.
- The 2-minute recheck sends only the current row, which works, so the app returns to Google, sends
  the same batch again and can fall back again.
- Live checks from the dev machine on 2026-10-09 (POST to `translate_a/t` with `client=gtx`,
  `clients5.google.com/translate_a/t` and `translate_a/single` with `client=at`): every batch of
  about 100 Japanese and English subtitle lines into Vietnamese came back complete, including ♪,
  [音楽], emoji and lone punctuation. The batch endpoint treats text as HTML, so a caption that is
  only `&nbsp;` comes back as a no-break space, which is blank after trimming. The likely trigger on
  the phone is Google answering with partial results after sustained use (inferred, not reproduced).
- The detail line does not say which Google address failed, so a screenshot cannot tell the causes
  apart.

## Goals

- Keep every line Google translated, even when other lines in the same reply are blank.
- Ask Google again only for a blank line, when playback needs it, on each Google address in turn.
- Translate a line on the device only if it is still blank; the rest of the video stays on Google.
- Name the Google address and HTTP status in the problem details.
- Translate five minutes ahead: the next minute as today, and minutes 2 to 5 in the background,
  one request at a time with a pause before each, so there is no burst.

## Non-goals

- No change to the endpoints, batch limits, retry delays, the 2-minute recheck, or the per-video
  fallback for real failures (no network, a block on every address, 5xx after the retries).
- No new setting or UI; no change to the F-Droid build, which never contacts Google.
- No change to row splitting or translation slicing.

## User-visible behavior

- **A reply with some blank lines:** the other lines show Google's translation. When playback
  reaches a blank line, that line alone goes to Google again (the address that last worked, then the
  others). If it is still blank, only that line is translated on the device (downloading the model
  first if needed, with the usual status). No problem icon appears, and the next line uses Google.
- **A reply where every line is blank** (more than one line sent): treated like a refusal by that
  address. The next address is tried; if every address refuses or answers blank, the video falls
  back to on-device as today, with the 2-minute recheck.
- **Problem details:** the English detail names the address, for example
  "Google Translate returned HTTP 503 (translate.googleapis.com/translate_a/t)." or
  "Google Translate refused every address: translate.googleapis.com/translate_a/t HTTP 429, …".
- **Five minutes ahead:** while a video plays with Google, minutes 2 to 5 are translated in the
  background, one request of at most 128 lines every 3 seconds, so a jump within the next five
  minutes, or a Google outage, finds those lines already translated. Nothing is sent while paused,
  and a text is sent at most once per load. A background failure waits 2 minutes and never moves the
  video to on-device; only the playing minute decides that.
- Word cards, live captions and the phrase bar: a blank Google answer translates that text on the
  device without moving the video to on-device.

## Technical constraints / invariants

- [On-device fallback and recheck](../project/tech-stack.md#architecture-invariants) stay as the
  [quiet-fallback revision](2026-10-05-opt-in-google-translate.md#revision-quiet-fallback-and-a-top-right-icon-2026-10-06)
  describes. Diagnostic exception text stays English.
- `BuildConfig.ONLINE_TRANSLATION` is false in the F-Droid build, so none of this runs there.
- The on-device engine for a blank line is opened only when needed, so a language pair ML Kit cannot
  translate still uses Google for every other line.
- Unit tests stay plain JUnit4 with the local OkHttp interceptor; nothing contacts Google.

## Proposed approach / plan

1. `GoogleWebTranslator`:
   - `parseGoogleBatchTranslation` and `parseGoogleTranslation` return `null` for a blank line
     instead of throwing; non-JSON, web pages and a wrong count still throw.
   - `sendAll` treats a multi-line reply with every line blank as a refusal (`blocked`), and tags
     every error with the address (`GoogleEndpoint.label`). HTTP errors carry `status`, and a block
     on every address lists each address and status.
   - `translateAll` returns `List<String?>` and caches only real translations. `translate` returns
     `String?`: a blank line is sent alone to every other address before it returns `null`.
   - `responds` is false when the probe comes back blank.
   - New `translateNextBatch`: one batch of the texts not cached yet, no retries; returns what it sent.
2. `SubtitleStore.indicesBetween(fromMs, toMs, maxRows)` for rows starting in a time range.
3. `PlaybackTranslation.kt`: `translationTexts(rows)` (shared by `upcomingTranslationTexts`) and
   `prefetchInBackground`, which waits `BACKGROUND_PREFETCH_PAUSE_MS` (3 s) before each step, reads
   rows starting from 1 to 5 minutes ahead while playing, sends one batch through `fetch`, skips
   texts it already sent, rests 2 minutes after a failure and waits for 30 s of playback (or a seek)
   when nothing is missing.
4. `AppViewModel`: the Google branch launches `prefetchInBackground` beside the playback window and
   translates a blank line with an on-device session (`translateOnDevice`); `translateText` does the
   same for single texts without falling back.
5. Docs: AGENTS.md, tech-stack, the AI assistant guide, this spec.

## Acceptance criteria

- [ ] Blank batch entries parse as `null`; a wrong count, a web page or broken JSON still throws
  (`GoogleWebTranslatorTest`).
- [ ] A reply with one blank line keeps and caches the others; the blank line is not cached and is
  asked again alone, address after address, and `translate` returns `null` only when every address
  leaves it blank (`GoogleWebTranslatorTest`).
- [ ] A multi-line reply with every line blank moves on to the next address, and blank everywhere
  fails as a block (`GoogleWebTranslatorTest`).
- [ ] Error details name the address and status; a block on every address lists all three
  (`GoogleWebTranslatorTest`).
- [ ] The recheck is false for a blank probe (`GoogleWebTranslatorTest`).
- [ ] `translateNextBatch` sends one batch of uncached texts and returns it, or nothing when all are
  cached (`GoogleWebTranslatorTest`).
- [ ] The background prefetch sends rows 1 to 5 minutes ahead one batch at a time, nothing while
  paused, each text once, and keeps going after a failure (`BackgroundPrefetchTest`).
- [ ] `indicesBetween` returns rows starting after the start up to the end, capped
  (`SubtitleStoreTest`).
- [ ] Existing prefetch, retry, block and recheck tests still pass.
- [ ] Final-head CI: all four Android CI jobs pass.
- [ ] Owner phone check: a long video keeps Google translations past 30 minutes; jumping a few
  minutes ahead shows translations right away.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local Linux + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux + CI |
| F-Droid build | `fdroid-build` and `fdroid-device-tests` CI jobs pass | CI |
| Managed-device/emulator | Existing suites pass; nothing contacts Google | CI |
| Physical-device/manual | Owner phone check above | Owner's phone |
| Live endpoint | Recorded in Context; the fix itself is checked offline | Dev machine |

## Risks / edge cases

- **More requests for blank lines:** a blank line costs up to three extra one-line requests, only when
  playback reaches it. A reply that is entirely blank is a refusal, so a degraded Google cannot turn
  every line into three requests.
- **Background volume:** the same lines are sent as before, only earlier, one request every 3 s at
  most; texts behind a jump that are never watched are wasted. Requests stop while paused.
- **Mixed engines inside a sentence:** a blank row prefix translated on the device only moves where
  that row's slice starts; the sentence's own translation still comes from Google when it is there.
- **Model download for one line:** if ML Kit's model is missing, the first blank line downloads it,
  as the per-video fallback already does.

## Release intent

`release:patch` (project default, no override label). This fixes Google dropping out and adds the
background look-ahead without new settings. The owner did not state an intent.

## Implementation result

Implemented as planned, with these details:
- A blank line is not retried inside the batch request. It stays uncached, and when playback needs
  it, `translate` sends it alone to the address that answered and then to each other address. This
  keeps the playing minute's request from waiting on retries, and asks again only for lines that
  are actually shown.
- `GoogleWebTranslator`'s memory cache holds 1,024 texts (was 256), so five minutes ahead with row
  prefixes stays in memory; the disk cache is unchanged.
- `prefetchInBackground` waits for 30 s of playback, or a seek, once everything ahead is translated.
- `runStoredTranslation` was near the 100-line complexity limit, so the Google path moved into
  `followGoogleTranslation`.

## Validation result

- Local (Linux, Android SDK 36, JDK 21): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed; 631 unit tests, 0 failures. The new and changed
  tests answer every request with a local OkHttp interceptor and never contact Google.
- Live endpoint (dev machine, 2026-10-09): see Context. The blank-line behavior itself was checked
  only offline, because Google did not return blank lines to the dev machine.
- Managed-device tests and the F-Droid jobs: CI.
- Physical phone (Google past 30 minutes, jumping ahead): pending the owner's check.
