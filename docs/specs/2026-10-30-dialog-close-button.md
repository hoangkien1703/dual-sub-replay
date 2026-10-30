# Close (X) button on dialogs

## Status

Implemented (owner request 2026-10-30 in the "App scaling" chat: "add an X to every dialog from now
on, unless it is a very critical one like the security update"; the owner chose a separate PR after
[scale readiness](2026-10-30-scale-readiness.md)). Waiting for CI and the owner's phone test.

## Context / problem

Many people do not know that tapping outside a dialog or pressing Back closes it, and some dialogs
had only text buttons such as Close, Cancel or Back at the bottom. The owner wants a familiar X at
the top right.

## Goals

- One shared title-with-X (`ui/DialogClose.kt`): an X at the top right of the dialog title, a 48 dp
  touch target, "Close" for screen readers, the app theme's colors.
- X always means "close without doing anything": the same as tapping outside, Back or the dialog's
  Cancel/Not now. It never saves, deletes or starts anything.
- A dialog whose only button closed it (Close, Cancel, Back) loses that button; real choices (Save,
  Cancel next to Save, Not now, Done, Update…) stay.
- Typed text is never lost silently: Edit memory's X, Cancel and outside tap ask "Discard your
  changes?" when the text changed.
- Full-screen pages with a "Close" text (Practice, Progress) show the X icon instead.
- AGENTS.md records the rule so every future dialog follows it.

## Non-goals

- No X on the Security update dialog (owner decision) or on "must decide" confirmations: delete a
  saved word, clear known words, reset all settings, clear AI memory. Each keeps its Cancel and Back.
- The fullscreen video and the TV-layout dialogs (TV search, words of a line): a TV remote moves
  between text buttons more easily than to a corner icon.
- Full-screen pages that already have a back arrow or an X (Settings, Language downloads, word card,
  AI panel) are unchanged.

## User-visible behavior

| Dialog | Before | After |
| --- | --- | --- |
| Open-source licenses, Check for updates (local build), Report a problem | Close button | X |
| AnkiConnect deck, App language, Export words | Cancel only | X |
| Caption language picker | Back | X |
| Daily goal, Import words, Known words offer, Comprehension, Check for updates | Their buttons | X plus their buttons (a lone Close that only closed goes) |
| Subtitle languages (the subtitle panel's gear) | Globe icon, centered two-line title, labels above full-width buttons, model hint, a two-line settings link, Done | Owner requests 2026-10-30: no icon, the title on the left beside the X, each label beside its button on one line, no model hint (it stays on the full settings page), a one-line settings link, no Done (choices apply at once), "Auto" instead of "Auto (recommended)" on its button, both buttons the same width (130 dp, with smaller labels so "Original language" fits on one line), and no empty button row (a `BasicAlertDialog` card); no swap button (most videos have captions in one language only) |
| Edit memory | Save, Cancel | X, Save, Cancel; X, Cancel or an outside tap ask before dropping changed text |
| Practice, Progress (full screen) | "Close" text | X icon |

## Technical constraints / invariants

- Dialogs keep the app theme (`DualSubTheme`) and take colors from `MaterialTheme.colorScheme`.
- The X's text is `dialog_close` in `strings_dialogs.xml` in all 8 languages; the discard strings are in the same file.
- Test tags: the X is `dialog_close` unless a screen passes its own (dialogs that can sit on top of
  another use their own tag).

## Proposed approach / plan

1. `ui/DialogClose.kt`: `DialogTitle(text, onClose, closeTag)` for `AlertDialog` titles and
   `CloseIconButton` for full-screen headers.
2. Apply it dialog by dialog as in the table; remove the lone close buttons.
3. Edit memory: a discard confirmation when the text changed.
4. Update the device tests that tapped removed buttons; add `DialogCloseUiTest` with screenshots.
5. AGENTS.md UI conventions; record the scale-readiness phone test result.

## Acceptance criteria

- [ ] Each dialog in the table shows the X at the top right, and tapping it closes the dialog
      without any other effect (device tests for licenses, Report a problem, app language, update).
- [ ] The Security update dialog and the four confirmations show no X.
- [ ] Edit memory with changed text asks before closing; unchanged text closes at once.
- [ ] Screenshots of the new title look right on the default and Light themes.
- [ ] CI parity command and both Android CI jobs pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` (no new logic beyond UI) | Local + CI |
| Android lint/build | CI parity command from AGENTS.md | Local + CI |
| Managed-device/emulator | `DialogCloseUiTest`, updated `NavigationRecoveryUiTest`, `AppUpdateUiTest`, `AppNoticesUiTest` | CI |
| Physical-device/manual | Owner opens several dialogs and taps X | Owner's phone |
| Live YouTube | Not applicable | — |

## Risks / edge cases

- A long title wraps beside the X instead of under it; the X stays at the top.
- Dialogs on top of another (a confirmation over a picker) must not share a test tag.

## Release intent

`release:patch` (default; the APK changes).

## Implementation result

- `ui/DialogClose.kt`: `CloseIconButton` and `DialogTitle` (with `centered` for dialogs whose title
  sits under an icon), strings in `strings_dialogs.xml`.
- X added with the lone close button removed: licenses (`DrawerFooter`), Check for updates on a local
  build and its Close in the up-to-date state (`AppUpdateUi`), Report a problem (`AppNotices`),
  AnkiConnect deck (`StudyToolsSettings`), App language, Export words, caption language picker
  (phones only; the TV layout keeps Back).
- X added beside the dialog's choices: daily goal, import words, comprehension (Cancel shows only
  while asking to mark every word known), known words offer, subtitle languages, Check for updates.
- Practice and Progress: the "Close" text became the X icon.
- Edit memory: `DiscardChangesDialog` when the text changed, for the X, Cancel and an outside tap.
- Removed the now unused `navigation_cancel`, `navigation_close`, `onboarding_progress_close`,
  `study_close` and `updates_close` in all 8 languages.
- Deviation: none from the plan.

## Validation result

To be filled after validation.
