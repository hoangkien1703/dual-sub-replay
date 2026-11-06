# Loop button on the playing line, settings search and Often changed

## Status

Implemented (owner request 2026-11-06, release:patch). Waiting for the owner's phone test.

## Context / problem

lovoro (discussion #81) asked after v1.8.19 for a quick way to turn sentence repeat on and off from
the main screen: Repeat each line lives in Settings → Study tools → Play mode, three levels deep.
The owner wants a simple, minimalist main screen, so the loop control is an option that is off by
default. The settings page has grown to 12 sections, so the owner also asked for a search bar at
the very top and a short list of the settings the user changes most.

## Goals

- Settings → Study tools → **Loop button on the playing line** (off by default). While it is on, the
  playing (highlighted) row of the transcript panel shows a small loop icon at its right edge. Tap
  it and that line plays again and again. The loop stays on until the icon is tapped again: tapping
  another line, or seeking elsewhere, loops that line instead (owner feedback on the preview).
- A **Search settings** field at the very top of the settings page. Typing shows matching settings
  (name and section, in the app's language, accents ignored); tapping one opens its section,
  scrolls to it and briefly highlights it.
- **Often changed**: shown under the search field while it is tapped and empty (owner feedback on
  the preview): up to 3 settings the user changed at least twice in the last 7 days, most changed
  first, or a line saying settings changed twice in a week show there. Tapping one jumps to it
  like a search result.
- **Languages** becomes a closed section, the first under More settings, its summary showing the
  two languages; the "A language model downloads only when it is needed." hint is gone.

## Non-goals

- No loop icon in the overlay, full screen or TV layout; no loop for live subtitles (play modes
  already need the full transcript).
- The Repeat each line play mode and its 2/3/5/Forever counts are unchanged.
- No search inside the AI assistant's own sub-sections: the search finds the AI assistant section.

## User-visible behavior

- Loop: the icon is a repeat-one icon; while looping it sits on a filled accent circle. The loop
  overrides the play mode and skip silence while on, moves to the line the learner taps or seeks
  into (or the next line after a gap), and ends when the icon is tapped, the video changes or the
  option is turned off.
- Often changed counts the user's own changes on the settings page and in the gear's languages
  popup (not the AI assistant, not Reset all settings); a slider drag counts once (changes of one
  setting less than 3 seconds after the one before merge). Reset all
  settings also clears this history.

## Technical constraints / invariants

- One WebView; the loop only sends seeks through the existing `StudyPlaybackRunner` and
  `YouTubeWebController.replayFrom(byPlayMode = true)`.
- All new text in all 9 interface languages.
- The loop preference is a Study tools key, so Reset all settings resets it.

## Proposed approach / plan

- `StudySettings.loopButton` (`line_loop_button`), `AiSetting.LOOP_BUTTON` so the assistant can turn
  it on. `LineLoop` holds the looped line's id; `StudyPlaybackDriver` seeks back to the line's
  start at its end and reports when a seek elsewhere ends the loop.
- `ui/SettingsSearch.kt`: `SearchableSetting` (title, section, anchor tag, preference keys),
  `searchSettings`, change history (`recordSettingChange`, `oftenChangedSettings`) in the
  `settings_usage` preferences, and `Modifier.settingAnchor`. `SettingsSwitchRow` anchors itself by
  its test tag; other settings anchor by hand.

## Acceptance criteria

- With the option off (default) the transcript rows look exactly as before.
- With it on, only the playing row shows the icon; tapping it repeats that line until it is tapped
  again; tapping another line loops that line instead.
- Typing "loop" in Search settings shows "Loop button on the playing line · Study tools"; tapping
  it opens Study tools at that switch, highlighted.
- Changing Text size twice (a few seconds apart) within 7 days shows it under Often changed when
  the search field is tapped; one change does not.

## Validation plan

- Unit tests: `StudyPlaybackDriverTest` loop cases, `SettingsSearchTest` (matching, history,
  ranking, merge window, 7-day window).
- Compose tests: search result jump, Often changed card, loop icon on the active row only.
- Owner phone test on the PR preview build.

## Risks / edge cases

- A seek landing early, in the line before, must not end the loop (own seeks are recognised).
- The transcript lines are a window that slides with playback: after a long seek the loop waits
  until the window reaches the new place instead of ending (found on the emulator, build 674).
- An ad plays on the page with its own clock: the play modes and the loop ignore ticks while
  YouTube's player shows an ad (`adShowing` in the playback snapshot); otherwise the loop kept
  restarting the ad. After the ad, the loop follows the video to where it is.
- Settings that show only under another choice (for example Google wait) have no anchor while
  hidden; the jump then stops at the section's top.
