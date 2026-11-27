# Listening practice

## Status

Implemented. Owner request 2026-11-27 ("hide the dual subtitles but keep the panel, tap to show one line"); the owner picked option A, "tap the dots", from three options (A: tap the dots, B: a show icon on each row, C: a separate Play mode). Merge and release need the owner's OK.

## Context / problem

The gear popup's *Show while playing* had only a Translation chip: the Original chip was removed on 2026-11-11 because hiding both lines made the subtitle panel disappear while the video played, and the video looked blank. The owner wants a listening mode: the video plays as usual, the panel stays, every line is hidden, and the learner shows one line only when they want to read it.

Dictation already hides panel rows as dots (one per word) and remembers revealed lines, so this reuses that.

## Goals

- The *Original* chip is back in the gear popup's *Show while playing*, beside *Translation*, on its own row under the label.
- **Listening practice** starts when neither line shows while playing (`listeningPractice()` in `ui/CaptionVisibility.kt`): both chips off, or on the settings page any mix of *Only when paused* and *Never* except both *Never*.
- While it is on, the subtitle panel stays (it no longer disappears) and every row shows dots instead of its text. A tap on a row's dots shows that line, original and translation (each only if not set to *Never*), with word colors and tap-to-look-up as usual. A shown line stays shown while the app runs (per video and line, in memory, like Dictation). ▶ and the rest of the row still replay the line.
- The playing row keeps its outline, so the learner can follow along.
- Settings search finds the setting with "listening".

## Non-goals

- No hint text under the chips or on the settings page (owner, 2026-11-27: "unnecessary … the user can try it").
- No new setting, no new Play mode, no icon per row.
- Pausing does not reveal the lines in the panel: the next lines would give themselves away.
- No blur: it needs Android 12, and blurred text still shows word shapes.

## User-visible behavior

| Where | Listening practice |
| --- | --- |
| Subtitle panel | Stays while playing; rows as dots; tap the dots to show a line. |
| Full screen box, overlay player mode | Unchanged: *Only when paused*, so the playing line shows when the video pauses. |
| TV layout | Rows as dots; OK on a hidden row shows it, OK on a shown row opens its words as before. |
| Live subtitles | Unchanged (no fixed lines to hide): the panel goes away while playing, as before. |
| Listen first, Dictation | Unchanged: their own rules win. Other Play modes (Pause after each line, Repeat each line …) work with it. |
| Both lines *Never* | Unchanged: the panel goes away. |

Also in this PR (owner, 2026-11-27): the panel header's second line no longer says "Subtitles ready near playback" or "Tap a paragraph to replay it". It shows only while something is happening (finding the track, preparing translations, a translation problem). Between lines the box on the video now says "Waiting for the next caption…" (its existing text for that case) instead of "Subtitles ready near playback".

## Plan

1. `listeningPractice()` / `DualSubUiState.listeningInPanel()`; `tracksVisible()` keeps the panel for it.
2. `ListeningPracticeState` (`ui/ListeningPractice.kt`), held by `StudyPlaybackRunner` beside `DictationState`, given to the panel through `LocalListeningPractice`.
3. `SubtitleTimeline` hides rows for it; `CompactSubtitleCard(onRevealHidden)` makes the dots a button ("Show this line" for TalkBack).
4. `ShowWhilePlayingRow` gets the Original chip; `QuickLanguageSettingsDialog(originalVisibility)`.
5. Status line: `statusMessage` is null once lines are ready; the header hides an empty second line.

## Validation

- Unit: `CaptionVisibilityTest` (when it starts, which modes keep their rules, the overlay keeps *Only when paused*, revealed lines per video).
- Device: `ListeningPracticeUiTest` (dots, a tap shows one line without replaying, pausing reveals nothing, a line shown while playing turns it off; screenshots `listening-practice-hidden`, `listening-practice-revealed`); `SubtitleUiTest.quickSettingsShowWhilePlayingTogglesCaptionVisibility` (both chips; screenshot `subtitle-quick-show-while-playing-both-off`).
- Not verified here: a real phone with a real video (owner's preview test).
