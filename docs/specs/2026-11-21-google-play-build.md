# Google Play build (2026-11-21)

## Status

Implemented (owner picks 2026-11-21 in the "Publish the app on Google Play" thread: publish on
Google Play with the same package and signing key, a separate Play build without what Play forbids,
then "create a PR for the Play Store version"). Merge and release stay the owner's call.

## Context / problem

The app has shipped only as GitHub releases. The owner now wants it on Google Play too, under the
title "DualSub Replay: Dual Subtitles", with the same package (`com.kienhoang.dualsubreplay`) and the
same signing key (`f664…116c`, through Play App Signing), so a person can move between the two.

Google Play's Developer Program Policies forbid parts of the GitHub build:

- Ad and sponsor skipping: apps must not interfere with another app's ads (YouTube's ads).
- Screen-off and background playback of YouTube: against YouTube's terms (a Premium feature), and
  Play removes apps that break another service's terms. It also needs a foreground service of type
  media playback, which Play reviews.
- Installing its own updates (`REQUEST_INSTALL_PACKAGES`, the in-app updater, delta patches):
  an app from Play may update itself only through Play.
- The Bilibili beta sites: third-party sites the app was not given rights to show; Play keeps to
  YouTube while the GitHub build keeps the beta.
- Apps that make AI content must let people report offensive AI output in the app.

## Goals

- A `play` build type: the release build with the same package and version, as an Android App Bundle
  (`./gradlew bundlePlay`), that leaves out everything above.
- Report under each AI assistant answer, in both builds.
- CI builds the Play bundle on every PR and fails when its manifest asks for a forbidden permission
  or declares the playback service or update receiver again.

## Non-goals

- Signing and uploading the bundle to Play (needs the owner's Play Console account, which Google is
  still verifying, and the upload key in the `release` environment): a follow-up PR.
