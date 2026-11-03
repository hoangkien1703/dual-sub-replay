# Video titles and notes on saved words, and a third subtitle line

## Status

Implemented. Ported from mrAlishah's fork [Triple Sub Replay](https://github.com/mrAlishah/triple-sub-replay)
(branch `mydevelop`), after mrAlishah offered the fork's features in
[discussion #81](https://github.com/hoangkien1703/dual-sub-replay/discussions/81#discussioncomment-19352942).
The owner picked three of them on 2026-11-02: the video a word was saved from, notes on a
saved word, and the third subtitle line, off by default. The code was rewritten for this
repository (decks, 101 languages, the current translation pipeline) rather than merged.

Not taken now: the fork's "ChatGPT subscription" sign-in. OpenAI's official
[Sign in with ChatGPT](https://developers.openai.com/siwc/quickstart) is for approved
partners, and its documented open-source flow only describes a desktop `127.0.0.1` redirect,
with nothing for Android apps yet. The fork reuses the Codex CLI's login instead.

## Goals

- A saved word keeps the title of the video it came from. Practice shows it, searches it,
  and can show and practise one video's words.
- A saved word can carry the learner's own notes, edited in Practice and shown after
  "Show meaning" in a review.
- An optional third subtitle line: one more translation of each caption into a third
  language, under the translation. Off by default.
- Backups and Anki text keep titles and notes; a file without them never erases them.

## Non-goals

- Typing or editing a video's title (the fork's manual titles).
- Meanings in several languages per word, word taps on the third line, or sending titles and
  notes to AnkiDroid or AnkiConnect cards.
- AI translation of the third line (it uses Google, or the device).
- Per-line right-to-left checkboxes: each line already takes its direction from its own script.

## User-visible behavior

- Practice → word: "From: <video title>" under the example, and a **Notes** box with
  **Save notes**. Going back saves typed notes too.
- Practice → list: **Video: All videos ▾** appears once a word has a title; picking a video
  shows its words, and **Practice due words** practises only those. Search also matches
  titles and notes.
- Settings → Layout & format: **Third subtitle line**, a switch that is off by default (owner
  request 2026-11-02, after first trying it in the Languages card). Turning it on shows its
  language button under the switch, and opens the language list when none is picked yet.
  While it is on, the subtitle panel's language popup also shows a **Third line** row; picking
  "Off" there turns the switch off. The language is remembered while the line is off.
  The line shows its translation under each subtitle, in the overlay too, in the
  translation's color and a bit smaller. It follows the translation line's visibility
  (paused only, hidden, Listen first). A language that equals the original or the translation
  shows nothing, and live subtitles (no full transcript) have no third line.
- Words saved before this change have no title until saved again.
- Saved words keep the third line too (follow-up after mrAlishah tested v1.8.16, 2026-11-03): a
  word saved while the line is on keeps that line's text and language. Practice shows it under
  the translation (a Persian, Arabic or Hebrew line in Practice now reads from the right, like the
  subtitles); backups and Export keep it; AnkiDroid and AnkiConnect cards get it in the
  **Translation** field, on a new line under the translation.

## Technical notes

- `SavedWord.sourceTitle` and `SavedWord.notes`: optional JSON fields (`sourceTitle`, `notes`),
  so the database needs no migration. The JSON reader also accepts the fork's `personalNotes`.
  Neither is part of the word id. Saving a word again keeps its notes, and its title when the
  new copy has none (`mergeResaved`).
- The title comes from the page's playback state (`WebVideoBridge.playback`), which reports
  the title and the video address together; a title of another video is never used.
- TSV export adds `sourceTitle` and `notes` columns after `deck`; import maps them by header.
- Third line (`ThirdSubtitleLine.kt`): the fork's design. `ThirdLineTranslation` runs a second
  `translatePlaybackWindow` loop on the same display store as the second line, so row ids
  match, and publishes `ThirdLines(videoId, language, lines)` beside `DualSubUiState.segments`.
  The subtitle store, `SubtitleSegment` and the two-line pipeline are unchanged. It starts with
  each translation run and restarts when the third language or the engine changes. Google
  first with one batched request for upcoming lines, falling back to ML Kit when it has a
  model; with "On this device only" it uses ML Kit only. Its failures are silent and never
  touch the other two lines.
- `SavedWord.thirdSentence` and `SavedWord.thirdLanguage`: optional JSON fields, filled from
  `DualSubUiState.withThirdLineOnScreen` when a word is tapped and again when it is saved (the line
  may arrive in between). Saving again with the line off keeps the old one (`withThirdLineOf`).
  TSV export adds `thirdSentence` and `thirdLanguage` columns at the end. Anki gets no new field:
  AnkiDroid's provider cannot add a field to the "DualSub Replay" note type people already have,
  and a second note type with the same name would split their cards.
- Third line preferences: `third_subtitle_line_enabled` (default false) and
  `third_subtitle_language` (blank = none picked); both reset with all settings.

## Tests

- `SavedWordNotesAndSourceTest`: storage, backup and TSV round trips, older words, fork
  backups, re-saving, import validation.
- `ThirdLineOnSavedWordsTest`: saving with the line on, off or for another video, storage,
  backup, TSV and Anki fields.
- `ThirdLineAndVideoFilterTest`: stored language, no repeated language, rows only for the open
  video and chosen language, video list, search.
