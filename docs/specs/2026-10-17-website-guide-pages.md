# Website guide pages and accurate privacy wording

## Status

Implemented. The owner asked in the project thread (2026-10-17) to use
[claude-seo](https://github.com/AgriciDaniel/claude-seo) to bring more users and GitHub
stars. Builds on the [discoverability spec](2026-09-26-discoverability-landing-site.md).
The two guide pages follow the recommended option on the decision card; the owner can
still drop them before merge.

## Context / problem

claude-seo audits websites, so only the GitHub Pages site applies. Its basic checks
(title, description, canonical, hreflang, Open Graph, JSON-LD, sitemap, Search Console)
already passed. The audit found:

- The FAQ "Does translation send my subtitles to a server?" answered "No", and the
  JSON-LD, the Vietnamese description and feature card, the README and the launch kit
  said translation runs only on the phone. Google Translate (online) has been the default
  since the [2026-10-05 change](2026-10-05-opt-in-google-translate.md).
- The site did not mention Japanese grammar, screen-off play or the AI assistant.
- One page per language can rank for only a few searches. Learners search for
  "learn Japanese with YouTube" and "Language Reactor Android", which no page targets.

## Goals

- Correct every statement about where translation happens.
- Show the newer features on the home pages.
- Add a "Learn Japanese with YouTube" page and a "Language Reactor alternative for
  Android" page, in English and Vietnamese, linked from the home pages.

## Non-goals

- No app change, custom domain, analytics, or ratings in structured data.
- No outward actions (repository description, topics, posts); those stay owner steps.

## User-visible behavior

Website only: corrected privacy answer, three new feature cards and a Japanese FAQ
entry on the home pages, four new guide pages, and a "Guides" footer line.

## Technical constraints / invariants

- Every page keeps the checks in `tools/tests/test_site.py`: title ≤ 65 characters,
  description 70–160, canonical, reciprocal en/vi/x-default hreflang per page pair,
  `MobileApplication` (price 0, no ratings) plus `FAQPage` with answers identical to the
  visible text, and local links that resolve in the published layout.
- Claims about Language Reactor are limited to what its own channels say (desktop
  Chrome extension; no mobile app, per its team in March 2026), and the pages state
  that the project is not affiliated.

## Proposed approach / plan

1. Fix the wording in `site/`, `README.md` and `docs/promotion/launch-kit.md`.
2. Add `site/{,vi/}learn-japanese/` and `site/{,vi/}language-reactor-alternative/`
   using the existing stylesheet and layout, plus a word-card image cropped from the
   promo video (`docs/images/dualsub-replay-japanese-word-card.webp`, copied by
   `pages.yml`).
3. List the pages in `sitemap.xml` and generalize `test_site.py` to page pairs.

## Acceptance criteria

- [x] No site, README or launch-kit text says translation never leaves the phone.
- [x] All six pages pass `test_site.py`, and the sitemap lists exactly those six.
- [x] No horizontal scroll at 390 px on any page; layout checked at 1280 px.
- [ ] After merge, the Publish website workflow deploys and the four new URLs return 200.

## Validation plan

| Category | Command/scenario and expected result | Environment |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py'` passes | Local and PR CI |
| Workflow | `pages.yml` parses; assembled `_site` contains the new image | Local |
| Rendering | Serve `_site` under `/dual-sub-replay/`; Chromium at 390 and 1280 px | Local |
| Android build and device tests | App unchanged; existing PR CI still runs | PR CI |

## Risks / edge cases

- Language Reactor may ship a mobile app later; the page then needs updating.
- Duplicate `MobileApplication` data on several pages is allowed; each page has its own
  canonical URL.

## Release intent

The PR changes `pages.yml` and `tools/tests/test_site.py`, so the standing
`release:skip` rule for docs-only PRs does not apply on its own. No app code changes;
the release label is the owner's call (patch by default, `release:skip` suggested).

## Implementation result

Implemented as planned.

## Validation result

- `tools/tests`: 34 tests passed locally.
- `pages.yml` YAML parse: passed.
- Chromium, all six pages at 390 px: `scrollWidth` equals `clientWidth`; screenshots at
  1280 px checked by eye.
- Deploy and live URLs: not run; requires merge.