- The store listing, screenshots and feature graphic, and the testers' Google Group.
- The drawer's "Help test the Play version" link and the GitHub Discussion: once the closed test is
  live (owner's earlier ask, same thread).
- Any change to the GitHub build's behaviour, apart from Report under AI answers.

## User-visible behavior

GitHub build: unchanged, plus a small Report button (flag icon) under each AI assistant answer.

Play build:

- Settings has no Ads & sponsors and no App updates section; Beta keeps only the TV layout. Search
  settings and the AI assistant never offer those switches (`skip_ads`, `skip_sponsors`,
  `always_skip_sponsors`, `update_reminders`, `auto_update`, `bilibili`, `bilibili_international`).
- YouTube ads play as YouTube shows them; no sponsor part is skipped, whatever an earlier backup or
  the GitHub build stored.
- A video pauses when the app leaves the screen (as before screen-off playback came in): no media
  notification, no earphone buttons.
- Menu → Check for updates opens the app's Play Store page; the app never checks GitHub for a new
  version, never downloads one and never asks to install one.
- Report under an AI answer asks why (Offensive or harmful, Wrong or misleading, Something else),
  then opens the phone's mail app to the project's report inbox with the reason, app version and
  the answer (at most 1,500 characters) filled in. Nothing is sent until the person sends it; with no
  mail app, the dialog shows the inbox address.

## Technical constraints / invariants

- One switch: `BuildConfig.PLAY_STORE`, read as `IS_PLAY_BUILD` (`data/PlayBuild.kt`). The left-out
  code stays in the bundle (R8 keeps it, checked 2026-11-21) but never runs, and the manifest drops
  what it would need. Each area has a small function with a `playStore`
  parameter that unit tests call for both builds: `AdSkipSettings.forBuild`, `playsOffScreen`,
  `updateChannelFor(…, playStore)` (`UpdateChannel.GooglePlay`), `MoreSettingsSection.inBuild`,
  `SearchableSetting.inBuild`, `betaSitesInBuild`, `aiSettingsInBuild`.
- A build type, not a product flavor: every existing task name (`assembleDebug`, `assembleRelease`,
  the managed-device tasks, the benchmark build) stays as it is.
- `src/play/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, the playback service and the update receiver from the merged
  manifest; `tools/check_play_manifest.py` checks it in CI.
- Signing stays out of Gradle: the `play` build type has no signing config, like the GitHub APK that
  `publish-main` signs with `apksigner` ([AGENTS.md](../../AGENTS.md)).
- Reports: no project server ([AI rules](../project/tech-stack.md#ai-features)); the person sends the
  email from their own mail app, like Report a problem.

## Proposed approach / plan

1. `app/build.gradle.kts`: `PLAY_STORE` BuildConfig field; `play` build type from `release`.
2. `src/play/AndroidManifest.xml` with the removals.
3. Gate each area (files above) and wire the gates into `YouTubeBrowserScreen.kt`,
   `AdSkipScripts.kt`, `SubtitleSettingsDialog.kt`, `SettingsSearch.kt`, `BetaSettings.kt`,
   `AiActions.kt`, `AiAssistantSnapshot.kt`, `AppUpdateUi.kt`.
4. `ui/AiAnswerReport.kt`: Report button and dialog; strings in all 9 interface languages.
5. CI: `bundlePlay` + manifest check + unsigned bundle artifact in `verify-build`.

## Acceptance criteria

- [x] `PlayBuildTest`: the Play build skips no ads or sponsors, pauses when hidden, has no updater
      channel, shows no Ads & sponsors / App updates section or Bilibili switches, and its assistant
      offers none of those settings; the GitHub build keeps every one.
- [x] The Play build's merged manifest has no installer or foreground service permission, playback
      service or update receiver (`tools/check_play_manifest.py`, and it fails on the GitHub manifest).
- [ ] `bundlePlay` builds in CI and uploads `DualSub-Replay-play-bundle`.
- [ ] `AiAssistantPanelUiTest.reportUnderAnAnswerAsksWhyBeforeOpeningEmail`: Report shows the
      reasons and closes with the X; nothing is sent to the model.
- [ ] Existing unit and device tests pass (GitHub build unchanged).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `PlayBuildTest`, `SettingsSearchTest`; tools `test_check_play_manifest.py` | Cloud container, PR CI |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug`, `assembleRelease`, `bundlePlay` | PR CI `verify-build` |
| Managed-device/emulator | `AiAssistantPanelUiTest` with the new Report test; UI evidence `ai_report_answer` | PR CI `managed-device-tests` |
| Physical-device/manual | Owner installs the preview (GitHub build) and checks Report under an answer. The Play build itself is first tried from Play's internal or closed test track. | Owner's phone |
| Live YouTube | Not applicable before the Play upload; the Play build's pause-when-hidden is checked from the closed test track. | Closed test |

## Risks / edge cases

- A person who moves from the GitHub build to the Play build keeps their stored switches; the Play
  build ignores the ones it leaves out, and moving back gives them back.
- Earphone buttons and the media notification are gone in the Play build, since they lived in the
  playback service. If testers miss them, a later change can keep the media session while the app is
  on screen.
- Play's review may still ask about other parts (the YouTube page in a WebView, the app's name and
  icon next to YouTube's); the store listing will say the app is not made by YouTube.

## Release intent

`release:patch` (owner rule: patch releases only). The GitHub APK changes (Report under AI answers;
the Play gates compile in but stay off). Expected v1.9.17 (estimate: v1.9.16 is the logo PR #251).

## Implementation result

As planned. Kept for later: signing and uploading the bundle, the drawer link to the tester
Discussion.

## Validation result

- Cloud container: `bundlePlay` (33 MB unsigned AAB), `compileDebugKotlin`, `compilePlayKotlin`, `PlayBuildTest` (7 tests),
  `SettingsSearchTest`, `processPlayMainManifest` + `check_play_manifest.py` (passes on Play, fails on
  debug as expected), `python3 -m unittest discover -s tools/tests` passed.
- PR CI and the phone check: see the PR.
