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
  it and that line plays again and again; tap it again, tap another line, or seek elsewhere to stop.
- A **Search settings** field at the very top of the settings page. Typing shows matching settings
  (name and section, in the app's language, accents ignored); tapping one opens its section,
  scrolls to it and briefly highlights it.
- **Often changed**: below the search field, up to 3 settings the user changed at least twice in
  the last 7 days, most changed first; hidden when none qualify. Tapping one jumps to it like a
  search result.

## Non-goals

- No loop icon in the overlay, full screen or TV layout; no loop for live subtitles (play modes
  already need the full transcript).
- The Repeat each line play mode and its 2/3/5/Forever counts are unchanged.
- No search inside the AI assistant's own sub-sections: the search finds the AI assistant section.

## User-visible behavior

- Loop: the icon is a repeat-one icon; while looping it sits on a filled accent circle. The loop
  only follows the line it was turned on for, overrides the play mode and skip silence while on,
  and ends when the video changes or the option is turned off.
- Often changed counts the user's own changes on the settings page only (not the AI assistant,
  not Reset all settings); a slider drag or quick on/off within 30 seconds counts once. Reset all
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
- With it on, only the playing row shows the icon; tapping it repeats that line until tapped
  again, another line is tapped, or the user seeks elsewhere.
- Typing "loop" in Search settings shows "Loop button on the playing line · Study tools"; tapping
  it opens Study tools at that switch, highlighted.
- Changing Text size on two visits within 7 days shows it under Often changed; one change does not.

## Validation plan

- Unit tests: `StudyPlaybackDriverTest` loop cases, `SettingsSearchTest` (matching, history,
  ranking, merge window, 7-day window).
- Compose tests: search result jump, Often changed card, loop icon on the active row only.
- Owner phone test on the PR preview build.

## Risks / edge cases

- A seek landing early, in the line before, must not end the loop (own seeks are recognised).
- Settings that show only under another choice (for example Google wait) have no anchor while
  hidden; the jump then stops at the section's top.
