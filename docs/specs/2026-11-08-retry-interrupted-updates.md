# Retry interrupted app updates

## Status

Implemented. Owner picked "Full fix + cleanup" on 2026-11-08. Merge and release need the owner's OK.

## Context / problem

Owner report 2026-11-08: when an in-app update is interrupted midway, the app offers no way to retry the install. The owner also asked whether the app removes the old version after installing.

Reading `update/AppUpdater.kt` on `main` (ae9c1be) found three causes:

1. **Stuck install.** If the person leaves Android's "Update this app?" screen with Home instead of Cancel, Android sends no answer. The state stays `Installing` for good: `busy()` is true, so Check for updates and Update do nothing, and the dialog only offers Hide. Only killing the app clears it.
2. **Everything is in memory.** If the app is closed or killed (ColorOS does this often) during or after a download, the next try downloads from 0%, and the reminder banner waits a week.
3. **No resume.** A dropped connection shows Try again, but it downloads all ~34 MB again.

Leftover file: Android replaces the installed app in place, so no old version stays installed. The downloaded APK (`cacheDir/app-update/update.apk`, ~34 MB) was never deleted after a successful install; only the next update's download or Android's cache clearing removed it.

Related: [Check for updates](2026-10-20-check-for-updates.md).

## Goals

- An install Android never answered shows Install again when the app comes back to the screen.
- A finished download survives the app being closed and is offered again without downloading it twice.
- A broken download carries on from where it stopped, both on Try again and after the app was closed.
- The downloaded APK is deleted once the new version opens.

## Non-goals

- No background download service or progress notification (owner picked the option without it).
- No new settings or buttons.

## User-visible behavior

- Home on Android's install screen, then back to the app: the dialog shows "Version X is ready to install." with Install. Install opens Android's screen again (the same session while Android still holds it, otherwise a new one).
- The connection drops mid-download: the app tries twice more by itself (after 2 s and 5 s) when bytes were arriving. If it still fails, the dialog says "The download stopped before it finished. Try again to carry on from where it stopped." Try again first asks GitHub which version is newest (owner question 2026-11-08): the same version asks GitHub for the rest only; a version released meanwhile replaces the half download, so the person updates once, to the newest. A finished download already in the cache installs at once.
- The app is closed after the person tapped Update: on the next start the dialog shows once more, with Install for a finished download or Try again for a half one. A download Update automatically made waits silently as before and still installs when the app leaves the screen.
- Check for updates with a finished download of the same version in the cache: Ready with Install, no new download.
- After the update, the new version deletes the APK on its first start. Up to date also clears the folder, and a half download older than 3 days is deleted.

## Technical constraints / invariants

- Downloads stay on GitHub hosts and HTTPS; the whole file must still match GitHub's SHA-256 and be a newer version of this package before it reaches `PackageInstaller` (AGENTS.md App updates). A resumed download hashes the bytes already on disk first, so the check covers the whole file.
- `update.json` beside the APK is parsed strictly (schema 1, GitHub HTTPS address, 64-hex SHA-256); anything else clears the folder.

## Proposed approach / plan

- `data/UpdateDownloads.kt`: `UpdateDownloadRecord` (update, expected SHA-256, asked), JSON write/parse, `isSameUpdateDownload`, `contentRangeStart`.
- `update/AppUpdater.kt`:
  - `downloadNow` writes the record, reuses a finished APK, otherwise `downloadWithRetries` → `downloadTo` with `Range: bytes=<have>-`; a 200 starts over, a 206 must start at the byte asked for. CHECKSUM/WRONG_APP clear the folder; other failures keep the part (`INTERRUPTED` when bytes are there).
  - `restoreDownload` on the first start of a process: abandon the closed app's install sessions, delete an APK that is not newer than this app, restore Ready or the interrupted state, show the dialog once when the person had tapped Update.
  - `recoverUnansweredInstall` on `onAppStarted` while `Installing`: back to Ready (with the waiting confirmation) unless a silent install is still in progress.
  - `installNow` abandons this app's older sessions before creating a new one.
- `ui/AppUpdateUi.kt` + `strings_updates.xml` (9 languages): the `INTERRUPTED` message.

## Acceptance criteria

- [x] `Installing` with an unanswered confirmation becomes Ready on return to the screen; with no session left, it becomes Ready too.
- [x] Try again after a dropped connection sends a `Range` request and keeps the earlier bytes; the full-file SHA-256 is still checked.
- [x] A finished download is reused after the app is closed; a not-newer APK is deleted at start.
- [x] The record is parsed strictly (`UpdateDownloadsTest`).
- [ ] Owner phone test with the preview build (below).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `UpdateDownloadsTest`, `AppUpdatesTest` pass | cloud container |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | cloud container |
| Managed-device/emulator | `AppUpdateUiTest.aBrokenDownloadOffersTryAgain` | PR CI |
| Physical-device/manual | Preview build N updating to N+1: (a) Home on Android's install screen, reopen app → Install shows and works; (b) airplane mode mid-download → Try again continues from the same percent; (c) swipe the app away mid-download, reopen → Try again continues; (d) after the update, `app-update` cache is empty (Settings → Apps → storage: cache drops ~34 MB) | owner's phone |
| Live YouTube | not applicable | |

## Risks / edge cases

- GitHub's signed download address changes on every request; the `Range` header follows the redirect, and a host that ignores it sends 200, which starts over.
- Killed after the last byte but before the check: the part's length equals the size, so it is hashed without a request.
- `abandonSession` on a session already committed may throw on some Android versions; it is ignored.

## Release intent

`release:patch` (fix in the APK).

## Implementation result

As planned above. Deviation: none.

## Validation result

- Passed locally (cloud container, no emulator): `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` and `python3 -m unittest discover -s tools/tests -p 'test_*.py'`.
- PR CI on f156815: `verify-build` and `managed-device-tests` passed, including `AppUpdateUiTest.aBrokenDownloadOffersTryAgain` (screenshot `app-update-interrupted` matches the other update dialogs).
- Not verified yet: the four phone scenarios in the validation plan, on the owner's phone with the PR preview build.
