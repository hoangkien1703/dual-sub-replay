# Remove the main preview APK

## Status

Approved for implementation. On 2026-10-18, after v1.6.2, the owner asked in the project thread
whether `DualSub-Replay-preview.apk` was still needed and said: "if we don't need that preview apk
i want to remove it from CI". That does not authorize merging or publishing.

## Context / problem

Every owner merge into `main` ran **Release merged PR**, which built a debug APK of `main` and
uploaded it to the `preview` release as `DualSub-Replay-preview.apk`, next to the numbered test
APKs of open PRs. Since every owner merge now also publishes an official release (unless
`release:skip`), that APK is almost always the same app as the latest release. It costs a
build on every merge and makes the preview page harder to read.

## Goals

- Merges into `main` no longer build or upload `DualSub-Replay-preview.apk`.
- A `release:skip` merge publishes nothing and starts no build job.
- Official releases and PR preview APKs (`DualSub-Replay-PR<N>-build<run>-preview.apk`, removed
  when the PR closes) are unchanged.

## Non-goals

- No change to PR CI, its `DualSub-Replay-preview` artifact (which `pr-preview-auto.yml`
  downloads) or the preview package id.
- The session cannot edit releases, so the existing `DualSub-Replay-preview.apk` asset and the
  preview release text are changed by the owner on GitHub.

## Plan

1. `release-on-main.yml`: remove the preview signing-key cache and the build, verify and publish
   steps for the main preview; run `publish-main` only when a release is needed, and drop the
   now-redundant per-step `release_needed` conditions.
2. Rename the `release:skip` unit test, which no longer keeps a preview.
3. Update AGENTS.md, README.md, `docs/releasing.md`, `docs/project/tech-stack.md` and
   `docs/specs/README.md`.

## Acceptance criteria

- [ ] No workflow builds or uploads `DualSub-Replay-preview.apk` to a release.
- [ ] `publish-main` runs only when `prepare` reports `eligible` and `release_needed`.
- [ ] `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` passes and the workflow
  YAML parses.
- [ ] Both Android CI jobs pass on the final head.

## Validation plan

| Category | Command/scenario and expected result | Environment |
| --- | --- | --- |
| Workflow tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` passes | Local, CI |
| YAML | The changed workflow parses | Local |
| CI | `verify-build` and `managed-device-tests` pass | CI |
| After merge | The merge's **Release merged PR** run does not upload `DualSub-Replay-preview.apk` | GitHub |

## Release intent

Patch by default (a workflow change with no explicit owner intent, per AGENTS.md). A patch
release would publish the same app as v1.6.2 under a new number, so `release:skip` is
recommended and put to the owner.

## Implementation result

As planned. `automatic_release.py` is unchanged: a skip merge still reports `eligible` with
`release_needed` false, and the job condition now skips the build. The removed preview step was
the one that created the `release/` folder, so the official packaging step now creates it.

PR CI does not run **Release merged PR**, so the release path is first exercised by the next
non-skip owner merge; a failure there is retried from the same reservation (see
`docs/releasing.md`).

## Validation result

- `python3 -m unittest discover -s tools/tests -p 'test_*.py'`: 34 tests pass. All workflow
  YAML files parse.
- Android CI: see the PR's checks on the final head.
