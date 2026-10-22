# Security check fixes: AI service web search, Dependabot, SECURITY.md

## Status

Implemented. In the "App security check" project thread (2026-10-21) Kien asked whether the app
has security vulnerabilities. The audit of main (v1.7.2, merge of #138) found one medium and two
low findings; Kien picked "All three" and added that **Full access for the assistant should still
accept changes without a tap**. Merge and release stay with the owner.

## Context / problem

1. **Medium: the AI service's own web search was not counted as outside text.** Without full
   access, setting and memory changes wait for the user's tap once the answer has read text the
   user did not type (subtitles, files, the app's YouTube search, Tavily results, a screenshot).
   Claude's `web_search` and OpenRouter's `openrouter:web_search` run inside the service's reply;
   `AiAnswerRun.run()` recorded their queries and sources but never set `otherText`. A web page
   could therefore tell the model to change a setting or save a memory, and it ran at once.
2. **Low: no dependency update automation.** Actions are pinned to commit SHAs and no library has
   a known vulnerability (OSV, 2026-10-21), but nothing reports new versions or advisories.
3. **Low: no `SECURITY.md`,** so there is no documented private way to report a problem.

The Node.js security release of 2026-10-21 does not affect the app: no Node.js ships in the APK
and no workflow installs Node. Only `tools/promo-video` uses it on the owner's PC (`npm audit`: 0).

## Goals

- A service reply that searched the web (queries or cited sources) counts as outside text, so
  without full access its setting and memory changes wait for a tap, like Tavily results.
- With full access on, only removing a key and resetting settings wait for a tap, even after
  subtitles, web pages, files or screenshots were read (owner decision, 2026-10-21). The Full
  access warning says so in all 8 interface languages.
- Dependabot opens monthly grouped update PRs for Gradle, GitHub Actions and the promo-video npm
  packages (`release:skip` for promo-video, per AGENTS.md).
- `SECURITY.md` points to GitHub's private vulnerability reporting.

## Non-goals

- Turning on Dependabot alerts, secret scanning or private vulnerability reporting: those are
  repository settings only the owner can change.
- Any other change to the assistant's tools or prompts.

## Acceptance criteria

- [x] Without full access, a setting change in a reply that carries Claude-style web queries, or
  OpenRouter-style cited sources only, waits for a tap (`AiAnswerRunTest`).
- [x] With full access, an AI setting change after `look_at_video` runs at once; a saved word
  delete after `find_saved_words` runs at once, and a reset in the same reply still waits.
- [x] Existing behavior: Tavily results still make later setting changes wait; a key the user
  did not type still cannot be added.

## Validation

| Category | Command/scenario and result |
| --- | --- |
| Unit tests | `testDebugUnitTest` for `assistant.*` and `ui.AiAssistant*`: see PR. |
| CI | Full PR CI (format, lint, unit, device tests) on the PR head. |
| Phone | Not run in the cloud: owner tests the preview APK. |
