# Translation position, whole-app colors, and AI assistant control of every safe setting

## Status

Approved for implementation. Kien asked for issue #115 as a setting rather than a button on the
screen, picked "All safe ones" for the AI assistant's reach, then asked for the whole app's
colors to be changeable too and picked "Dark and light" (project thread, 2026-10-16). That
covers this scope; merge and release stay with the owner.

## Context / problem

[Issue #115](https://github.com/hoangkien1703/dual-sub-replay/issues/115) asks for the
translation to show above the original subtitle line, with a button to switch. Today the
original line is always on top, in the overlay over the video and in the transcript panel
(`SubtitleCard.kt`, `LearningPlayerRoot.kt`).

The AI assistant's `change_setting` tool ([actions spec](2026-10-08-ai-assistant-actions.md))
reaches 16 settings. About 18 other settings on the settings page can't be read or changed by
it, so the assistant has to tell the user the steps instead.

Settings → Colors & theme only changes the accent; the rest of the app is always dark teal, and
several screens (setup, guide, transcript cards) used fixed hex colors instead of the theme.

## Goals

- A **Translation position** setting (Below original, the default, or Above original) under
  Settings → Reading. It sets the order of the two lines everywhere both lines are drawn
  together: the overlay on the video, the transcript panel and split view, and the live
  captions card.
- The AI assistant reads every setting it may change, and can change every setting on the
  settings page except the ones listed under Non-goals.
- An **App colors** setting under Settings → Colors & theme: six dark themes (Deep teal, the
  default, Black, Navy, Slate, Plum, Forest) and a Light theme. Every screen follows it; the
  accent color still applies on top.
- Small, display-only changes run at once with Undo, like today. Changes that reload the video's
  subtitles, download language models, or restart the screen in another language show an Apply
  button and wait for the user's tap.

## Non-goals

- No switch button on the player screen (owner decision for #115).
- The assistant still can't touch its own AI settings, API keys, saved words or memories outside
  `save_memory`/`forget_memory`, language downloads, or "Reset all settings to defaults"
  (owner rule 2026-10-08; reset can't be undone). Since 2026-10-18 the off-by-default Full access
  option lifts this for everything except memory and language downloads
  ([redesign spec](2026-10-18-ai-settings-redesign-keys-and-full-access.md)).
- Saved overlay and CC-button positions (set by dragging) and "Reset subtitle positions" stay
  manual: they are drag results, not settings.

## User-visible behavior

Settings → Reading gets "Translation position" with two chips, "Below original" and
"Above original". Above puts the translation line first in the overlay, the transcript panel
and the live captions card. Hiding either line still works as before. "Reset all settings"
returns it to Below.

New settings the assistant can change at once, with Undo:

| Key | Values | Settings page |
| --- | --- | --- |
| `translation_position` | below, above | Reading |
| `portrait_panel_position` | -20 to 20 (% higher when negative) | Layout & format |
| `overlay_background` | deep_teal, black, navy, slate, plum, forest | Colors & theme |
| `app_colors` | deep_teal, black, navy, slate, plum, forest, light | Colors & theme |
| `accent_color` | cyan, blue, mint, amber, rose, lavender | Colors & theme |
| `colored_subtitle_lines` | original, translation, both | Word learning |
| `highlight_active_sentence_only` | on, off | Word learning |
| `pronunciation_volume` | 25 to 200 % in 25 % steps | Word learning |
| `overlay_in_fullscreen` | on, off | Overlay & fullscreen |
| `overlay_when_rotated` | on, off | Overlay & fullscreen |
| `movable_subtitle_controls` | on, off | Overlay & fullscreen |
| `avoid_video_controls` | on, off | Overlay & fullscreen |
| `remember_dragged_position` | on, off | Overlay & fullscreen |
| `daily_goal` | off, 5, 10, 15, 20, 30, 45, 60 (minutes) | Progress |

New settings that wait for the user's tap on Apply:

| Key | Values | Why it asks |
| --- | --- | --- |
| `original_caption_language` | auto or a language the open video has | reloads the video's subtitles |
| `natural_subtitle_flow` | on, off | reloads the video's subtitles |
| `preload_translation_models` | on, off | downloads language models |
| `app_language` | device or one of the app's interface languages | rebuilds the screen in that language |

Settings → Colors & theme gets "App colors" with seven swatches. Dark themes recolor the teal
palette's hue and saturation (Black uses pure black backgrounds); Light uses white cards with a
darkened accent, dark status-bar icons, and darker shades of the subtitle and word colors so they
stay readable. The subtitle overlay on the video stays dark on every theme. "Reset all settings"
returns to Deep teal.

The assistant's "Current settings" list now shows every setting above with its key, so it
can answer "what is my X set to" for all of them.

Turning `remember_dragged_position` off forgets the dragged positions, exactly as the switch on
the settings page does; Undo turns the switch back on but the old positions stay forgotten.

## Technical constraints / invariants

- The assistant acts only through the fixed tool list in `assistant/AiActions.kt`; this adds
  entries to the existing `change_setting` enum and does not add a tool.
- Every change goes through the same setter or preference the settings page uses, so the
  screens that listen to `dual_sub_preferences` update at once.
- Setting changes after subtitles, files or error text were read still wait for a tap
  (`asksAfterOtherText`).
- User-visible text lives in `strings_settings.xml` in every `values-*` folder.

## Proposed approach / plan

1. `TranslationPosition` enum (`below`/`above`) stored as `translation_position`, loaded into
   `DualSubUiState`, set by `AppViewModel.setTranslationPosition`, cleared by reset.
2. `subtitleLineOrder()` decides the order; `CompactSubtitleCard` and `LearningSubtitleOverlay`
   draw their two lines through it.
3. `AppColorThemeOption` stored as `app_color_theme`; `dualSubColorScheme` builds a dark scheme
   by retinting the teal roles, or a light scheme. Fixed colors in screens move to theme roles.
4. A Reading-section chip row, like Caption format's, with strings in all languages.
5. `AiSetting` gains the keys above and an `asks` flag; `alwaysAsks` includes those settings.
   Number values go through one parser per unit (percent, signed percent, speed).
6. `AiStoredSettings` reads the preference-only settings for `aiSettingCurrentValue` and the
   snapshot; `AppAiActions.apply` writes them through the same preference keys.
7. `aiSettingsSummary` lists every `AiSetting` by key with its value.
8. Update the assistant guide asset and the actions spec's setting list.

## Acceptance criteria

- [ ] With Translation position set to Above, the overlay and the transcript panel show the
  translation line before the original line (Compose test reads the order).
- [ ] Below (the default) keeps today's order; existing UI tests stay green.
- [ ] Every `AiSetting` current value is one its parser accepts (`AiAssistantActionsTest`).
- [ ] The four "asks" settings report `alwaysAsks`; the others don't.
- [ ] The tool's description lists every key and its values; parsing accepts the documented
  values and rejects out-of-range numbers and unknown languages.
- [ ] The system prompt's settings list holds every `AiSetting` key.
- [ ] Choosing Light or another dark theme on the settings page recolors the app at once
  (Compose test checks the background), and the light transcript stays readable (screenshot).
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`, including new parser, order and prompt tests | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` via PR CI (`managed-device-tests`) | GitHub Actions |
| Physical-device/manual | Owner: switch the setting, ask the assistant to change several settings, tap Undo | Owner's phone, preview APK |
| Live YouTube | Not required: no caption fetching or page script changes | n/a |

## Risks / edge cases

- `app_language` on Android 13+ is applied by the system, which recreates the activity; the
  chat survives because the assistant's controller lives in a view model.
- `original_caption_language` is refused when the open video has no captions in that language,
  with the languages it does have.

## Release intent

`release:minor`, recommended because this adds a user-visible setting and widens the assistant;
patch is the default. The owner decides; until then the PR keeps the patch default and asks.

## Implementation result

- `ui/TranslationPosition.kt`: the setting, `subtitleLineOrder()` and the Reading chip row.
  `CompactSubtitleCard` and `LearningSubtitleOverlay` draw their lines through it; the live
  captions card reuses `CompactSubtitleCard`.
- `ui/AppColorTheme.kt` and `ui/theme/Theme.kt`: the seven themes. `retint()` keeps each teal
  role's lightness and swaps hue and saturation. `DualSubTheme` follows both preferences and
  sets light or dark system-bar icons.
- Light-theme readability: `SubtitleColorOption.lightArgb` gives each subtitle color a dark
  shade for app cards; `partOfSpeechColor()` darkens word colors on dark text; the amber/red
  status tints have darker light-theme shades.
- Fixed colors moved to theme roles in `DualSubApp`, `SubtitleCard`, `LanguageSetupScreen`,
  `GuideScreen`, `DailyGoalSetupScreen` and `JumpBackPill`.
- `assistant/AiActions.kt`: 34 settings in settings-page order (18 new, including
  `app_colors`), aliases for spoken values, `asks` on the four settings above.
  `ui/AiAssistantActions.kt` reads them through `AiStoredSettings` and writes them through the
  same setters or preference keys as the settings page. `original_caption_language` is refused
  with the languages the open video has.
- `AiActionsTest`, `AiAssistantActionsTest`, `AiAssistantPromptTest`, `TranslationPositionTest`,
  `AppColorThemeTest` (unit) and `TranslationPositionAndThemeUiTest` (Compose, with screenshots).

Deviation: the settings dialog keeps "Reset subtitle positions" manual, as planned; turning
`remember_dragged_position` off through the assistant clears the same keys the switch clears.

## Validation result

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed in the cloud container |
| `pixel2Api36DebugAndroidTest` | Runs in PR CI (`managed-device-tests`); no emulator in the container |
| Physical device, live YouTube | Unverified; owner tests the preview APK |
