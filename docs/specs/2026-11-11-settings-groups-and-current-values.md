# Settings page: four groups, current values, one rule for look settings

## Status

Implemented. The owner picked "Changes 1 to 5 in one PR" on 2026-11-11 from a ranked list with a before/after mock-up. Merge and release need the owner's OK.

## Context / problem

The owner found the settings page confusing, mainly the "More settings" heading. Emulator screenshots of `main` (2c4a0a1) showed why:

- Languages (2026-11-06) and Reading & view (2026-11-04) were folded into the list at the owner's request, so "More settings" sat above all 14 sections (15 with Backup & sharing) and said nothing.
- Only Languages showed its current value. The other lines listed what was inside ("Caption flow and model preloading"), so every answer needed a section opened.
- The look settings were split without a rule: Translation position in *Reading & view*, Caption format and Third line in *Layout & format*, Default view apart from Panel position. The page-token caption setting sat in Study tools.
- The top bar said "Dual-subtitle settings" and had both an X and a Done button that did the same.
- Search settings found no AI assistant setting, and no test noticed.

## Goals

1. Four named groups replace "More settings": **Subtitles** (Languages, Captions & translation, Subtitle text, Screen layout), **Study** (Word learning, Study tools, Flashcards & Anki), **Video** (Overlay & fullscreen, Ads & sponsors, YouTube tabs), **App** (Colors & theme, AI assistant, App updates, Android TV, Backup & sharing). Daily use first, rare last.
2. The line under each closed section shows its current value.
3. One rule sorts the look settings: **Subtitle text** is what the words look like, **Screen layout** is where things sit. "Load captions with a page token" moves to Captions & translation.
4. The top bar says **Settings** and loses Done; the X closes. Update 2026-11-11 (owner, after the first preview): the X sits in the top right corner, like every dialog's X, with the eye just left of it.
5. Search settings finds the AI assistant's settings, and a unit test fails when a new switch is not searchable.

## Non-goals

- No page per category. One scrolling page keeps the scroll memory, the one-open-section accordion and the peek button ([page memory spec](2026-10-31-settings-page-memory-and-peek.md)).
- No setting is added, removed or given a new default, and no stored preference changes.
- Often changed, Search settings and Reset all settings stay where they are.
- CI timing (publishing the preview APK as soon as the build job passes) is a later `release:skip` PR.

## User-visible behavior

Before: Often changed, Search settings, then "More settings" above 14 sections with fixed descriptions.

After: Often changed, Search settings, then four small headings, each above a joined block of sections. Each section line shows values:

| Section | Line (defaults, English) |
| --- | --- |
| Languages | Auto → Vietnamese |
| Captions & translation | Google Translate |
| Subtitle text | Text size: 100% · Translation below |
| Screen layout | Default view: Transcript panel |
| Word learning | Word colors: On · Known words: Off |
| Study tools | Play mode: Normal |
| Flashcards & Anki | Send to flashcard apps: Off, or the app's name and "Add audio to cards: On" |
| Overlay & fullscreen | Use overlay in fullscreen: On |
| Ads & sponsors | Skip ads: On · Skip sponsors: On |
| YouTube tabs | Use different Google accounts: Off |
| Colors & theme | Deep teal |
| AI assistant | Off, "No key yet", or the service's name |
| App updates | Update automatically: Off |
| Android TV | TV layout (beta): Off |
| Backup & sharing | Move to a new phone, share your setup (it has nothing to switch, so the line says what it does) |

The exact names come from the settings' own titles in the app's language. The line follows a change at once, also after Reset all settings.

Moved settings: Caption format and Third subtitle line are in Subtitle text; Default view and Follow the transcript are in Screen layout beside the panel positions; the page-token setting is in Captions & translation. Search settings and the AI assistant's guide name the new places.

The gear popup's last row says **Settings** too, so both ways in use one name.

Searching "memory", "full access", "key", "model" or a service name such as "Claude" now finds the assistant's setting, opens AI assistant and the card inside it, and shows the glow on the setting. In a build without the assistant these results do not appear.

## Technical constraints / invariants

