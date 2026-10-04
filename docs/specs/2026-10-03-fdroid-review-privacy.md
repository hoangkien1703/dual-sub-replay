# F-Droid review: browser data out of backups, no Safe Browsing in the F-Droid build, correct licences

## Status

Implemented. Approved for implementation by the owner on 2026-10-03 ("ok do what you recommend"), after the
F-Droid static review of v1.1.2 on fdroiddata merge request
[!50465](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50465). The owner chose to turn
Safe Browsing off in the F-Droid build only.

## Context / problem

The F-Droid reviewer raised three points, all still true on `main` (v1.3.5):

1. **Backups can carry the Google login.** `allowBackup="true"`, and the backup rules from the
   [backup alignment spec](2026-09-30-docs-backup-ci-alignment.md) leave out only the downloaded
   dictionary and models. The embedded browser's profile (`app_webview/`, with its session
   cookies) goes into cloud backups and device transfers.
2. **The store description misses connections.** It names only YouTube, not the Mozilla model
   download, the Japanese dictionary download from Maven Central (or Google's mirror), or the
   optional Google sign-in. WebView Safe Browsing is turned on explicitly
   (`AndroidManifest.xml`, `applyEmbeddedSecurityPolicy`), which sends URL checks to Google on
   devices with Google's WebView.
3. **The licence screen is wrong.** It shows MIT plus GPL-3.0 (from the downloader removed after
   v0.9.7) and none of the notices of the libraries in the APK.

## Goals

- Backups and device transfers leave out the WebView profile on every Android version; saved
  words, Progress and preferences stay in backups.
- The F-Droid build turns WebView Safe Browsing off (manifest and WebView setting); the full
  build keeps it on.
- The licence screen shows the app's MIT licence, the Kotlin/Java libraries' notices and
  Apache-2.0 text, and the build's own extras: the ML Kit terms in the full build, and the
  licence files of the native engine (taken from the submodules at build time) in the F-Droid
  build. The GPL-3.0 text is gone.
- The store description and PRIVACY.md name every connection the app makes.
- The fdroid draft recipe and docs match the submitted recipe (`fdroid-v` tags, categories).

## Non-goals

- No change to what is downloaded, or to the sign-in flow itself.
- Translations of the store description (only `en-US` exists).
- Updating the fdroiddata merge request; that happens after this is released.

## User-visible behavior

- **Before:** restoring a backup or moving to a new phone also restored the YouTube/Google login;
  the licence screen showed the GPL; the F-Droid build checked URLs with Google Safe Browsing.
- **After:** after a restore or transfer, the user signs in to YouTube again; saved words,
  Progress and settings still come back. The licence screen lists the real licences. The F-Droid
  build makes no Safe Browsing lookups; the GitHub build is unchanged there.

## Technical constraints / invariants

- Backup rules stay exclude-only; `BackupRulesTest` checks the paths in both rule files.
- The single-WebView and origin checks are untouched; `BrowseWebViewLifecycleTest` checks the
  security policy.
- The F-Droid build must keep only free software; the notices come from the submodules
  F-Droid already checks out.

## Proposed approach / plan

1. Exclude `root`-domain `app_webview/` in `backup_rules.xml` and both sections of
   `data_extraction_rules.xml`; extend `BackupRulesTest`.
2. In `app/build.gradle.kts`, derive `BuildConfig.WEBVIEW_SAFE_BROWSING` and the manifest
   placeholder `webViewSafeBrowsing` from the distribution; use them in the manifest and
   `applyEmbeddedSecurityPolicy`; update the device test.
3. Licence assets: `main/assets/licenses/libraries.txt` (shared libraries, Apache-2.0 text),
   `full/assets/licenses/distribution.txt` (ML Kit terms), and for the F-Droid build a Gradle
   task that writes `licenses/distribution.txt` from the submodules' licence files. Delete
   `GPL-3.0.txt`; the screen reads `MIT.txt`, `libraries.txt`, `distribution.txt`.
4. CI `fdroid-build`: also require the notices asset and `EnableSafeBrowsing=false` in the APK.
5. Update the store description, PRIVACY.md, THIRD_PARTY_NOTICES.md and docs/fdroid.

## Acceptance criteria

- [x] Both backup rule files exclude `app_webview/` (root domain) in addition to the downloads,
  for cloud backup and device transfer (`BackupRulesTest`).
- [x] The full build has `WEBVIEW_SAFE_BROWSING = true`; the F-Droid build has `false` and its
  APK manifest has `EnableSafeBrowsing` false (`fdroid-build` CI check, local build).
- [x] The licence screen reads only existing assets; `GPL-3.0.txt` no longer ships
  (`LicenseAssetsTest`); the F-Droid APK contains the generated engine notices.
- [x] The store description mentions the Mozilla and dictionary downloads and the optional
  Google sign-in; PRIVACY.md mentions Safe Browsing and the WebView backup exclusion.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` incl. `BackupRulesTest`, `LicenseAssetsTest` | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| F-Droid build | `assembleRelease -Pdistribution=fdroid`; APK has notices asset and Safe Browsing off | Local Linux, CI `fdroid-build` |
| Managed-device/emulator | `BrowseWebViewLifecycleTest` and the existing suite | CI |
| Physical-device/manual | Licence screen and a backup restore on a phone | Owner (optional, not run) |

## Risks / edge cases

- After a restore the user is signed out of YouTube; that is the intended trade.
- A submodule update that moves a licence file fails the F-Droid build loudly instead of
  shipping incomplete notices.

## Release intent

`release:patch` (repository default; behavior changes are small). The version is an estimate
until reserved (next after v1.3.5). After release, the owner adds the matching `fdroid-v` tag
and the fdroiddata recipe moves to that version.

## Implementation result

As planned. Details:

- The F-Droid notice is written by the `engineLicenseNotice` task to
  `licenses/distribution.txt` (19 components; the list is in `app/build.gradle.kts`). Some files
  cover components that may not all be linked on every ABI (intgemm is x86-64 only); listing an
  unused one is harmless, a missing one is not.
- The full build's notice is `app/src/full/assets/licenses/distribution.txt`, added through
  `assets.srcDir("src/$distribution/assets")`. F-Droid's recipe deletes `app/src/full`, which
  this does not depend on.
- `navigation_license_summary` no longer says GPL-3.0, in all eight app languages.
- The fdroid docs now describe `fdroid-v` tags, and `docs/fdroid/com.kienhoang.dualsubreplay.yml`
  matches the submitted recipe (categories, update check).

## Validation result

Local Linux, 2026-10-03:

- Passed: `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest` (includes `BackupRulesTest`, `LicenseAssetsTest`).
- Passed: `./gradlew assembleRelease lintRelease -Pdistribution=fdroid`. The APK holds
  `assets/licenses/{MIT,libraries,distribution}.txt`; `distribution.txt` has all 19 sections;
  `aapt2 dump xmltree` shows `EnableSafeBrowsing` = `false`; the new CI shell check passes on it.
- Passed: `python3 -m unittest discover -s tools/tests -p 'test_*.py'` (31 tests); workflow YAML
  parses.
- Not run locally: managed-device tests (no KVM here); see the PR's CI. The backup exclusion and
  the licence screen were not checked on a phone.
