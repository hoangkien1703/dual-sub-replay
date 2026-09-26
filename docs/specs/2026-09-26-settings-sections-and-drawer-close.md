# Grouped settings, drawer close button, and subtitle panel polish

## Status

Validated. CI passed, and the owner tested the preview builds on a phone and approved them. The owner asked for this work and a PR to test on a phone. That request does not
authorize merging or publishing.

## Context / problem

The owner asked for a better UI, performance work where needed, the "More settings" part split into
sections that are easy to understand, and an "x" button to close the left sidebar. Their phone
screenshots show:

1. **Sidebar.** The navigation drawer has only "Practice" and "Settings" as plain text. The only
   ways to close it are the back gesture or tapping the thin strip beside it. It uses Material's
   default purple-grey surface, not the app's dark teal.
2. **Settings.** "More settings" opens about 25 controls in one long list inside a narrow
   `AlertDialog`: panel position, word learning, caption format, colors, box background, app theme,
   fullscreen, overlay, translation and split view. It is hard to find anything.
3. **Subtitle panel header.** It reads "English (auto-generated) (auto-…" because YouTube's track
   name already says "(auto-generated)" and `sourceDescription` adds it again. The target language
   is cut off. The paused status is also cut off.
4. **Performance.** Each spoken-word highlight update passes the new `activeWordIndex` to every
   visible transcript row, so every row recomposes, although only the active row uses it. Each
   translated line tokenizes its original text even when word learning is off.

## Goals

- A visible close button at the top of the sidebar, plus icons and a short app tagline.
- Settings in a full-screen page with clear groups: Languages, Reading, Default view, then five
  collapsible "More settings" sections, each with an icon and a one-line summary.
- App dialogs, the drawer and chips use the app's teal surfaces instead of the purple-grey defaults.
- The subtitle panel header shows "English (auto-generated) → Vietnamese" without the duplicate.
- Only the active transcript row recomposes when the spoken word moves.

## Non-goals

- No setting is added, removed or renamed in storage. Defaults and "Reset all settings" behave the
  same.
- No change to caption discovery, translation, the WebView, replay or highlight timing.

## User-visible behavior

- **Sidebar:** the header has the app name, "Learn languages with YouTube", and an "x" button that
  closes it. "Practice" and "Settings" have icons. The GitHub link and licenses stay at the bottom.
- **Settings:** a full-screen page with an "x" and "Done" in the top bar. Always visible:
  - Languages: original language, translate to.
  - Reading: text size, caption visibility, highlight spoken words.
  - Default view: transcript panel or scroll-friendly overlay.

  Under "More settings", tapping a section opens it and closes the one that was open:
  - Layout & format: portrait panel position, caption format, landscape split view.
  - Colors & theme: custom subtitle colors, overlay box background, app accent.
  - Word learning: pronounce tapped words, word colors and their options.
  - Overlay & fullscreen: auto overlay in fullscreen and landscape, dragging, locking, avoiding
    controls, remembered positions, reset positions.
  - Captions & translation: natural subtitle flow, preload translation models.

  "Reset all settings to defaults" stays at the bottom with its confirmation.
- **Subtitle panel:** no duplicated "(auto-generated)". The paused status reads
  "Paused · translation resumes on play".

## Technical constraints / invariants