- Section keys (`reading`, `layout`, …) and the `settings_section_<key>` test tags do not change: page memory (`SettingsPageMemory`), search anchors and device tests depend on them. Only titles and order changed.
- Sections stay direct children of the scrolling column so `sectionTops` and the restore-scroll logic keep working; groups are drawn with corner shapes by position and thin separators.
- Strings exist in all nine languages (`AppLanguageTest`, lint `MissingTranslation`). Removed: `settings_more_settings`, the 13 `settings_section_*_summary` texts, `settings_done`, `settings_dual_subtitle_settings`.
- [Default settings rule](../../AGENTS.md#default-settings): untouched, no default changes.
- The AI guide (`assets/ai/assistant-guide.md`) names every section under its group (`AiAssistantPromptTest`).

## Proposed approach / plan

1. `SettingsGroup` and `MoreSettingsSection` (order, group, icon) in `ui/SubtitleSettingsDialog.kt`; `settingsGroupsOnPage` builds the page; `settingsSectionShape` rounds only a group's first and last card.
2. `ui/SettingsSummaries.kt`: pure `settingsSectionSummary(section, state)` returning parts, `settingsSummaryText` to join them, and `rememberSettingsSummaryState` to follow stored values.
3. Move the settings between `READING`, `LAYOUT`, `TRANSLATION` and `STUDY`, and their `SearchableSetting` entries with them.
4. Top bar title and the Done button.
5. `SearchableSetting` entries for the assistant (service, keys, model, history, YouTube search, web search, screen, memory, full access) with anchors in `ui/AiAssistantSettings.kt`; `SettingsAnchors.aiSectionToOpen` opens the inner card.

## Acceptance criteria

- [x] "More settings" is gone; the four group headings show in order, and every section belongs to exactly one group (`SettingsSectionsTest`).
- [x] Without the assistant, the App group has no AI assistant section and search has no AI results.
- [x] Each section's line shows the values in the table, and changes with the setting (`SettingsSummariesTest`).
- [x] The moved settings are found by search in their new sections (`SettingsSearchTest.theSettingsThatMovedAreFoundInTheirNewSections`).
- [x] Every `Switch` test tag on the settings page is searchable, with `ai_enabled_switch` (the section's own first row) as the one listed exception (`SettingsSearchTest.everySwitchOnTheSettingsPageCanBeFound`).
- [x] The top bar says "Settings", has no Done, and the X (top right corner, right of the eye) and Back close the page.
- [ ] Device tests and screenshots on the CI emulator (see the PR).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: `SettingsSectionsTest`, `SettingsSummariesTest`, `SettingsSearchTest`, `AiAssistantPromptTest`, `AppLanguageTest` | Cloud |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud |
| Managed-device/emulator | `LearningPlayerUiTest`, `StudyToolsUiTest`, `AiAssistantPanelUiTest`, `TranslationPositionAndThemeUiTest`, `SubtitleUiTest`; screenshots `settings-layout-open`, `settings-captions-page-token`, `settings-search-ai-setting`, `settings-search-ai-jump` and the existing settings ones, dark and light | PR CI |
| Physical-device/manual | Owner opens Settings on the preview APK and checks the groups, a few lines and one AI search | Owner's phone |
| Live YouTube | Not needed: no page script or caption code changes | n/a |

## Risks / edge cases

- A long line (two values in a long language) is cut with an ellipsis after two lines, as before.
- People who knew the old places of the four moved settings need Search settings or the new names once. The names follow one rule now, so the change should not need repeating.
- PR #215 (Backup & sharing) merged first; its section is the last one of the App group, just above Reset as before.

## Release intent

`release:patch`: the settings page looks different but no feature or stored setting changes.

## Implementation result

Implemented as planned. Differences from the mock-up: lines with a switch use the "Name: value" form ("Default view: Transcript panel", "Word colors: On") so the value cannot be mistaken for a section name, and the gear popup's row was renamed to "Settings" to match the page title.

## Validation result

See the PR for the final-head CI run and the emulator screenshots. Physical-device check: not run, waits for the owner.
