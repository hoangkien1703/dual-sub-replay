# Android look, guide with AI setup, grammar always shown, playing part first

## Status

Implemented. The owner reported on 2026-11-21 that grammar did not show and the UI needed work, and asked for a guide
to using AI. They also reported that subtitles fell behind the video when a video was opened from Saved words.

## Context / problem

1. **Grammar:** the card listed only the points the clicked word belongs to. Most words (nouns, adjectives) belong to
   none, so the card showed no grammar at all, and an analysis failure was silent.
2. **UI:** the extension used YouTube-like light and dark colors that matched neither YouTube nor the Android app.
3. **AI setup:** the only help was a "Get a key" link in the menu. There was no explanation of which service, how to
   get a key, or what the errors mean.
4. **Behind the video:** Google translated a track from its first line, 50 lines at a time. A video opened at minute
   20 from Saved words (`&t=1200s`) waited for every earlier batch before its playing lines got a translation.

## User-visible behavior

- **The Android app's look** (`SubtitleColors.kt` defaults, always dark teal):
  - The overlay has an ice white original on a deep teal box, with the translation in sky blue.
  - The panel has a header with CC, "English → Persian", a status line, ⚙ settings and ? guide. Each sentence is a
    card with a ▶ button, and the playing one has a cyan border.
  - The word card shows the word, 🔊 and ✕, the part of speech with IPA, dictionary form and level, a "Meaning (fa)"
    box, Grammar, Dictionary, a wide cyan "Save to vocabulary" button, then Copy and Send to Anki.
  - The menu, Saved words and the guide share `ui/theme.css`.
- **Grammar:** the card shows the word's points under "Grammar". When there are none, it shows "Grammar in this
  sentence" with each point of the sentence once. While the sentence is analyzed it says "Reading the sentence…", and
  a failure says "Grammar is not available: <reason>".
- **Guide** (`ui/guide.html`): opens on install, from the panel's ?, from the menu's "Guide & AI setup" and "How?",
  and from the status line's "Set up AI" or "Help" when AI has no key or fails.
  - It covers how to watch, replay, look up and save.
  - It explains why AI translates better, and lists the four services with Android's notes ("Free key with a Google
    account"…).
  - Each service has setup steps from Android's `ai_setup_step_*` text, and the key can be pasted on the page (saving
    turns AI translation on and warns when the key's prefix does not fit the service).
  - A table explains the errors: 401/400, 503, 429, could not be reached.
- **Playing part first:** Google's batches start with the one being played (or the address's `t=` time, before the
  player has jumped there), then the rest of the video, then its start. AI translation already worked that way.

## Acceptance criteria

- [x] Headless Chrome: opened with `&t=300s` and a 140-line track, line 100 (300 s) is translated before line 0.
- [x] Headless Chrome: a noun's card shows "Grammar in this sentence" with each point once; a selection's card shows
      its own points.
- [x] Headless Chrome: in the guide, picking Gemini and saving a key stores it in `storage.local.ai` and sets the
      translator to AI.
- [x] The scope test caught `languageName` declared in both `translation/ai.js` and `ui/content.js`; it is now shared
      through `DualSub`.

## Version

0.6.0.

## Validation result

All of the above passed, and unit tests pass. Not checked: the real YouTube dark and light themes beside the panel,
and the owner's own grammar case. If the owner's card still shows no grammar, its new message says why.
