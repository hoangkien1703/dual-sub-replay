# Preview APKs for fork pull requests

## Status

Owner request 2026-10-28, after PR #167 (from a fork) got CI but no preview APK: "i want from now
the fork PR will have apk file too, the same way as us do".

## Behavior

- `pr-preview-auto.yml` publishes `DualSub-Replay-PR<N>-build<run>-preview.apk` to the rolling
  `preview` release for fork PRs as well as same-repository PRs.
- GitHub leaves `workflow_run.pull_requests` empty for runs from forks, so the PR is found by
  the run's head commit among open PRs. A run is published only while its PR is open and its head
  is still that commit, so an older build never replaces a newer one.
- Closed-PR cleanup uses `pull_request_target`, because a fork's `pull_request` event only gets a
  read-only token. That job checks out no PR code; it only deletes assets named after the PR number.
- Nothing from the artifact is executed in the publishing job. Fork PR CI still runs only when
  GitHub's fork-workflow approval setting allows it.

## Acceptance criteria

1. A green fork PR run uploads its preview APK under its PR number.
2. Same-repository PRs behave as before.
3. A run whose PR moved on, closed, or cannot be found publishes nothing.
4. Closing a fork PR removes its preview APKs.

## Validation

- The lookup step ran locally against PR #167's current head: it found #167 from the fork.
- YAML parses; end-to-end needs the next fork PR run (the workflow runs from `main`).

## Release intent

`release:skip`: only CI, docs and repo rules change, so the APK is the same (owner 2026-10-28).

## Release rule change (same PR)

Owner 2026-10-28: "you can change the rule to allow skip the app release when apk app doesn't
change". AGENTS.md, `docs/specs/README.md` and the PR template now say: apply `release:skip`
without asking when no changed file can reach the shipped APK (`app/src/main/**`, app/root
Gradle files, `gradle.properties`, `gradle/**`, `app/proguard-rules.pro`).
