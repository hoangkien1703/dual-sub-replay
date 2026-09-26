# Discoverability: landing site and search metadata

## Status

Implemented. The owner asked in [#53](https://github.com/hoangkien1703/dual-sub-replay/issues/53)
and in the project thread for the app to reach more users, gain GitHub stars, and rank
on Google for searches like the app. That covers this repository-side scope; merge and
release remain the owner's decision.

## Context / problem

The only public surfaces are the GitHub repository and release pages. There is no
website (`has_pages` is false), so Google has no page written for queries such as
"dual subtitles YouTube Android", "bilingual subtitles app", or Vietnamese searches like
"phụ đề song ngữ YouTube". The README has good content but little of the vocabulary
learners search with, and no FAQ.

## Goals

- A fast, static landing page in English and Vietnamese with search metadata
  (title, description, canonical, hreflang, Open Graph), `MobileApplication` and
  `FAQPage` structured data, and a sitemap.
- Automatic publishing to GitHub Pages from `main`, independent of app releases.
- Optional Search Console verification without a code change.
- README intro and FAQ that use the words people search for.
- A prioritized checklist of steps only the owner can take.

## Non-goals

- No app code, UI, or behavior change.
- No custom domain, analytics, or tracking on the site.
- No fabricated ratings, review counts, or download numbers.
- No changes to the release workflow or version constants.

## User-visible behavior

App: unchanged. Repository: README gains a keyword-rich intro, links to the website,
and a short FAQ. New website at `https://hoangkien1703.github.io/dual-sub-replay/`
once the owner enables Pages.

## Technical constraints / invariants

- Architecture invariants are unaffected; nothing under `app/` changes.
- The Pages workflow runs only on `main` in this repository, has `contents: read` for
  the build job, and grants `pages: write`/`id-token: write` only to the deploy job.
- `robots.txt` is omitted on purpose: crawlers read it only at the host root
  (`hoangkien1703.github.io/robots.txt`), which a project site cannot control. The
  sitemap is submitted through Search Console instead.

## Proposed approach / plan

1. `site/`: `index.html`, `vi/index.html`, `styles.css`, `favicon.svg` (from the
   launcher icon), `sitemap.xml`.
2. `.github/workflows/pages.yml`: copy `site/` plus four images from `docs/images/`,
   optionally inject the `google-site-verification` meta tag from the
   `GOOGLE_SITE_VERIFICATION` repository variable, upload, and deploy.
3. `tools/tests/test_site.py`: offline checks that run with the existing
   `tools/tests` discovery in Android CI.
4. README intro, FAQ, and website section; tech-stack row; owner checklist in
   `docs/promotion/discoverability.md`.

## Acceptance criteria

- [x] Both pages have a title of at most 65 characters, a description of 70–160
      characters, a canonical URL, reciprocal `en`/`vi`/`x-default` hreflang links, and
      Open Graph tags with an absolute image URL.
- [x] Each page's JSON-LD parses, contains `MobileApplication` (price 0, no ratings) and
      `FAQPage`, and every FAQ answer is identical to the visible answer.
- [x] Every local link or image resolves in the published layout, and each image is one
      the workflow copies.
- [x] The sitemap lists exactly the two pages.
- [x] The site's language list equals the app's `TranslationLanguages.kt` list (59).
- [x] No horizontal scroll at 390 px or 1280 px and no failed requests when the
      assembled site is served locally.
- [ ] After merge with Pages enabled, the workflow deploys and both URLs return 200.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` passes | Local, and PR CI `verify-build` |
| Workflow | `pages.yml` parses as YAML; build job assembles `_site` as expected | Local; deploy only verifiable after merge |
| Rendering | Serve assembled `_site` under `/dual-sub-replay/`, load both pages in Chromium at 390 and 1280 px | Local Playwright |
| Android lint/build, managed device | Unchanged app; existing PR CI still runs | PR CI |
| Physical device / live YouTube | Not applicable: no app change | — |

## Risks / edge cases

- The first deploy fails if Pages is not set to "GitHub Actions"; re-run after enabling.
- `actions/upload-pages-artifact@v3` and `actions/deploy-pages@v4` use version tags,
  not commit SHAs like the other workflows; pin them if the owner prefers.
- The Language Reactor FAQ names a third-party product for comparison and states
  that the app is not affiliated.

## Release intent

`release:patch` (the default; no label). No explicit owner intent was given. This PR
changes no app code, so `release:skip` is a reasonable alternative if the owner prefers
not to publish an identical APK.

## Implementation result

Implemented as planned. The feature-card grid uses `minmax(min(280px, 100%), 1fr)` so it
cannot overflow on very narrow screens.

## Validation result

- `tools/tests` (29 tests, including 6 new site tests): passed locally.
- `pages.yml` YAML parse: passed. Local `_site` assembly mirrors the workflow's copy step.
- Chromium at 1280×900 and 390×844 (English and Vietnamese): `scrollWidth` equals the
  viewport width, no failed requests.
- Deploy and live URLs: not run; requires merge and Pages enabled by the owner.
- Android CI: see the PR's final-head checks.
