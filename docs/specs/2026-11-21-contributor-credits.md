# Credit people who help through Discussions and Issues

## Status

Implemented. Owner authorized the plan on 2026-11-21 (all-contributors table in its own file, thank-you lines in release notes, co-author credit only for features that were clearly someone's idea).

## Context / problem

Many features came from people who never opened a pull request: lion737 and 25kiuo in [Discussion #81](https://github.com/hoangkien1703/dual-sub-replay/discussions/81), and issue authors such as Rozen-x (#152) and musdarrt-ctrl (#184). GitHub's contributor graph only counts commit authors, so they were invisible. The owner also worried that a contributors table could make README very long with thousands of people.

## Goals

- A public list of everyone who helped, with the kind of help (ideas, bug reports, testing, code).
- README stays short no matter how many people are listed.
- A release that ships someone's idea thanks them by name without anyone editing release notes by hand.
- A feature that was clearly someone's idea credits them as a commit co-author.

## Non-goals

- Installing the all-contributors GitHub App (it needs write access; agents run the CLI instead).
- Crediting votes, "+1" comments or ideas that have not shipped.
- Rewriting the notes of past releases.

## User-visible behavior

No app change. README gets a contributors badge and one sentence linking to the new CONTRIBUTORS.md. Release notes of PRs with a `Thanks:` line start with "🙏 Thanks to @a and @b for helping shape this release."

## Proposed approach / plan

1. `.all-contributorsrc` lists people and their contribution types; `files` points at CONTRIBUTORS.md (full table) and README.md (badge only, no list markers).
2. `tools/automatic_release.py` reads `Thanks: @login` lines from the merged PR body (comments ignored, owner and non-logins skipped, at most 20 names) and puts the thank-you line before GitHub's generated notes.
3. AGENTS.md "Crediting contributors", the PR template's Credits section, CONTRIBUTING.md and docs/releasing.md describe the habit and the co-author rule.
4. `tools/tests/test_contributors.py` keeps the generated files in sync with the config.

First entries, each checked on GitHub on 2026-11-21: hoangkien1703 (owner), mrAlishah (code: PRs #167, #233 and the Triple Sub Replay port), lion737 (many shipped #81 requests, bug reports with recordings, testing), 25kiuo (#81: sleep timer, IPA, whole transcript, custom repeat; background repeat bug), lovoro (#81: sentence loop and centred transcript, PR #195), Rozen-x (#152), musdarrt-ctrl (#184), jarsin918 (#115, PR #119), xmha97 (#88). Not yet listed: FelixEhrlichKIT (word-by-word gloss is still in the backlog), Aglimun (#202, not planned), parastouh (PR #253 still open).

## Acceptance criteria

- [x] README holds only the badge and a link; the table is in CONTRIBUTORS.md (test checks README has no list markers).
- [x] `Thanks: @lion737, @25kiuo` in a PR body adds "🙏 Thanks to @lion737 and @25kiuo for helping shape this release." to the reserved release's notes, before GitHub's generated notes.
- [x] `Thanks:` inside template comments, the owner's own login, e-mail addresses and invalid logins add nothing.
- [x] CONTRIBUTORS.md and the README badge match `.all-contributorsrc`.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` passes | Cloud container and PR CI |
| Android lint/build | Not applicable: no app file changes | |
| Documentation/process | `npx all-contributors-cli@6.26.1 generate` leaves the files unchanged; links render on GitHub | Cloud container |

## Risks / edge cases

- `@login` in release notes notifies that person, which is the point; the 20-name cap keeps a mistaken line from pinging many people.
- The release tool runs from `main`, so the first release after this merges is the first real check of the thank-you line.

## Release intent

`release:skip`: only docs, tools and tests change, so the APK cannot change.

## Implementation result

As planned.

## Validation result

- `python3 -m unittest discover -s tools/tests -p 'test_*.py'`: passed locally (new thanks and contributors tests included).
- `all-contributors generate` run after the last config edit; output committed.
- PR CI: see the PR.
