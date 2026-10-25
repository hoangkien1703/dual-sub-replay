# Recent languages in "Translate to"

## Status

Implemented (owner request 2026-10-25). Merge waits for the owner's phone test.

## Context / problem

The "Translate to" picker lists every language A to Z, so switching between the two or three
languages a learner actually uses means searching or scrolling each time.

## Goals

- The picker shows the 3 languages chosen most recently at the top, under "Recent", above
  "All languages".
- One tap on a recent language switches to it.
- No setup and no new setting.

## Non-goals

- No recent group for "Original language" (that list follows the video's caption tracks) or the
  first-run language setup.
- No change to how translation works.

## User-visible behavior

- Before: Translate to → A-Z list only.
- After: once a second language was picked, Translate to → Recent (current language first,
  marked Selected, then the other recent picks, at most 3) → All languages (the A-Z list).
- Typing in Search languages hides the Recent group and shows only the matches, as before.
- Reset all settings clears the recent list.

## Technical constraints / invariants

- Stored in preferences as `recent_target_languages` (comma-separated codes, newest first),
  listed in `RESETTABLE_SETTING_KEYS`.
- All target changes go through `AppViewModel.setTargetLanguage`, including the AI assistant's, so
  they all update the list. The language a pick replaces is kept too, so the first switch after
  updating already shows two languages.
- New UI text (`player_recent_languages`, `player_all_languages`) exists in all 8 interface
  languages.

## Proposed approach / plan

1. `ui/RecentLanguages.kt`: parse/encode, `rememberRecentLanguage`, `recentLanguagesToShow`.
2. `DualSubUiState.recentTargetLanguages`, loaded from and saved to preferences.
3. `LanguagePickerDialog` takes `recentCodes` and shows the Recent group when the search is empty.
4. Both the gear popup and the settings page pass the list to the Translate to picker.

## Acceptance criteria

- [x] After picking English while Vietnamese was set, Recent shows English (Selected), then
  Vietnamese: newest first (`RecentLanguagesTest`).
- [x] Only the 3 newest picks are kept; picking a recent language again moves it to the top.
- [x] Recent is hidden while searching and with fewer than 2 languages (`SubtitleUiTest`).
- [x] Tapping a recent language changes the target (`SubtitleUiTest.recentLanguageSwitchesInOneTap`).
- [x] Reset all settings clears `recent_target_languages` (`SubtitleHighlightTest`, `RecentLanguagesTest`).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including `RecentLanguagesTest` | PR CI |
| UI tests | `SubtitleUiTest` recent-language tests pass | PR CI managed device |
| Manual | Switch Translate to a few times on a phone; Recent shows the last 3 | Owner, preview build |

## Results

Cloud container has no Android SDK; results come from PR CI and the owner's preview test.
