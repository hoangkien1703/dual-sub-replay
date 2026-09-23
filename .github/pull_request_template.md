## What changed

<!-- Describe the focused change and why it is needed. -->

## User-facing result

<!-- Add screenshots or a recording for visible changes. Write “No UI change” when applicable. -->

## Verification

- [ ] `testDebugUnitTest`
- [ ] `lintDebug`
- [ ] `assembleDebug`
- [ ] `assembleDebugAndroidTest`
- [ ] Relevant managed-device or manual checks completed, or not applicable with an explanation
- [ ] No unintended version or signing changes

## Release

- Release intent and reason:
- Expected version (estimate until reserved; or explain why unavailable / skipped):
- Applied GitHub release label or exact-version directive (default: no override):

<!-- Default: automatic patch release after the owner merges with green Android CI.
For another version, add exactly one release:minor / release:major / release:skip label,
or put a standalone directive OUTSIDE this comment, for example:
Release-Version: 1.2.0
Use at most one release label OR one directive, never both. Labels must be applied
on GitHub; writing a label name here does not activate it. Use patch by default,
including docs/workflow PRs, unless the user requests otherwise. Keep any exact-version
directive outside code fences too. Do not include an active example directive.
Do not manually bump Gradle version constants. See AGENTS.md for PR preparation
and README for retry behavior.
-->

## Notes for reviewers

<!-- Call out risks, limitations, follow-up work, or areas that deserve close review. -->
