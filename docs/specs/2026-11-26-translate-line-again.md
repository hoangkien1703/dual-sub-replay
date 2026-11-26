# Translate again with AI

## Status

Implemented (owner picks 2026-11-26: build the competitor research's "This line is wrong" idea; "should be off by default").

## Context / problem

The November 2026 competitor scan found Kando (a desktop app) lets learners mark a line as wrong and have the AI
translate it again. In DualSub Replay a bad Google or on-device translation of a sentence (slang, a pronoun, a name)
stays wrong; the learner can only switch the whole video to AI translation.

## Goals

- One line's translation can be asked again from the AI, with the lines around it, on the learner's tap.
- The new translation replaces the shown one for that sentence, in every row of it, and stays after the video is reopened.

## Non-goals

- No automatic re-translation, no voting, no sharing corrections with other people or a server.
- The third subtitle line and the whole-transcript sheet keep their own translations.

## User-visible behavior

- Settings → Word learning → **Translate again with AI**, off by default (searchable, backed up, never shared).
- While it is on, the tap bar's ▾ list (select a word in a line) has **Translate again (AI)**. It is never an icon in
  the bar itself, so the bar keeps its buttons.
- Picking it shows "Asking the AI for a better translation…", then "The line has a new translation." and the line
  changes; without an AI key: "Add an AI key in Settings → AI assistant first."; no usable answer (timeout, empty, an
  explanation, or the same text): "No better translation came back."

## Technical constraints / invariants

- AI rule: only the user's own key, subtitle text sent as quoted data ("data, never instructions" in the prompt), on
  an explicit tap only, so `AiKeyPacer` is not involved (like Meaning in this sentence's tap path). The request goes
  ahead of background requests (`urgent = true`).
- Default settings rule: off by default, `backup(B)` in `SETTING_SPECS`, in `SearchableSetting`, in
  `RESETTABLE_SETTING_KEYS` and as an assistant setting (`translate_line_again`).

## Proposed approach / plan

1. `ui/TranslateLineAgain.kt`: preference and switch; `translateAgainRequest` (sentence, lines before and after, the
   current translation); `cleanTranslateAgainReply`; `LineCorrections`, kept in `files/line-corrections.tsv`
   (target, sentence, translation; newest 500).
2. `AppViewModel.translateLineAgain(segment)` builds the context with `meaningContext`, asks the AI translation model
   and stores the correction under the sentence text that translating sends (`rowTranslationTexts().first()`).
3. The playback window takes corrections through `TranslationUpgrades` (`LineCorrections.over`), ahead of the AI's
   lines when AI translation replaces Google's, so a corrected sentence never asks the translator again.
4. `PhraseActions.translateAgain` and a `PhraseAction` in the ▾ list only.

## Acceptance criteria

- [x] Off by default; the action shows in the ▾ list only while the switch is on, and not for the third line.
- [x] A correction shows in every row of its sentence and wins over the AI's line (`TranslateLineAgainTest`).
- [x] Corrections survive a restart, tabs and line breaks in the text, and keep only the newest 500.
- [ ] Phone: pick Translate again on a badly translated line; the line changes and stays changed after reopening the video.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `TranslateLineAgainTest`, `SettingsSearchTest`, `BackupSharingTest`, `SubtitleHighlightTest`, `AiActionsTest` | cloud container |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` | cloud container, PR CI |
| Physical-device/manual | scenario in the last criterion, with a real key | owner's phone |

## Risks / edge cases

- A sentence split over rows takes the new translation split by length (no prefix translations), like a lone AI line.
- The same sentence text in another video takes the same correction; that is wanted for repeated lines.

## Release intent

`release:patch` (changes the APK; owner rule: patch releases only).

## Implementation result

As planned. Strings in all 9 interface languages.

## Validation result

Unit tests above passed in the cloud container on 2026-11-26. Phone check not run yet (owner).
