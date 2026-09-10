> Superseded: the user rejected this engine on their phone in landscape. PR #63 now restores the v0.9.5 timing path; see [current restoration and checks](../v095-highlighting.md). This document records the earlier attempt.

# Preview-16 timing engine port

The user confirmed that `highlight-dual-sub-app` release `preview-16` provides the desired word timing on their phone. This port replaces the unsuccessful Adaptive/Live/Transcript arbitration in PR #63.

Reference: https://github.com/hoangkien1703/highlight-dual-sub-app/tree/ecceeffbaeb52e495f3cf82c1d71e6916d61d872

## Behavior retained

- JSON3-first word selection with the lab's 60 ms render lead.
- While timed-text is loading/unavailable, the lab's rolling DOM state and 320 ms media-time word progression.
- Frame-driven selection inside the existing YouTube page, with direct word events to Android.
- The reference's chunk parsing and estimation rules. This is behavioral parity with the phone-tested engine, not a claim that every source word boundary is acoustically exact.

## Integration changes

- An origin-restricted AndroidX WebMessage listener accepts main-frame YouTube messages only. Events carry video, language, URL, revision and a fresh timestamp; stale/foreign/malformed events are rejected.
- Android maps the chosen token onto existing subtitle segments. Transcript timing no longer selects or overrides the highlighted word. Existing transcript layout, translation and replay remain available.
- A watchdog uses the same engine with `video.currentTime` when frame callbacks stop (including native fullscreen). Pauses use the stationary media time. Seeking resets word state; navigation/language changes invalidate outstanding timed-text requests.
- Automatic source-language selection follows the current app language. The old timing-mode controls and persisted selection are removed. Only the existing word-highlight on/off switch remains.
- Live subtitle recovery uses the same engine word index, with character spans separate from replay segments.

## Validation

- The 12 original JavaScript algorithm tests are retained.
- Five installed-engine tests cover loading fallback, rolling captions, pause/speed, fullscreen watchdog, seek clearing, disabling, foreign origins and stale fetches.
- Native unit tests validate message bounds, freshness, video identity and word mapping.
- The offline Android WebView fixture requires a word update through the actual bridge without native playback polling.
- The existing UI test verifies that all three obsolete timing-mode selectors are absent.
- `formatCheck` now also runs the Node tests; Node 18+ is needed for that gate (no npm dependencies).
- Existing detekt baseline signatures were updated only for the removed timing parameters and added caption-language parameter; no new baseline findings or thresholds were added.

## Phone acceptance

Use the PR #63 preview APK and enable word highlighting. There is no timing-mode choice.

1. Compare https://www.youtube.com/watch?v=obQgWiSX8tY with lab preview-16, including 00:20–00:30 and the passage shown in the user's screenshot.
2. Check normal speed, 1.5x/2x, pause/resume and seek in both directions.
3. Repeat in portrait and fullscreen, using Short paired phrases and Whole sentence.
4. Change video/source language and confirm no old words flash; hide/show the subtitle panel and confirm updates resume.

CI fixtures do not contact live YouTube and cannot establish acoustic accuracy on the user's phone. That comparison remains the acceptance step.
