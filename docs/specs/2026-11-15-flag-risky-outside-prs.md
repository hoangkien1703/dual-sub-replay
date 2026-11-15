# Flag risky files in outside PRs

## Status

Implemented. Owner request 2026-11-15 ("if someone sends a PR that contains harmful scripts or malware, how can we catch it?"), plan OK'd in the project thread.

## Context / problem

Fork PRs can't run CI until the owner taps **Approve workflows to run**, and the signing key lives only in the main-only `release` environment, so a PR can't publish a release. The remaining risk is a person approving a CI run, or opening the branch on a computer, without noticing that the PR changed a file that runs code: a workflow, a Gradle file, the Gradle wrapper, a script, an editor task, or a binary nobody can read. Changes to the app's permissions or new internet addresses are the in-app version of the same problem, and `AGENTS.md`-style files can steer the AI agents that review PRs.

## Goals

- Every fork PR that touches such a file gets the `needs careful review` label and one comment listing the files, before anyone approves its CI run.
- Every PR's new GitHub Actions and npm dependencies are checked against GitHub's advisory database.
- None of this ever runs, builds or checks out the PR's code with a write token.

## Non-goals

- Proving a PR is safe. The list says what a person must read; it never approves anything.
- Gradle library advisories on PRs: the dependency graph has no Gradle data yet (it would need `gradle/actions/dependency-submission`), so the comment lists added `gradle/libs.versions.toml` lines and new download sources instead.
- The owner's own branches and Dependabot PRs (in-repo heads): they get dependency review but no label.

## User-visible behavior

No app change. On GitHub, a fork PR touching a flagged file gets the red `needs careful review` label and a comment from `github-actions[bot]` grouped by: runs on CI or on your computer; instructions for AI agents; keys/signing/secrets files; binary or very large files; app permissions, exported parts or network rules; new or changed libraries; internet addresses added to app code; app code that needs a close look (system commands, code loading, `addJavascriptInterface`, Base64 decoding). Later pushes edit the same comment; when the PR no longer touches any of these, the label goes and the comment says so.

## Technical constraints / invariants

- `.github/workflows/outside-pr-flags.yml` uses `pull_request_target` so it runs from `main`, before approval, with a token that can label fork PRs. It checks out only `main`'s `tools/` (`persist-credentials: false`), reads the PR's files and diffs from the API, runs `python3 -I` on `tools/pr_review_flags.py`, and puts no PR text into a shell command. `tools/tests/test_pr_review_flags.py` fails if the workflow checks out anything else, names PR text in an expression, names an environment or secret, or uses an unpinned action.
- `pr_review_flags.py` only matches names and diff lines; every quoted piece is stripped of backticks, line breaks and control characters, so a hostile file name can't escape its code span, add headings or mention anyone.
- Like `pr-preview-auto.yml`, it runs from `main`, so a change to it shows its effect on the first fork PR after it merges.

## Acceptance criteria

- [x] Run against fork PR #233's real file list, it flags `AGENTS.md`, `app/build.gradle.kts`, the two new `tools/german/*.py` scripts and the two learner-data files GitHub shows no diff for; licence-text URLs are not listed.
- [x] Plain app code, strings, docs and site changes are not flagged (unit test).
- [x] A hostile file name stays inside one code span (unit test).
- [x] The workflow-safety tests above pass; `actionlint` (with shellcheck) reports nothing.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` passes | Cloud container; also PR CI |
| Workflow lint | `actionlint .github/workflows/outside-pr-flags.yml` clean | Cloud container |
| Live run | First fork PR after merge gets the label and comment; dependency review shows on the next PR | GitHub, after merge |
