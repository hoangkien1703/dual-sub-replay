# Privacy and notices match the app; backups skip redownloadable files; releases need every CI job

## Status

Implemented. Approved for implementation by the owner on 2026-09-30 ("do what you recommend",
after the review of the external Sol 6.1 audit, items 7 and 9 plus the backup gap found during
that review). Local checks pass; see the PR for the final-head CI.

## Context / problem

1. **PRIVACY.md was out of date.** It did not mention the Japanese dictionary download added in
   v1.3.1 (PR #87), still described the optional yt-dlp clip downloader that was removed, and did
   not say that a tapped word's speech is recorded to a temporary file.
2. **THIRD_PARTY_NOTICES.md left out Kuromoji.** The APK contains Kuromoji's code (Apache-2.0), and
   the downloaded dictionary carries the mecab-ipadic NAIST/ICOT notice, whose "NO WARRANTY"
   section must accompany redistribution.
3. **Backups included large downloaded files.** `android:allowBackup="true"` with no rules backs
   up everything in `filesDir`, including the 13 MB Japanese dictionary (`japanese-dictionary/`)
   and, in the F-Droid build, the Bergamot models (`translation-models/`). Android's cloud backup
   quota is 25 MB per app; going over it stops the app's backups, which would also stop backing
   up saved words and Progress.
4. **The PR #87 spec still read as unreleased.** Its status, two acceptance boxes, the release
   shrink row, release intent and validation result predate the phone test and the v1.3.1 release.
5. **The release gate named only two CI jobs.** `tools/automatic_release.py` listed
   `verify-build` and `managed-device-tests` as required. The run-level success check already
   fails on any failed job, but a renamed or removed F-Droid job would silently stop gating
   releases, and AGENTS.md/README.md told contributors only two jobs matter.

## Goals

- PRIVACY.md describes every network download and local file the current app makes.
- THIRD_PARTY_NOTICES.md lists Kuromoji and reproduces the mecab-ipadic notice.
- Backups and device transfers leave out `japanese-dictionary/` and `translation-models/`, on
  Android 8–11 (`fullBackupContent`) and 12+ (`dataExtractionRules`); saved words, Progress and
  preferences stay in backups.
- The PR #87 spec records what actually happened.
- A release requires all four Android CI jobs by name, and a test keeps that list equal to the
  jobs in `.github/workflows/android.yml`.

## Non-goals

- No change to what is downloaded, when, or from where.
- ML Kit manages its own model storage; this PR does not try to exclude it.
- WebView data and cookies keep today's backup behavior.
- The GitHub ruleset that marks checks required for merging is the owner's setting; this PR
  does not change it (see Release intent / owner action).

## User-visible behavior

- **Before:** a phone backup could include 13 MB or more of downloaded files and hit Android's
  25 MB quota; the privacy policy described a clip downloader that no longer exists.
- **After:** backups hold only the learner's own data and settings; after a restore the
  dictionary or models download again on first use. The privacy policy and notices match the app.

## Technical constraints / invariants

- Backup rules use exclude-only lists, so everything else keeps being backed up.
- The excluded paths must equal the folder names `AppViewModel` creates; `BackupRulesTest` checks
  both rule files, the manifest attributes and those names.
- Workflow tests run with `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v`, without
  PyYAML.

## Proposed approach / plan

1. Update PRIVACY.md and THIRD_PARTY_NOTICES.md.
2. Add `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, reference them from the
   manifest, and add `BackupRulesTest`.
3. Reconcile the PR #87 spec with the PR record.
4. Set `REQUIRED_JOBS` to all four jobs, update the error text, AGENTS.md and README.md, and add
   tests for workflow parity and for a failed, pending or missing F-Droid job.

## Acceptance criteria

- [x] PRIVACY.md mentions the dictionary download, hosts and checksum; no longer describes clip
  downloads; describes the temporary pronunciation recording and the backup exclusions.
- [x] THIRD_PARTY_NOTICES.md lists Kuromoji 0.9.0 and reproduces the mecab-ipadic notice.
- [x] Both backup rule files exclude exactly `japanese-dictionary/` and `translation-models/`
  (cloud backup and device transfer), and the manifest references them (`BackupRulesTest`).
- [x] `REQUIRED_JOBS` equals the job names in `android.yml`; a failed, pending or missing
  `fdroid-build` or `fdroid-device-tests` job blocks the release.
- [x] The PR #87 spec's status, boxes, shrink row, release intent and validation result match the
  PR record.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Workflow tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` | Local Linux, CI |
| Unit tests | `./gradlew testDebugUnitTest`, including `BackupRulesTest` | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` (lint validates the backup XML) | Local Linux, CI |
| Managed-device/emulator | Existing suite | CI |
| Physical-device/manual | `adb shell bmgr backupnow` then inspect, or a restore to a second phone | Owner's phone (optional, not run) |

## Risks / edge cases

- After a restore, the first Japanese video (and, in the F-Droid build, the first translation)
  downloads its files again. That is the intended trade.
- Requiring the F-Droid jobs by name changes nothing today, because the run-level check already
  required every job to succeed; it only stops a silent loss of the gate.

## Release intent

`release:patch` (repository default; the manifest change alters backup behavior). The version
depends on merge order with the other audit PRs and is an estimate until reserved.

Owner action outside this PR: to make the F-Droid jobs block merging as well as releasing, add
`fdroid-build` and `fdroid-device-tests` to the required status checks of the `main` ruleset
(Settings → Rules → Rulesets).

## Implementation result

As planned.

## Validation result

See the PR description for local commands and the final-head CI result. The backup behavior was
not checked on a phone.
