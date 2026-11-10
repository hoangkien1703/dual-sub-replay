# Hide while playing in the gear popup

## Status

Implemented. The owner picked option B ("Hide while playing" toggles) on 2026-11-10 after comparing it with two dropdown rows. Merge and release need the owner's OK.

## Context / problem

The owner switches Settings → Captions & translation → *Translated captions* between *Always* and *Only when paused* often. Today that takes the gear, *Dual-subtitle settings*, the right section, a dropdown and a pick. The gear's popup only had the languages, and the owner worried that adding more would clutter it.

## Goals

- The gear popup gets one line: **Hide while playing** with a **Translation** toggle chip at its end.
- Update 2026-11-11 (owner): the **Original** chip was removed. With both ticked every line disappears while playing, which can make people think the app broke. Original stays on the settings page.
- A tick means the line is hidden while the video plays (*Only when paused*); no tick means *Always*. One tap switches.
- The popup title becomes **Subtitles**, as it now holds more than languages.

## Non-goals

- *Never* stays in the full settings page only. In the popup a line set to *Never* shows ticked (it is hidden while playing), and a tap sets it to *Always*.
- No change to the full settings page, the play modes or the AI assistant (which can already set both).

## User-visible behavior

Gear → popup: Original language, Translate to (and Third line when on), then *Hide while playing* with the Translation chip on the same line, then *Dual-subtitle settings*. Changes apply at once, like the language rows, and count for Often changed (same preferences as the settings page).

Listen first and Dictation still hide lines while playing whatever the chip says; the chip shows the stored setting.

## Technical constraints / invariants

- Strings live in `strings_settings.xml` in every language (`AppLanguageTest`, lint `MissingTranslation`). The chip reuses `settings_colored_lines_translation`; `settings_subtitle_languages` is replaced by `settings_quick_subtitles` and `settings_hide_while_playing`.
- Writes go through `AppViewModel.setCaptionVisibility`, the same path as the AI assistant, which writes `original_caption_visibility` / `translated_caption_visibility`.

## Proposed approach / plan

1. `CaptionVisibility.kt`: `hiddenWhilePlaying`, `toggledHideWhilePlaying()` and the `HideWhilePlayingRow` composable (the label and one FilterChip on a Row, with a check when ticked).
2. `QuickLanguageSettingsDialog` takes the translated visibility and a change callback; `DualSubApp` passes the state and `viewModel::setCaptionVisibility`.

## Acceptance criteria

- [x] With Original *Always* and Translated *Only when paused*, the popup shows Translation ticked and no Original chip.
- [x] Tapping Translation sets it to *Always*, and tapping again sets it back to *Only when paused*; Original is never changed from the popup.
- [x] *Never* reads as ticked, and a tap gives *Always*.
- [x] The language rows, Recent languages and *Dual-subtitle settings* still work.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `CaptionVisibilityTest.hideWhilePlayingToggleSwitchesAlwaysAndOnlyWhenPaused` | Cloud |
| Android lint/build | `formatCheck complexityCheck lintDebug compileDebugAndroidTestKotlin` | Cloud |
| Managed-device/emulator | `SubtitleUiTest.quickSettingsHideWhilePlayingTogglesCaptionVisibility` and the existing quick popup tests; screenshot `subtitle-quick-hide-while-playing` | PR CI |
| Physical-device/manual | Owner flips the chip on the preview APK while a video plays | Owner's phone |
| Live YouTube | Not needed beyond the manual check: no page scripts change | n/a |

## Risks / edge cases

- A long translation of "Hide while playing" wraps beside the chip, so the popup can grow one line in some languages.
- In landscape the popup is one row taller; it still fits with the third line on.

## Release intent

`release:patch`: a small visible change to the APK.

## Implementation result

As planned.

## Validation result

See the PR for CI. Physical-device check: not yet done.
