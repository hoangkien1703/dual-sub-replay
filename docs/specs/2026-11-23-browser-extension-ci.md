# Browser extension CI check

## Status

Implemented. Owner pick 2026-11-23 on PR #253: ask the contributor for changes, then merge the extension as a
preview; workflow edits come from the owner's branch, not the fork.

## Context / problem

PR #253 adds `browser-extension/`, a Manifest V3 Chrome extension in plain JavaScript with Node unit tests
(`node --test`). `android.yml` builds and tests only the Android app, so nothing in CI runs the extension's tests.

## Goals

- Every PR and main push that changes `browser-extension/**` runs `node --check` on each `.js` file and `node --test`.

## Non-goals

- Skipping the Android jobs on extension-only PRs. `tools/automatic_release.py` (`verify_ci`) requires both
  `verify-build` and `managed-device-tests` to succeed on a merged PR's final head, `release:skip` included, and a
  skipped job does not qualify. Changing that release safety check is out of scope.
- Making the job a required status check.

## User-visible behavior

No app change. A new `Browser extension checks / extension-tests` check appears on PRs that touch the extension.

## Technical constraints / invariants

- `permissions: contents: read`, checkout without persisted credentials, pinned to the SHA `android.yml` uses.
- No third-party action, no `npm install`: the runner's preinstalled Node runs the tests, so a fork PR can't pull
  in packages.
- Runs on `pull_request` (not `pull_request_target`), so fork runs still wait for the owner's approval and get a
  read-only token.

## Acceptance criteria

- [x] On a commit without `browser-extension/` the job passes and says there is nothing to check (this PR).
- [ ] On PR #253 after it merges `main`, the job runs the syntax check and all extension tests.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Workflow | This PR's own run passes with the "nothing to check" message | GitHub Actions |
| Extension | The same commands on PR #253's head (`ea8fb96`): `node --check` on every file, `node --test` 7/7 pass | Cloud container, Node 22 |

## Release intent

`release:skip`: workflow and docs only; the APK cannot change.
