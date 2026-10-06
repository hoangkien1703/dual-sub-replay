# Project mission

DualSub Replay helps Android users learn languages through YouTube: read original
and translated subtitles together, tap a sentence to replay it, and save words and
context for later practice. See the [product guide](../../README.md) for features.

## Product principles

- Keep YouTube playback, captions, and replay reliable. The learning controls build
  on the existing YouTube page; this is a language-learning app, not a project to
  replace YouTube's general browsing/player experience.
- Keep study data local and on-device translation always available with downloadable
  ML Kit models. No project-operated backend, application account, or user-provided API
  key is required. Avoid adding unnecessary service dependencies. The owner approved one
  exception on 2026-10-05: the GitHub build translates with Google Translate (online) by
  default for better quality, with a setting to translate on the device instead, and it
  offers on-device again when Google fails. The F-Droid build stays on-device.
- Be precise about privacy: YouTube browsing/captions and model downloads use the
  network; speech engines may use online voices. On-device translation does not
  make the entire app offline or prevent Android backup of local study data.
- Preserve existing users' vocabulary, review progress, settings, and migrations.
  Keep production signing continuity so official releases can update installed apps.
- Prioritize readable subtitles, accessible controls, useful error recovery, and
  mobile usability in portrait, landscape, and fullscreen.
- Prefer focused improvements over architecture complexity. Preserve the
  [technical invariants](tech-stack.md#architecture-invariants) when extending learning features.

These principles summarize the current README, [roadmap](../../ROADMAP.md), and
implementation; they do not promise new features. ROADMAP.md remains the sole
directional roadmap. Record a specific change in a [feature spec](../specs/README.md)
and update this document only when an authorized decision changes lasting intent.
