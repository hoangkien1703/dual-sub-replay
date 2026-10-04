# Promo video and README refresh

## Status

Implemented. The owner asked in the project thread on 2026-10-04 for a new promotional video and an updated README, with suggestions. That covers this scope; merge and release remain the owner's decision.

## Context / problem

The 30-second promo video, README GIF and poster were rendered on 2026-08-26 for v0.9.2. They show dual subtitles, tap-to-replay and fullscreen only. Since then the app gained word and phrase lookup with Japanese grammar, saved-word practice, progress and daily goals, and eight interface languages, none of which appear in the media. The README had grown to mix user information with detailed CI and release operations, so the learner-facing pitch was spread out and the newer features were buried in long paragraphs.

## Goals

- A new vertical promo video (about 50 s) that shows the newer learning features.
- A README GIF that shows both dual subtitles and word lookup at a smaller file size.
- A README that leads with the pitch, demo and download, groups features by what a learner does, and moves operations detail out of the way.
- Renders that are reproducible on any OS (bundled fonts) and documented.

## Non-goals

- No app code, UI, string or behavior change.
- No new screen recording: the container cannot run the app against live YouTube. Scenes that the August recording predates are recreated in HTML from the app's strings and theme.
- No change to release automation, version constants, or the website pages.

## User-visible behavior

Docs and media only. README: new structure, new GIF and demo video. `docs/releasing.md` now holds the CI and release section that was in the README; links to it are updated. The social preview image is re-rendered with the same text in the bundled Roboto font.

## Technical constraints / invariants

- Website tests (`tools/tests/test_site.py`) require every `docs/images` file the Pages workflow copies to exist; file names stay the same.
- Links in docs that pointed at `README.md#automatic-official-releases` must keep working.

## Proposed approach / plan

1. Extend `tools/promo-video`: theme module with bundled Roboto and Noto Sans JP (`@fontsource`), recreated screens in `src/AppScreens.tsx` (word lookup, Practice, Progress) using real strings, a still cropped from `docs/images/dualsub-replay-landscape.png`, and a new scene timeline.
2. Rebuild the README loop as 14 s (real dual-subtitle footage, then word lookup) at 400 px.
3. Update `validate.mjs` targets and the tool README.
4. Rewrite README.md; move CI and releases to `docs/releasing.md`; update the two inbound links.

## Acceptance criteria

- [x] `npm run validate` passes: H.264, 1080x1920, 30 fps, yuv420p, about 51 s, silent, fast start, MP4 under 20 MiB, GIF 400x712 at 12 fps under 6 MiB.
- [x] `npm run validate:links` passes for every local README link (remote links are checked where the network allows).
- [x] README shows the GIF, demo link and APK download above the fold, and lists word lookup, Practice and Progress.
- [x] `python3 -m unittest discover -s tools/tests -p 'test_*.py'` passes.
- [x] No file under `app/` changes.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Media | `npm ci && npm run render && npm run validate` in `tools/promo-video` | Linux container, local headless Chromium |
| Links | `npm run validate:links` | Same |
| Website/release tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` | Same |
| Visual review | Contact sheet `out/promo-contact-sheet.jpg` and sampled frames checked by eye | Same |
| Android build/tests | Not applicable: no app change; PR CI still runs | GitHub Actions |

## Risks / edge cases

- Recreated scenes can drift from the real UI after future app changes. The tool README says which scenes are recreated and where their strings come from.
- `@fontsource/noto-sans-jp` adds about 100 font files to the render bundle; only `node_modules` grows, nothing is committed.

## Release intent

Default `release:patch` per AGENTS.md (no owner instruction yet). Recommended alternative: `release:skip`, because nothing in the APK changes. The owner decides before merge.

## Implementation result

As planned, plus one owner follow-up: most users are not technical, so the README now opens with a large Download for Android button, the preview link and simple install steps, then the video and screenshots; the badges moved to For developers. A second follow-up (after merging PR #102) brought the badges back under the pitch and added a second Download for Android link after the screenshots, for readers who scroll past the first button. The owner also made `release:skip` automatic for docs and promo-media-only PRs; AGENTS.md, the spec guide and the PR template record the rule. The promo is 51 s (intro, dual subtitles, replay, word lookup, Practice, fullscreen, Progress, end card). The GIF dropped from 7.2 MB at 480 px to a 400 px, 14 s loop.

## Validation result

- Passed: `npm ci`, `npm run render` and `npm run validate` (24 checks) in a Linux container with local headless Chromium. Promo MP4 51 s, 13.3 MiB; GIF 400x712, 168 frames, 4.3 MiB (was 6.9 MiB).
- Passed: `npm run validate:links`, 32 local and remote README links. The link pattern now matches only Markdown link targets, so prose in parentheses such as "Português (Brasil)" is no longer read as a path.
- Passed: `python3 -m unittest discover -s tools/tests -p 'test_*.py'`, 36 tests.
- Passed: visual review of the contact sheet and sampled frames.
- Not applicable: Android build and device tests (no app change); PR CI runs them anyway.
