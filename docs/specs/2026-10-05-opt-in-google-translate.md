# Google Translate (online) engine

## Status

Implemented. The owner asked for this on 2026-10-05: "users have to turn on it in setting, by
default app still use old engine, and if Google fail then show notifications and option to
switch back. create pr for this". That request does not authorize merging or publishing.

**Revised the same day, before merge.** The owner changed their mind: "let make Google translate
online become default, and have setting to change to local engine (including opinion to switch
automatically, which is off by default)". The [default-engine revision](#revision-google-by-default)
below supersedes the "off by default" goals and behavior in the original sections, which are kept
as the history of the first version.

**Revised again on 2026-10-06, before merge.** The owner dropped the dialog and the automatic-switch
setting: Google retries, then quietly falls back to on-device, and problems show as a top-right
icon. The [quiet fallback revision](#revision-quiet-fallback-and-a-top-right-icon-2026-10-06)
supersedes every dialog, transcript-bar and "Switch to on-device automatically" behavior above.

## Context / problem

- The owner reports that translation is often poor, especially Japanese. In their screenshot
  ML Kit translated 「僕の中では…あんまりいません。」 as "In my morning, … but it doesn't matter."
- The app already sends whole sentences
  ([sentence translation spec](2026-09-24-sentence-translation-and-highlight-sync.md)), so the
  errors come from the small offline model itself.
- Research (project notes, 2026-10-05) compared engines on 12 spoken Japanese sentences. Google
  Translate online was the best of all engines tested and was the only one to translate the
  cut-off fragment いません correctly. ML Kit is Google's small offline model and was clearly worse.
- Many subtitle extensions call Google's free web endpoint (`translate.googleapis.com/translate_a/single`).
  It needs no key, but it is not an official API, may conflict with Google's terms, and can be
  throttled or blocked at any time. On 2026-10-05 the `gtx` client returned HTTP 429 from the test
  machine while the `at` client still worked.

## Goals

- A Settings → Translation switch, **off by default**, that translates with Google Translate online.
- With the switch off, behavior is unchanged: ML Kit (or Bergamot in the F-Droid build).
- When Google fails, the user is told right away and can switch back to on-device translation
  with one tap, or keep Google and retry.

## Non-goals

- No official Cloud Translation API key setup, no project-operated server or proxy.
- No automatic silent fallback to ML Kit; the owner asked for a notice and a choice.
- No change to the F-Droid build: it never shows the switch or contacts Google.
- No change to row splitting or slicing.

## User-visible behavior

- **Settings → Translation:** new switch "Google Translate (online)". The description says it
  gives better translations (especially Japanese), sends subtitle text to Google, and may stop
  working because it is not an official service. Default off. "Reset all settings" turns it off.
- **Switching** the engine reloads the current video's translations with the new engine.
- **While on:** subtitle rows, live captions, "Translate" in the phrase bar and the word card all
  use Google. No ML Kit model download is needed for subtitles.
- **When Google fails during playback** (no network, HTTP error, blocked, unreadable reply):
  - Original subtitles keep playing, as for any translation failure today.
  - A dialog appears once: "Google Translate stopped working" with **Use on-device translation**
    and **Keep Google Translate**.
  - The transcript's "translation unavailable" bar shows **Retry translation** and **Use
    on-device**, so the choice stays available after the dialog is closed.
  - Choosing on-device turns the setting off and reloads the translations with ML Kit.
- The word card keeps its existing "Translation unavailable" message on a Google failure.

## Technical constraints / invariants

- The [on-device translation invariant](../project/tech-stack.md#architecture-invariants) and
  the [mission](../project/mission.md) change: on-device stays the default and needs no key;
  Google is an opt-in online exception. Update both, AGENTS.md, README and PRIVACY.md.
- The F-Droid variant must not offer it (`BuildConfig.ONLINE_TRANSLATION` is false there).
- HTTPS only to `translate.googleapis.com`, a response size cap, timeouts and cancellation, like
  the caption provider.
- User-visible text in `strings_settings.xml` / `strings_status.xml` with every translated
  locale; exception text stays English.
- Unit tests stay plain JUnit4.

## Proposed approach / plan

1. `translation/TranslationEngine.kt`: `TranslationEngine` enum (`on_device`, `google_web`) and
   `storedTranslationEngine(raw, onlineAvailable)`.
2. `translation/GoogleWebTranslator.kt`: OkHttp POST to the web endpoint with `client=at`, then
   `gtx` when one answers 403/429 (remembering the working one). Parses the JSON segments, caps the
   response at 1 MiB, 15 s call timeout, memory cache plus its own disk cache directory so cached
   ML Kit and Google results never mix. Pure helpers for language codes, parsing and the client
   order are `internal` for tests.
3. `build.gradle.kts`: `BuildConfig.ONLINE_TRANSLATION` = not F-Droid.
4. `AppViewModel`: engine preference and state, one `translateText` used everywhere,
   `runStoredTranslation` uses Google directly when on, `setTranslationEngine` reloads the video,
   `onlineTranslationFailed` state for the dialog, `useOnDeviceTranslation()`,
   `dismissOnlineTranslationFailure()`.
5. UI: switch in Settings → Translation; dialog in `LearningPlayerRoot`; second button on
   `TranslationUnavailableBar`.
6. Strings in all 8 locales, docs.

## Acceptance criteria

- [x] With no saved choice, or in the F-Droid build, the engine is on-device
  (`TranslationEngineTest`).
- [x] Google replies are joined across sentence segments; HTML "Sorry" pages, empty or malformed
  replies throw (`GoogleWebTranslatorTest`).
- [x] App language codes map to Google's (`zh` → `zh-CN`, `he` stays `he`) (`GoogleWebTranslatorTest`).
- [x] A blocked client (403/429) moves to the next client; other errors do not
  (`GoogleWebTranslatorTest`).
- [x] Every new string exists in every locale with matching placeholders (`AppLanguageTest`, lint).
- [x] Existing unit tests, lint and builds pass.
- [ ] The settings switch is off by default and hidden when online translation is unavailable; the
  failure dialog and the bar's switch-back button call their actions (`OnlineTranslationUiTest`,
  managed device in CI).
- [ ] Owner phone check: turn the switch on, play a Japanese video, see Google translations; turn
  on airplane mode, see the dialog, tap "Use on-device translation" and see ML Kit translations.
- [ ] Final-head CI: all four Android CI jobs pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local Linux + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux + CI |
| F-Droid build | `fdroid-build` CI job; no switch, no ML Kit classes | CI |
| Managed-device/emulator | Existing suites pass; offline tests never call Google | CI |
| Physical-device/manual | Owner phone check above | Owner's phone |
| Live endpoint | One manual request from the dev machine returned the expected English | Recorded below |

## Risks / edge cases

- **Blocking:** Google can block a client id or an IP at any time. The dialog and switch-back are
  the mitigation; the app never retries in a loop (existing one-failure-per-attempt rule).
- **Terms:** the endpoint is unofficial. It is opt-in and described as such in Settings and PRIVACY.md.
- **Privacy:** subtitle text goes to Google only while the switch is on.
- **Request volume:** each sentence plus each row prefix is a request (cached). Heavy use can
  trigger throttling sooner.

## Release intent

`release:patch` (project default; no override label). The owner did not state an intent. This
changes the default translation engine for every GitHub-build user, so `release:minor` would also
be reasonable if the owner prefers.

## Revision: Google by default

### Goals

- In the GitHub build, Google Translate (online) is the default engine. The Settings → Translation
  switch stays and turning it off translates on the device (ML Kit).
- New switch under it, **Switch to on-device automatically**, off by default and shown only while
  Google is the engine. When on, a Google failure moves the current video to on-device translation
  without the dialog, and a short notice (Android toast) says so.
- With the automatic switch off, failures behave as in the first version: dialog plus the bar's
  switch-back button.
- F-Droid build unchanged: no switch, never online.

### Decisions (defaults Claude picked where the request did not say)

- **Scope of the automatic switch:** only the current video. The saved engine stays Google, so the
  next video tries Google again, because blocks and network loss are often temporary. Reloading the
  same video (language or format change) keeps the fallback; turning the automatic switch off tries
  Google again for the video.
- **Existing users:** anyone without a saved choice gets Google after upgrading, including every
  user of an earlier release (the first version never shipped). "Reset all settings" also returns
  to Google and turns the automatic switch off.
- **Description:** the Google switch now says it is on by default and that turning it off
  translates on the device.

### Plan

1. `TranslationEngine.kt`: `defaultTranslationEngine(onlineAvailable)`; `storedTranslationEngine`
   falls back to it. New `AUTO_SWITCH_TO_ON_DEVICE_PREFERENCE` (resettable).
2. `AppViewModel`: `autoSwitchToOnDevice`, `onDeviceFallback`, `onDeviceFallbackNotice` state;
   `translatesWithGoogle()` is the engine actually in use. With the switch on, a stored-subtitle
   failure sets the fallback and the translation flow (keyed on it) restarts on the device;
   `translateText` (live captions, phrase bar, word card) translates that text on the device.
   A new video clears the fallback.
3. UI: `OnlineTranslationSettings` replaces the two dialog parameters; the second switch;
   `OnDeviceFallbackNotice` toast.
4. Strings in all 8 locales; AGENTS.md, tech-stack, mission, README, PRIVACY.md and the website.

### Acceptance criteria

- [x] With no saved choice the GitHub build uses Google; the F-Droid build always uses on-device,
  even with a restored Google choice (`TranslationEngineTest`).
- [x] The automatic-switch preference is cleared by "Reset all settings" (`SubtitleHighlightTest`).
- [ ] The Google switch is on for the default engine and turning it off chooses on-device; the
  automatic switch is off by default, can be turned on, and is hidden while on-device or when the
  build cannot translate online (`OnlineTranslationUiTest`, managed device in CI).
- [ ] Owner phone check: a fresh install translates Japanese with Google without touching
  Settings; with airplane mode the dialog appears; with the automatic switch on, the toast appears
  and subtitles continue with on-device translation (after the model download if needed).
- [ ] Final-head CI: all four Android CI jobs pass.

## Revision: fewer requests and a dark dialog

The owner's phone test (2026-10-06) showed "Google Translate stopped working" after a few minutes,
and the dialog appeared in Material's light default colors. They asked why other projects keep
working and for a dialog that suits the dark theme.

### Cause

- Every subtitle row cost one request for its sentence plus one per row prefix, sent back to back
  (often 3 to 4 requests per sentence). Google throttles bursts from one client with HTTP 429.
- One failed request (a 429, a timeout on mobile data) stopped translation for the rest of the
  video. There was no retry.
- The player root is not wrapped in `DualSubTheme`, so the dialog used Material's light defaults.

### What other projects do

Subtitle and page-translation extensions send many texts per request to the same free endpoints
(`translate_a/t`, which takes repeated `q` fields and answers a JSON array), cache results, and
retry later instead of giving up. Tested from the dev machine on 2026-10-06: `translate_a/t` with
`client=gtx` answered 200 to 60 quick batch requests, `clients5.google.com/translate_a/t` with
`client=dict-chrome-ex` also works, while `translate_a/single` with `client=gtx` answered 429.
Chrome's own `translate-pa.googleapis.com/v1/translateHtml` also works but needs Chrome's API key,
so it is not used.

### Changes

1. `GoogleWebTranslator.translateAll`: cached texts are skipped, the rest go in batches of up to
   32 texts / 4,000 characters to `translate_a/t` (`gtx`), then `clients5` (`dict-chrome-ex`), then
   the old one-text `translate_a/single` (`at`) when an endpoint answers 403/429. The working
   endpoint is remembered.
2. Throttling (all endpoints refused), HTTP 5xx and network errors are retried after 2 s and 6 s
   before the failure is shown. Other errors are not retried.
3. `translatePlaybackWindow` gets an optional `prefetch`: before a row is translated, the Google
   engine receives that row's sentence and prefixes plus the next untranslated sentences in the
   window (up to 8 sentences) in one request. Rows then read their texts from the cache, so a video
   costs roughly one request per 8 sentences instead of 3 to 4 per sentence.
4. The failure dialog applies `DualSubTheme` itself (dark teal surface, accent buttons), shows a
   cloud-off icon, makes "Use on-device translation" the filled button, and shows the English
   diagnostic reason (for example the HTTP status) in small text so a report says why it failed.

### Acceptance criteria

- [x] Many texts go in one request, repeats and cached texts are not sent, and later calls only
  send new texts (`GoogleWebTranslatorTest`).
- [x] A refused endpoint hands over to the next and is remembered; short outages are retried;
  throttling that outlasts the retries fails; client errors are not retried (`GoogleWebTranslatorTest`).
- [x] The prefetch sends the row's sentence, its prefixes and the next untranslated sentences once
  each (`TranslationPrefetchTest`).
- [ ] The dialog shows the failure detail (`OnlineTranslationUiTest`, managed device in CI).
- [ ] Owner phone check: a long Japanese video keeps translating with Google; the dialog matches
  the dark theme.

## Implementation result

Implemented as planned, with these details:
- The dialog is shown once per failure. After "Keep Google Translate" it is not shown again until
  the user retries or changes the engine, because live captions would otherwise fail line after line.
- The transcript bar shows the localized "Google Translate stopped working" instead of the English
  exception text when Google is the engine.
- Settings, the dialog and the bar reach the engine choice through `LocalTranslationEngineActions`
  (like `LocalLanguageDownloads`), which keeps `DualSubApp` under the complexity limit.
- `client=at` is tried first: on 2026-10-05 `gtx` answered HTTP 429 from the test machine while `at`
  answered 200 with the expected translation for the screenshot sentence.

## Validation result

- Local (Linux, Android SDK 36, JDK 21): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed; 434 unit tests, 0 failures. The new
  `GoogleWebTranslatorTest` answers every request with a local OkHttp interceptor, so it never
  contacts Google.
- Live endpoint, from the dev machine with curl: POST `client=at&sl=ja&tl=en&dt=t` with the
  screenshot sentence returned "I thought there would be a lot of people out for a walk around 6am,
  but there weren't that many."
- Managed-device tests (`OnlineTranslationUiTest`) and the F-Droid build: not run locally (no
  emulator or NDK); CI runs them.
- Physical phone: not run; pending the owner's check above.

### Revision validation result

- Local (Linux, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed; 435 unit tests, 0 failures.
- `loadVideo` hit the 100-line complexity limit, so the translation flow moved into
  `followStoredTranslation`.
- Managed-device tests, the F-Droid build and the owner's phone check: run by CI and the owner.
- The F-Droid jobs on the first version's CI run were cancelled without ever getting a runner
  (no steps, no logs), so they say nothing about this change.

### One minute ahead (2026-10-06)

The owner confirmed the batched build "worked better" and asked to translate the next minute of
subtitles too. The prefetch now covers every untranslated sentence starting within 60 s of the row
being translated (`PREFETCH_AHEAD_MS`) instead of 8 sentences, and a batch may hold up to 128 texts
and 5,000 characters. On 2026-10-06 the batch endpoint answered 120 texts and an 8,000-character
text in single requests, so these limits leave room. A minute of speech is usually one request.
The window already follows playback 60 s ahead (`SUBTITLE_LOOK_AHEAD_MS`), so rows fill from the
cache as playback reaches them. `TranslationPrefetchTest` covers the horizon.

### Fewer-requests revision validation result

- Local (Linux, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` and `tools/tests` passed; 443 unit tests, 0 failures.
- With the one-minute prefetch and [shorter Japanese rows](2026-10-06-japanese-short-rows.md): the
  same commands passed; 448 unit tests, 0 failures.
- Managed-device tests and the owner's phone check: CI and the owner.

## Revision: quiet fallback and a top-right icon (2026-10-06)

The owner asked: "now i don't want to show any big dialog, The Google translate will be default,
and after try up to 3 time (after 1s, 3s, 7s) it will use local engine. when user try to reload
video or move to next video, app will try Google translate cloud again. all the error like this now
will be shown in the far top Right of the portrait mode of the app when happen, and users can click
in to that to show more details". They approved Claude's three recommendations (remove the
automatic-switch setting, the icon covers translation errors only, the details offer "Try Google
again") and, on a decision card, two more: switch right away when Google blocks the app, and check
Google again every 2 minutes.

### Behavior

- No dialog, toast or transcript bar. "Switch to on-device automatically" and its preference are
  removed (they never shipped).
- A failing request is retried after 1 s, 3 s and 7 s (`GOOGLE_RETRY_DELAYS_MS`) when Google
  answered 5xx or could not be reached. When every endpoint answers 403/429 (a block), the error is
  thrown at once (`GoogleTranslateException.blocked`), since a few seconds do not lift a block.
- After that, the video translates on the device (`DualSubUiState.onDeviceFallback`). Rows Google
  already translated keep Google's text from its cache (`GoogleWebTranslator.cachedTranslation`).
- While falling back, the app sends the current row to Google every 2 minutes during playback
  (`GOOGLE_RECHECK_INTERVAL_MS`, `GoogleWebTranslator.responds`, no cache, no retries). When Google
  answers, the video goes back to Google and the icon disappears.
- Every load (the next video, a language or settings change, a captions retry) tries Google again.
- A small icon at the end of the app's top bar (`TranslationIssueButton`) shows while there is a
  translation problem: amber cloud-off for the on-device fallback, red for translation stopped.
  Tapping it shows the title, an explanation, the English diagnostic detail (for example
  "HTTP 429"), and one action: **Try Google again** or **Retry translation**. The top bar is the
  same in landscape, so the icon shows there too; fullscreen hides it like the rest of the bar.

### Acceptance criteria

- Retry delays are 1 s, 3 s and 7 s; a block on every endpoint is not retried; 5xx is tried four
  times in all (`GoogleWebTranslatorTest`).
- The recheck bypasses the cache, sends one request and caches a success (`GoogleWebTranslatorTest`).
- The icon reports nothing without a video or a problem, the fallback with its detail, and a
  stopped translation before the fallback (`TranslationIssueTest`).
- The icon opens the details; its action calls "Try Google again" or "Retry translation"
  (`OnlineTranslationUiTest`, `SubtitleUiTest`, managed device).
- New strings exist in all 8 locales; removed strings are gone from all of them.

### Quiet-fallback validation result

- Local (Linux, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` and `tools/tests` passed; 458 unit tests, 0 failures.
- `DualSubApp` reached the 100-line limit, so "Try Google again" reaches the top bar through
  `TranslationEngineActions` instead of a new parameter.
- Managed-device tests and the F-Droid build: CI. Physical phone: pending the owner's check.
