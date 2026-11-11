# Preview APK as soon as the build job passes

## Status

Implemented. The owner agreed on 2026-11-11 ("yes do that too") to the offer made after PR #216: publish the preview APK when the build passes, about 15 minutes sooner, while the device tests still gate the merge. It can only be validated end to end on the first PR after this one merges (see Validation plan). Merge needs the owner's OK.

## Context / problem

PR CI (`android.yml`) has two jobs that run side by side: `verify-build` (unit tests, lint, the preview APK; 6 to 10 minutes) and `managed-device-tests` (the emulator; about 20 minutes, up to 45). `pr-preview-auto.yml` published the APK only when the whole run had succeeded, so the owner waited for the emulator before he could install a build whose APK had been ready for a quarter of an hour. A failed device test also meant no APK at all, although the APK is often what he needs to see the problem.

## Goals

1. The preview APK is on the [preview release](https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview) as soon as `verify-build` passes.
2. The merge and the release are gated exactly as before: both jobs must be green.
3. If the early path fails for any reason, the old path (publish after the whole run succeeds) still delivers the APK.

## Non-goals

- No change to `android.yml`, `release-on-main.yml` or `tools/automatic_release.py`.
- No change to what a preview APK is, how it is named, signed or cleaned up, or to fork PR rules ([fork preview spec](2026-10-28-fork-pr-preview-apk.md)).
- CI is not made faster; only the wait for the APK is shorter.

## User-visible behavior

Infrastructure only; the app does not change.

Before: the APK appears on the preview page about 20 to 25 minutes after a push, and only when every job passed.

After: the APK appears a minute or two after `verify-build` passes, about 8 to 12 minutes after a push. The PR can still show a running or failed `managed-device-tests` check at that moment, so an APK on the page no longer means the PR is green. A build that fails `verify-build` gets no APK, as before.

## Technical constraints / invariants

- The release gate reads every job of the newest `android.yml` run for the PR head (`verify_ci` and `REQUIRED_JOBS` in `tools/automatic_release.py`, kept in step with the workflow by `test_every_android_ci_job_is_required`). Moving the publishing into `android.yml`, or splitting that workflow in two, would change what the gate sees. So only `pr-preview-auto.yml` changes.
- The publishing workflow keeps its security shape: it runs from `main`, holds the only write token, checks out no PR code and executes nothing from the artifact. `android.yml` stays read-only.
- A run is published only while its PR is open at the built commit, so an older build never replaces a newer one.
- The build job keeps the name `verify-build`; the preview workflow looks for it by name (noted in AGENTS.md).

## Proposed approach / plan

1. `pr-preview-auto.yml` also starts on `workflow_run: requested` (the CI run was just created). A first step polls that run's jobs every 20 seconds until `verify-build` ends. Success goes on to the existing publish steps; failure, cancellation, a run that ends without the job, or 35 minutes of waiting publish nothing.
2. The `completed` + success trigger stays as the fallback. It first looks at the preview release and stops when this build's APK is already there.
3. Both runs of one CI run share a concurrency group without cancelling, so the fallback waits for an upload instead of cutting it off.
4. Docs: AGENTS.md, CONTRIBUTING.md, `docs/releasing.md`, `docs/project/tech-stack.md`.

Considered and dropped: a publish job inside `android.yml` (a write token beside PR code, and one more job for the release gate), two CI workflows (the gate reads one), and `check_run` / `workflow_job` triggers (GitHub does not start workflows from checks that Actions itself created).

## Acceptance criteria

- [ ] On a PR opened after this merges, `DualSub-Replay-PR<N>-build<run>-preview.apk` is on the preview release while `managed-device-tests` is still running.
- [ ] On that PR, the fallback run after the whole CI run logs that the APK is already there and uploads nothing.
- [x] A failed or cancelled `verify-build`, a run that ends without that job and a fork run waiting for approval publish nothing (simulated).
- [x] The fallback publishes when the APK is missing, and is not fooled by another PR's or another build's asset (simulated).
- [x] `android.yml` and `tools/automatic_release.py` are unchanged; the release-automation tests pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py'` passes (release gate untouched) | Cloud |
| Workflow steps | The two changed shell steps, taken out of the YAML and run against a fake `gh` that replays job states: queued → running → success, failure, cancelled, no job, approval needed, API error then success; asset present / missing / similar name / empty release | Cloud |
| Workflow file | YAML parses; triggers, `if` and step conditions read back as intended | Cloud |
| Artifact while the run is in progress | On this PR's own CI run: the `DualSub-Replay-preview` artifact is listed and downloadable through the API after `verify-build` uploads it and before the run ends | PR CI |
| End to end | First PR after merge: both unticked criteria above | That PR's CI; the workflow runs from `main`, so this PR cannot exercise it |
| Android lint/build, device, live YouTube | Not applicable: no app code changes | n/a |

## Risks / edge cases

- A preview APK can now come from a build whose device tests fail later. Only the PR's checks say whether it is green; the docs say so.
- If the artifact cannot be downloaded before the CI run ends, the early run fails at the download step and the fallback publishes as before. Nothing is lost, only the time gain.
- The waiting job holds a runner for the length of the build job. The repository is public, so this costs no minutes; it does use one of the concurrent job slots.
- A new push cancels the old CI run; the waiting job then sees a cancelled `verify-build` and stops. If the old build had already passed, the "still at the built commit" check stops it.
- Re-running failed jobs starts both paths again; the same asset name is uploaded again or skipped.

## Release intent

`release:skip`: only a workflow and docs change, so the APK is the same.

## Implementation result

Implemented as planned, in `pr-preview-auto.yml` only.

## Validation result

Passed in the cloud: release-automation unit tests, the YAML parse, and every simulated state in the table. The in-progress artifact check and the end-to-end check are recorded in the PR (the second one after merge, by the agent that opened it).
