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

<!-- Follow explicit owner intent; otherwise default patch, including docs/workflow PRs.
Use at most one actual GitHub label: release:patch / release:minor / release:major /
release:skip, OR one standalone Release-Version: X.Y.Z line outside comments/code fences.
Writing a label here or checking a box does NOT activate it. Verify saved metadata;
report any required label that could not be applied before handoff. Never leave an
active example exact-version directive. Do not manually bump Gradle version constants.
See AGENTS.md for handoff and README.md for release recovery.
-->

## Notes for reviewers

<!-- Risks, unresolved validation, and plan deviations. Owner reviews preview/green
final-head CI and manually merges; opening this PR does not authorize merge/publication. -->