- [Architecture invariants](../project/tech-stack.md#architecture-invariants) are untouched: one
  WebView, navigation classification, caption provider boundary, on-device translation, onboarding
  guide migration.
- Every existing preference key, default and test tag is kept, so instrumented tests keep finding
  the same controls.
- Unit tests stay plain JUnit4.

## Proposed approach / plan

1. `AppNavigation.kt`: header row with title, tagline and a close `IconButton`
   (`close_navigation_menu`); icons on the drawer items.
2. `theme/Theme.kt`: set the `surfaceContainer*`, `outline*`, `primaryContainer` and
   `secondaryContainer` colors to teal tones.
3. New `SettingsSections.kt`: `SettingsGroupCard`, `ExpandableSettingsSection`,
   `MoreSettingsSection` with `toggleMoreSettingsSection`, and the overlay section that owns its
   SharedPreferences state (moved out of the dialog unchanged).
4. `SubtitleSettingsDialog`: full-screen `Dialog` with a top bar, the three groups, then the five
   sections. The signature does not change.
5. `captionTrackLabel(name, generated)` avoids the duplicate suffix. The paused status is shortened.
6. `SubtitleTimeline` passes `activeWordIndex` only to the active row and computes colors once.
   `TranslatedCardText` tokenizes only when word colors or tap-to-learn need it.
7. Update `LearningPlayerUiTest` to open sections, and add a drawer close test.

## Acceptance criteria

- [x] `captionTrackLabel` does not repeat "(auto-generated)" and still adds it when missing
  (`SettingsSectionsTest`).
- [x] Opening a section closes the other one, and tapping the open one closes it
  (`SettingsSectionsTest.toggleOpensOneSectionAtATime`).
- [x] The sidebar "x" closes the drawer and keeps the WebView alive
  (`NavigationRecoveryUiTest.drawerCloseButtonDismissesTheMenu`).
- [x] Every control previously behind "More settings" is reachable in its section, and reset still
  works (`LearningPlayerUiTest.unifiedSubtitleSettingsOffersViewAndOverlayBehavior`).
- [x] Language pickers and "Done" still work (`SubtitleUiTest.settingsKeepsLanguageAndTextOptionsWithoutFocus`).
- [x] The player gear popup shows only languages and opens the full page
  (`SubtitleUiTest.gearPopupShowsOnlyLanguagesAndOpensFullSettings`).
- [x] ktlint format ratchet and detekt pass.
- [x] Final-head CI `verify-build` and `managed-device-tests` pass.
- [x] Owner phone check: sidebar "x", each settings section, subtitle header text, and smooth
  scrolling during playback.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including `SettingsSectionsTest` | CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | CI; ktlint and detekt also locally |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest`, including the updated settings and drawer tests | CI |
| Physical-device / live YouTube | The phone check in the acceptance criteria, using the PR preview APK | Owner's phone |

## Risks / edge cases

- A full-screen settings page hides the video while it is open. The old dialog already covered
  most of it.
- The theme color change affects every Material surface: the drawer, dialogs, chips and menus.
  They move from purple-grey to teal. No layout changes.
- A localized YouTube track name that says "auto-generated" in another language still gets the
  English suffix, as before.

## Release intent

`Release-Version: 1.1.0`. The spec first recorded the `release:patch` default. After testing the
preview builds, the owner asked in the project chat to merge and publish official release v1.1.0.
The PR body carries the standalone directive, and no release label is applied.

## Follow-up after the owner's phone check

The owner asked that the gear on the player open only the languages, as a small popup with the
video still visible behind it, with a "Dual-subtitle settings" option that leads to the full page.

- `QuickLanguageSettingsDialog` is an `AlertDialog` with the original and target language pickers
  and a "Dual-subtitle settings" row (`open_all_settings`) that opens the full-screen page.
- The gears on the portrait panel, the landscape side panel and the compact overlay open the popup.
  The sidebar "Settings" item and the collapsed CC button (when both caption lines are hidden) still
  open the full page.
- The popup and the full page share `LanguagePickerState`, `sourceLanguageChoices`,
  `sourceLanguageLabel` and `LanguagePickerButtons`, so language picking behaves the same in both.
- Test: `SubtitleUiTest.gearPopupShowsOnlyLanguagesAndOpensFullSettings`.

The owner also saw two Google "Is it you?" prompts during sign-in, and the second one said "This
prompt has expired". The WebView code loads each sign-in page once: `handleMainFrameUrl` only
records state and returns `false` for Google hosts, and the only `loadUrl` in the sign-in path runs
after the YouTube session cookies appear, which is after the prompt was answered. Google sends that
prompt to every phone or tablet signed in to the account, and marks the others "expired" once one
is answered. No app change was made for it. The owner was asked whether another device is signed in.

## Implementation result

Implemented as planned, plus the follow-up above.

## Validation result

- Local (Linux, no Android SDK available in this environment): ktlint format ratchet and detekt
  passed. Kotlin compilation, unit tests, lint and instrumented tests ran in PR CI.
- PR CI: `verify-build` and `managed-device-tests` passed on each pushed head, including the
  new unit and instrumented tests.
- Physical phone / live YouTube: the owner installed the PR preview builds, checked the new
  settings, sidebar and subtitle panel, asked for the gear popup follow-up, and then approved.
