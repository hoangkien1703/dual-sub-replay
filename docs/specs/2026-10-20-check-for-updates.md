# Check for updates in the app

## Status

Implemented (owner request 2026-10-20 in the "App updates" project thread); waiting for the owner's phone test.

## Context / problem

Every release is published on GitHub as `DualSub-Replay.apk`, signed with the same production
key, so a new APK already installs over the old one and keeps saved words and settings. But the
app never says a new version exists: the drawer only says "Check the latest version of the app on
GitHub.", so people visit GitHub and download every release by hand.

## Goals

- A **Check for updates** button near the bottom of the navigation drawer, showing the installed
  version, in place of the GitHub sentence. Open-source licenses becomes smaller text under it.
- The app checks GitHub only when that button is tapped. When a newer version exists, **Update**
  downloads it, verifies it, and hands it to Android to install over the current app.
- Settings → App updates → **Update automatically** (off by default): once a day when the app
  opens it checks, downloads on Wi-Fi (unmetered networks) only, and installs when the user leaves
  the app and no video is playing.
- Preview builds update to newer builds of the same PR from the rolling `preview` release, so the
  owner can test the whole flow before merging.

## Non-goals

- Google Play, F-Droid, Obtainium or any other store or updater.
- A project server, or any request to GitHub that the user did not start or turn on. (Superseded
  on 2026-10-30 by the owner's decision to turn update reminders on by default; see
  [scale readiness](2026-10-30-scale-readiness.md).)
- Updating a locally built debug app (its signing key differs from releases): the button opens
  the releases page instead.

## User-visible behavior

- Drawer: a **Check for updates** row with "Version X" under it; small "Open-source licenses" text
  below.
- Tapping it shows a dialog: "Checking…", then "You have the latest version", or "Version Y is
  available" with **Update** / **Not now** and a link to what's new (the release page). Update
  shows download progress, then Android installs. Failures say what went wrong (no connection,
  GitHub unreachable, checksum mismatch, Android refused) with **Try again**.
- The first install asks the user to allow "Install unknown apps" for DualSub Replay (Android's
  own screen). Android may also show its own "Update this app?" confirmation; on Android 12+ the
  app asks for no confirmation, which Android grants to an app updating itself on most phones.
- Automatic mode: if Android needs a confirmation while the app is in the background, the
  downloaded update waits and the next open shows "Version Y is ready" with **Install**.

## Technical constraints / invariants

- `REQUEST_INSTALL_PACKAGES` is a normal (install-time) permission and is removed from
  `tools/report_apk.py`'s forbidden list for this owner-requested feature. No other permission is
  added; no notification permission.
- Requests only to `https://api.github.com/repos/hoangkien1703/dual-sub-replay/releases/...`;
  downloads only from `github.com` and GitHub's release-asset hosts, size-capped.
- Every download is verified against GitHub's SHA-256 asset `digest` (official releases fall back
  to the `.sha256` asset). The APK's package name must match the running app and its version code
  must be higher before it is handed to `PackageInstaller`; Android itself enforces the signature.
- Release channel by build: official = base application id and not debuggable; preview = debug
  build whose version name ends `-pr<N>-build<M>`; anything else has no channel.
- User-visible strings in `strings_updates.xml` in every interface language. The new setting is
  on the settings page, so the AI assistant can read and change it (`app_auto_update`, asks
  first).

## Proposed approach / plan

1. `data/AppUpdates.kt`: channel detection, version comparison and release JSON parsing as small
   `internal` functions with unit tests.
2. `update/AppUpdater.kt`: process-wide state (`StateFlow`), check, download with digest and size
   checks, `PackageInstaller` session install, plus a manifest-registered status receiver.
3. `ui/AppUpdateUi.kt`: drawer row and dialog; `MainActivity` calls the automatic check on start
   and the automatic install on stop.
4. Settings → App updates section; AI setting wiring; strings in 8 languages; docs.

## Acceptance criteria

- [ ] The drawer shows Check for updates with the installed version; licences are smaller text.
- [ ] Nothing is sent to GitHub until the button is tapped, unless Update automatically is on.
- [ ] Parsing picks the newer official release / newer same-PR preview build and ignores older or
      equal ones (unit tests).
- [ ] A download whose SHA-256 differs from GitHub's digest is deleted and never installed.
- [ ] Update automatically defaults off, checks at most once a day, downloads only on unmetered
      networks and never installs while a video plays.
- [ ] CI (`verify-build`, `managed-device-tests`) passes.
- [ ] Owner installs one preview build, then updates it in place to a newer build of this PR.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `AppUpdatesTest`: channel, versions, release parsing | Local + CI |
| Android lint/build | CI parity command from AGENTS.md | Local + CI |
| Managed-device/emulator | Drawer test still finds the update row | CI |
| Physical-device/manual | Preview build N updates itself to build N+1 | Owner's phone |
| Live YouTube | Not applicable | — |

## Risks / edge cases

- GitHub's unauthenticated API allows 60 requests an hour per IP; the app makes one per tap or
  per day, and a 403/429 shows "GitHub is busy, try later".
- Some phone makers' installers always ask for confirmation; the flow still works with one tap.
- A preview build cannot update to the official app (different application id) and vice versa.

## Release intent

`release:patch` (default; adds a feature without changing existing behavior).

## Implementation result

- `data/AppUpdates.kt`: channel, version comparison, release parsing, digest and host checks.
- `update/AppUpdater.kt`: state, check, download (SHA-256 against GitHub's digest or the
  `.sha256` asset, 150 MB cap, GitHub hosts only), archive check (same package, higher version
  code), `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED` on Android 12+, and
  `AppUpdateReceiver` for the result. Tapping Update installs as soon as the download is verified.
- `ui/AppUpdateUi.kt` + `ui/DrawerFooter.kt` (was `SettingsRepositoryLink.kt`): the drawer row,
  the dialog, and Settings → More settings → App updates. The GitHub sentence and its
  `navigation_latest_version` string are gone; the dialog's What's new and Open GitHub link there.
- `MainActivity` calls `onAppStarted` / `onAppStopped`. The AI assistant setting is `auto_update`
  (asks first); Reset all settings turns Update automatically off again.
- Deviation: none from the plan above.

## Validation result

- Passed locally: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest` and `python3 -m unittest discover -s tools/tests -p 'test_*.py'`
  (cloud container, no emulator).
- Device tests (`AppUpdateUiTest`, updated `NavigationRecoveryUiTest`): CI only.
- Not verified yet: a real install on a phone (preview build N updating itself to build N+1), the
  Android 12+ no-confirmation path, and Update automatically on a real phone. Owner's phone test.
