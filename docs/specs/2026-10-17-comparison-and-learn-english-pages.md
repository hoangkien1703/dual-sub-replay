# Comparison and Learn English guide pages

## Status

Implemented. On 2026-10-17 the owner shared a post about a product that ChatGPT started
recommending because of its comparison pages, and asked to do the same. He chose "Both"
on the decision card: an honest comparison page and a Learn English page. This follows
[the first guide pages](2026-10-17-website-guide-pages.md).

## Context / problem

Search engines and AI assistants answer questions such as "free app for dual subtitles on
YouTube on Android" or "how to learn English with YouTube" by quoting pages that answer
exactly that. The site had no page comparing the available options, and none for English
learners, who are the largest group of learners.

## Goals

- `dual-subtitles-youtube-android/`: a fair comparison of DualSub Replay, Trancy, the
  Dualsub Firefox add-on, and Language Reactor, with sources and a "Last checked" date.
- `learn-english/`: how to learn English with YouTube using the app.
- English and Vietnamese versions, linked from every page's footer and the home FAQ.

## Non-goals

- No app change, no ratings or invented numbers, and no claims about competitors
  beyond what their own pages say.

## User-visible behavior

Website only: four new pages, a comparison table, and updated footer links.

## Technical constraints / invariants

- All pages keep the `tools/tests/test_site.py` checks; the new pairs are added to
  `PAGE_PAIRS` and `sitemap.xml`.
- The comparison table scrolls inside its own box on narrow screens, so the page never
  scrolls sideways.
- Competitor facts, checked 2026-10-17: Trancy has Android and iOS apps; its free plan
  includes bilingual subtitles, saves up to 100 words and 50 sentences, and keeps AI tools
  for Premium (from about $3.49 a month, by region), per its plans page and FAQ. Dualsub's
  Firefox Add-ons listing is compatible with Firefox for Android, and its Native Mode is
  free. Language Reactor's team said in March 2026 that there is no mobile app.

## Proposed approach / plan

1. Generate the four pages with the same layout as the existing guide pages.
2. Add a `.table-scroll` / `table.compare` style.
3. Update footers, home FAQ links, sitemap, site test pairs and the discoverability
   checklist (including Bing Webmaster Tools for AI assistants).

## Acceptance criteria

- [x] All ten pages pass `test_site.py`.
- [x] No page scrolls sideways at 390 px.
- [x] Every competitor claim links to a public source on the page.
- [ ] After merge, the four new URLs return 200.

## Validation plan

| Category | Command/scenario and expected result | Environment |
| --- | --- | --- |
| Unit tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py'` passes | Local and PR CI |
| Rendering | Serve the assembled site; Chromium at 390 and 1280 px | Local |
| Android build and device tests | App unchanged; existing PR CI still runs | PR CI |

## Risks / edge cases

- Competitor prices and plans change; the page shows a "Last checked" date, and the
  discoverability checklist asks for a periodic recheck.

## Release intent

`release:skip`. No app change. On 2026-10-17 the owner chose "Always skip" for
website-only PRs, including ones that change the website's test or Pages workflow;
`AGENTS.md` and the spec README record that standing decision.

## Implementation result

Implemented as planned. Also corrected the Japanese pages, which named a "Saved words"
screen; the menu item is Practice, and Anki export is Practice → Export → Anki text.

## Validation result

- `tools/tests`: 34 tests passed locally.
- Chromium at 390 px on all ten pages: `scrollWidth` equals `clientWidth`. The comparison
  page was checked by eye at 1280 px.
- Live URLs: not run; requires merge.
