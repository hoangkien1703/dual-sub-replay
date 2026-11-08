# Smaller app updates: patches from earlier versions, ARM-only release APK

## Status

Implemented (owner pick 2026-11-08 in the "Decide on smaller or APK-free updates" thread: "Delta
updates + drop x86 libs", one PR). Waiting for CI and the owner's phone test.

Rejected in the same research (owner agreed): downloading page scripts without an APK. None of the
~68 releases from v1.3.2 to v1.8.24 changed only page scripts; remote switches
([scale-readiness spec](2026-10-30-scale-readiness.md)) already turn broken features off without an
APK, and scripts in the signed-in YouTube page would be a silent path to every phone.

## Context / problem

Every update downloads the whole APK: 39.8 MB for v1.8.24. Measured on the published APKs:

- 27.1 MB of it is ML Kit's `libtranslate_jni.so` for four ABIs (compressed: arm64-v8a 6.8,
  armeabi-v7a 6.0, x86 7.3, x86_64 7.1 MB). Phones only use one ARM copy.
- Between releases only `classes.dex`, the manifest, the baseline profile and sometimes
  `resources.arsc` change; libraries and learner data keep their exact compressed bytes.

## Goals

- **Patches.** Each release publishes small patches from the last 15 releases. An app on one of
  them downloads its patch, rebuilds the new APK from its own installed APK, and installs it.
  Measured: v1.8.23 → v1.8.24 2.6 MB, v1.8.20 or v1.8.10 → v1.8.24 3.1 MB (vs 39.8 MB).
- **Preview builds too.** A preview build updating to the next build of the same PR downloads a
  patch, so the owner's phone tests the same path.
- **ARM-only release APK.** The release build keeps only `arm64-v8a` and `armeabi-v7a`: about
  25 MB for every download, first installs included.

## Non-goals

- Byte-level diffs inside changed files (would need re-compressing on the phone exactly like the
  build did; `classes.dex` alone would shrink to ~0.8 MB, not worth the fragility).
- Separate APKs per ABI (Obtainium and the site would need to choose one).
- Any new host, server or permission.

## User-visible behavior

- The update dialog shows the patch's size, for example "Version 1.8.27 is available (2.6 MB)".
  Progress and every other step are as before.
- When a patch is missing or fails in any way, the app downloads the whole APK as before; the
  problem log records `PATCH_FAILED`.
- A device that cannot run ARM apps at all (no x86 phone runs Android 8+; Intel Chromebooks and
  emulators list ARM through translation) gets no update offer: the update dialog says new versions
  are made for ARM phones and the installed version keeps working, with Open GitHub. Debug and
  preview builds keep every ABI.

## Technical constraints / invariants

- Patch format (`tools/apk_patch.py` makes it, `data/UpdatePatches.kt` applies it): `DSRPATCH`,
  version 1, the old and new APK's size and SHA-256, then a zlib stream of copy (offset, length
  from the installed APK) and data operations. Unchanged zip entries are copied; everything else is
  new bytes. The rebuilt file must have the release's SHA-256 (the same check as a whole download),
  then the existing package/version check runs, so a patch can never produce anything else.
- The installed APK is read from `applicationInfo.sourceDir`; an app installed in parts (split
  APKs) skips patches.
- `update-info.json` stays schema 1 and gains an optional `patches` list (`from_version_code`,
  `from_sha256`, `name` = `update-from-vX.Y.Z.patch`, `sha256`, `size`); older apps ignore it.
  Preview patches are assets named `DualSub-Replay-PR<N>-build<new>-from-build<old>.patch` and use
  GitHub's asset digest.
- Patches are best effort in both workflows: a failure publishes the release without them.
- `pr-preview-auto.yml` runs from `main`, so preview patches start after this PR is merged.

## Proposed approach / plan

1. `tools/apk_patch.py` (make, apply, release) + `tools/tests/test_apk_patch.py`; a fixture in
   `app/src/test/resources/update-patch/` made by the tool.
2. `release-on-main.yml`: download the last 15 releases' APK and `update-info.json`, make patches,
   write them into `update-info.json`, upload them. `automatic_release.py update-info --patches`.
3. `pr-preview-auto.yml`: patch from the build being replaced; cleanup removes patches too.
4. `data/UpdatePatches.kt` (parse, choose, apply) + `AppUpdates.kt` (`AppUpdate.patch`,
   `downloadBytes`, `UpdateChannel.UnsupportedDevice`) + `AppUpdater.patchedApk` with fallback.
5. `app/build.gradle.kts`: release `abiFilters` arm64-v8a + armeabi-v7a (benchmark keeps all).
6. AGENTS.md App updates paragraph.

## Acceptance criteria

- [x] The Python tool's patch rebuilds the new APK byte for byte (real v1.8.10, v1.8.20, v1.8.23 →
      v1.8.24 APKs, and the fixture); a patch for another APK, a cut or a damaged patch is refused.
- [x] The app's `applyUpdatePatch` rebuilds the real v1.8.24 APK from v1.8.23, v1.8.20 and v1.8.10
      with the tool's patches (SHA-256 `6cf752d1…`, ~100 ms on a laptop JVM; one-off local check).
- [x] Unit tests: the fixture patch applies; `update-info.json` patches parse, odd entries are
      dropped, the installed version's patch is chosen; preview patch matched by PR and builds;
      ARM detection and `UnsupportedDevice`.
- [ ] The release APK holds only `arm64-v8a` and `armeabi-v7a` libraries (CI's release-size report).
- [ ] CI parity command and device tests pass (local: formatCheck, complexityCheck,
      testDebugUnitTest, lintDebug, assembleDebug, assembleDebugAndroidTest pass; device tests in CI).
- [ ] Owner, after merge: a preview build updates to the next build of the same PR with a patch
      (dialog shows ~3 MB), and the next official release's update dialog shows a few MB.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Release tools | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` | Local + CI |
| Unit tests | `UpdatePatchesTest`, `AppUpdatesTest`, `UpdateDownloadsTest` | Local + CI |
| Real APKs | Tool + app patcher on published v1.8.10/20/23 → v1.8.24 | Local, one-off |
| Android lint/build | CI parity command from AGENTS.md | CI |
| Physical device | Preview patch update; first official patch update | Owner's phone, after merge |
| Live YouTube | Not applicable | — |

## Risks / edge cases

- The first release after this PR has no ARM-x86 difference to patch around: patches copy the
  ARM libraries and skip the x86 ones, so they stay small.
- A release page gets up to 15 extra `.patch` assets; they sort after the APK.
- Rebuilding needs about 40 MB of free cache space, as a whole download does.
- An x86-only device without ARM translation can't install new versions; it keeps v1.8.25 or
  whichever version it has. If someone asks, an x86 APK can be published separately.
