# "Send release to F-Droid" workflow

## Status

Implemented. Requested by the owner on 2026-10-04: they want F-Droid updates without needing a
computer, and Claude sessions cannot create tags (the session's GitHub access refuses tag
writes), so each `fdroid-v` tag waited on the owner's laptop.

## Context / problem

F-Droid builds only `fdroid-vX.Y.Z` tags ([F-Droid docs](../fdroid/README.md#updates)). Creating
one needed `git tag` and `git push` from a clone, which the owner cannot easily do from a phone.

## Goals

- A manual GitHub Actions workflow that tags a published release `fdroid-vX.Y.Z`, startable from
  the Actions tab, the GitHub mobile app, or the API.
- It refuses unpublished/draft/pre-release versions and malformed input, is a no-op when the tag
  already points at the release, and never moves an existing tag.

## Non-goals

- No automatic tagging on release; the owner still chooses which releases reach F-Droid.
- No change to the release workflow or the fdroiddata recipe.

## User-visible behavior

Infrastructure only; the app does not change.

## Technical constraints / invariants

- The job has `contents: write` only and uses the workflow's `GITHUB_TOKEN`; the input reaches
  the script through an environment variable, never interpolated into shell.
- `tools/automatic_release.py` ignores tags that do not start with `v`, so the new tags start no
  release.

## Proposed approach / plan

1. `tools/fdroid_tag.py` with a testable `send_to_fdroid(api, version)`, reusing
   `GitHub` and `version_tuple` from `automatic_release.py`.
2. `.github/workflows/send-to-fdroid.yml` (`workflow_dispatch`, input `version`).
3. Tests in `tools/tests/test_fdroid_tag.py`; docs in `docs/fdroid/README.md`.

## Acceptance criteria

- [x] A published release gets `refs/tags/fdroid-vX.Y.Z` on its commit.
- [x] An existing tag on the same commit is a no-op; one on another commit fails without writes.
- [x] Missing, draft or pre-release versions and malformed input fail without writes.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Workflow tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` | Local Linux, CI |
| Workflow YAML | Parses | Local Linux |
| Live run | Run the workflow for 1.3.6 after merge; `fdroid-v1.3.6` appears on `4c76bb4` | GitHub, after merge |

## Risks / edge cases

- Anyone with write access can run it; that is only the owner today.

## Release intent

`release:patch` is the repository default. The app does not change, so the owner may prefer
`release:skip`; the PR asks.

## Implementation result

As planned.

## Validation result

- Passed: workflow tests (36, including 5 new); the workflow YAML parses.
- Not run yet: the live run, which needs the merged workflow.
