## What changed

<!-- Explain the problem, focused change, and why it is needed. -->

## Spec and acceptance criteria

- Spec: <!-- docs/specs/YYYY-MM-DD-name.md, or Not required — reason -->
- Criteria checked and result: <!-- Link spec results, or give brief criteria here. -->

## User-facing result

<!-- Screenshots/recording for visible changes; say “No UI change” for infrastructure/docs. -->

## Verification

<!-- List relevant commands/scenarios and actual pass/fail/not-run/not-applicable results.
Consider formatCheck, complexityCheck, testDebugUnitTest, lintDebug, assembleDebug,
assembleDebugAndroidTest, relevant managed-device/emulator and manual scenarios.
Separate physical-device, live YouTube and performance evidence from offline fixtures.
For docs-only work, check links/consistency/template behavior; existing PR CI still applies.
-->

- Local validation and remaining gaps:
- Latest final-head CI / preview status:
- Version/signing changes: <!-- Normally none; explain intentional exceptions. -->

## Release decision

- Intent and reason:
- Expected version: <!-- Estimate until reserved, or explain skipped/unavailable. -->
- Actual applied GitHub label or exact-version directive: <!-- Default: no override. -->

<!-- Follow explicit owner intent; otherwise default patch when the APK can change.
PRs that cannot change the APK get release:skip automatically (see AGENTS.md).
Use at most one actual GitHub label: release:patch / release:minor / release:major /
release:skip, OR one standalone Release-Version: X.Y.Z line outside comments/code fences.
Writing a label here or checking a box does NOT activate it. Verify saved metadata;
report any required label that could not be applied before handoff. Never leave an
active example exact-version directive. Do not manually bump Gradle version constants.
See AGENTS.md for handoff and docs/releasing.md for release recovery.
-->

## Checklist

<!-- Tick what is true; leave the rest unticked and explain in Notes. See CONTRIBUTING.md "How review works". -->

- [ ] One focused change; unrelated fixes are in their own PR.
- [ ] Screenshots or a recording above for visible changes (or "No UI change").
- [ ] Tried on a real phone (name it in Verification), or said why not.
- [ ] Android CI is green on the latest commit (`verify-build`, `managed-device-tests`).
- [ ] No keys, signing files, personal data or copied caption/video dumps; no manual version bump.
- [ ] From a fork: **Allow edits by maintainers** is ticked.
- [ ] I (or my agent) will answer every review comment with a fix or a reason, and keep pushing to this branch until it is good.
- [ ] AI-written changes are marked as such, and the agent followed AGENTS.md.

## Notes for reviewers

<!-- Risks, unresolved validation, and plan deviations. Owner reviews preview/green
final-head CI and manually merges; opening this PR does not authorize merge/publication. -->
