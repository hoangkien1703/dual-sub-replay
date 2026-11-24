# DualSub for Chrome (preview)

Maintainer: @parastouh

Learn a language with YouTube on a computer: a Chrome extension that brings DualSub Replay's learning to the desktop.

- **Two subtitle lines over the video:** the original line and its translation.
- **Clickable transcript** beside the video. Click a line, here or on the video, to hear it again.
- **Words colored by part of speech**, as in the Android app (English tagged in context with the same model; Japanese by rules).
- **English grammar help:** the dictionary form, CEFR level and IPA of each word, and the grammar points around it
  (165 patterns, A1–B2), the Android app's own.
- **Word card:** click a word for its translation and dictionary meaning. **Save** it, or **Send to Anki**.
- **Saved words and Practice page:** review saved words with the Android app's spaced-repetition intervals.

No account, no server, no key.

## Install (developer mode)

1. Download [DualSub-Chrome-extension.zip](https://github.com/hoangkien1703/dual-sub-replay/releases/download/extension-preview/DualSub-Chrome-extension.zip) (rebuilt from `main` on every change to this folder) and unzip it,
   or use this folder from a clone of the repository.
2. Open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked** and pick the `browser-extension` folder.
3. Open a YouTube video that has captions. Pick the translation language from the extension's toolbar icon (default: your browser's language, or English).
   The same menu opens **Saved words & practice**.

The guide (the panel's **?** or the menu's **Guide & AI setup**) shows how to set up AI translation with a free
Gemini key or another service.

**Send to Anki** needs Anki open with the [AnkiConnect](https://ankiweb.net/shared/info/2055492159) add-on. Anki asks once to allow DualSub.

## For contributors

Read the [Browser extension section of AGENTS.md](../AGENTS.md#browser-extension) and the
[technical context](../docs/project/browser-extension.md).
