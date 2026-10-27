# Select subtitle words the way Android selects text

## Status

Implemented. In the "Make word selection work like Android" project thread (2026-10-27) Kien
asked for subtitle word selection to work like normal touchscreen text selection: tap to select a
word, hold and drag to select several words, tap somewhere else to cancel, and a draggable pin at
both ends of the selection, like Android's blue handles. The suggested design below was posted in
the thread before building. Merge and release stay with the owner.

Updates the [multi-word selection spec](2026-09-29-multi-word-selection.md), whose non-goals
excluded drag handles and long-press selection.

## Context / problem

`SelectableSubtitleText` (`ui/PhraseSelection.kt`) selected a phrase only by taps: the first tap
selected a word, a tap on another word extended the selection to it, and a tap inside cleared it.
Anyone used to Android expects to hold and drag, and to move either end with a handle. Tapping
somewhere else on the screen left the selection and its bar open.

## Goals

- **Tap a word**: selects that word and pauses the video, as before. A tap on another word moves
  the selection there; a tap inside the selection, a tap between words of that line, ✕, Back, or
  playing the video clears it.
- **Hold a word and drag**: after Android's long-press time the held word is selected, and every
  word the finger passes joins it, in either direction. Holding first keeps a plain swipe for
  scrolling the subtitle list and for moving the subtitle box over the video.
- **Handles**: a teardrop handle in the theme's accent color hangs under each end of the selection
  (outward from it; mirrored in right-to-left text). Dragging one moves that end word by word; a
  handle dragged past the other end swaps the ends, as on Android.
- Selections snap to whole words from the existing tokenizers (Japanese and Chinese words stay
  whole) and stay inside one subtitle line.
- **Tap somewhere else**: a tap anywhere in the app outside the subtitle lines clears the
  selection. A scroll or drag does not.
- While a finger drags, the Copy / Translate / Pronounce bar hides (as Android hides its text
  toolbar), and speaking and translating wait until the finger lifts, so a drag over five words
  sends one request, not five.
- A light tick (`TextHandleMove` haptic) each time a drag changes the selection, and the system
  magnifier over the line under the finger while dragging (Android 9 and newer).
- With one word selected the bar's hint now says "Drag the handles to select more words", in all
  8 interface languages.

## Non-goals

- No selection across two subtitle lines, or across the original and translated rows.
- No change to the bar's actions, the word card, or what Translate sends.
- No auto-scroll of the subtitle list while dragging a handle past the screen edge.

## Technical constraints / invariants

- No new UI string literals in Kotlin; `practice_drag_handles` replaces `practice_tap_another_word`
  in every `values-*` folder (`AppLanguageTest`, lint `MissingTranslation`).
- Colors come from `MaterialTheme.colorScheme` (handles use `primary`).
- The subtitle box over the video keeps its drag-to-move and tap-for-buttons gestures: a held
  word's drag consumes the moves, so the box's own drag never starts.

## Proposed approach / plan

1. `ClickableText` becomes `BasicText` with two pointer handlers: `detectTapGestures` (tap
   selects / moves / clears, and marks the press as a subtitle press) and, inside it,
   `detectDragGesturesAfterLongPress` (so a long press's lift never counts as a tap).
2. Taps map to the character under the finger (`characterAt`), not the nearest caret position, so
   tapping the right half of a word's last character no longer picks the next word in Japanese
   or Chinese. Drags use the nearest word (`nearestWord`), so moving between or past words still
   picks one.
3. Handles are small `Popup`s, so the card around the line never clips them. A handle reports the
   finger's movement measured on the screen (`localToScreen`), so the handle popup jumping to the
   next word under the finger does not shift the drag. It aims at the middle of its line.
4. `PhraseSelectionController.select` sets a range (pausing for a new line);
   `Modifier.clearPhraseSelectionOnTapElsewhere` on `LearningPlayerRoot`'s root box clears the
   selection after a tap that did not press a subtitle line. The handles and the bar are popups of
   their own, so their taps never reach it.
5. The bar placed below the selection (no room above) leaves room for the handles.

## Acceptance criteria

- [x] A tap on a word selects it; a tap on another word moves the selection; a tap inside clears
      it (`PhraseSelectionTest.tapsSelectMoveAndClearAWordAsAndroidDoes`,
      `PhraseSelectionUiTest.tappingAnotherWordMovesTheSelectionAndCopyKeepsIt`).
- [x] Dragging the end handle from "looking" onto "to" selects "looking forward to" and Translate
      sends that phrase (`draggingTheEndHandleSelectsThePhraseAndTranslateSendsIt`); the same on
      the translated line (`translatedLineSelectsItsOwnWords`).
- [x] Holding "really" and dragging onto "forward" selects "really looking forward" without a
      replay (`holdingAWordAndDraggingSelectsEveryWordPassed`).
- [x] A tap outside the subtitle lines clears the selection; a tap on another line's word moves
      it (`tappingOutsideTheSubtitlesClearsTheSelection`).
- [x] Drag ranges and nearest-word picking (`draggingSpansFromTheFixedWordEitherWay`,
      `aDragBetweenOrPastWordsStillPicksTheClosestWord`), bar below leaves room for the handles.
- [ ] On a phone: handles look like Android's, are easy to grab, and dragging feels smooth in the
      subtitle list and in the box over the video (portrait and fullscreen).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest --tests "*PhraseSelectionTest"` passes | Cloud container |
| Android lint/build | `formatCheck complexityCheck`, `compileDebugAndroidTestKotlin`; full CI parity in PR CI | Cloud container + PR CI |
| Managed-device/emulator | `PhraseSelectionUiTest` in `managed-device-tests`, screenshots via `saveUiEvidence` | PR CI (no `/dev/kvm` in the container) |
| Physical-device/manual | Kien tests the preview build: tap, hold-and-drag, both handles, tap elsewhere, in the list and over the video | Owner's phone |
| Live YouTube | Not needed: no YouTube page or script change | Not applicable |

## Risks / edge cases

- A handle's 40dp touch area lies over the line below it, so a tap there grabs the handle, as on
  Android.
- Popups don't clip to the scrolling list: a handle of a line scrolled half out of view can show
  over the list's edge. The action bar already behaves this way.
- In an ellipsized line over the video, words past the ellipsis can't be reached by a drag.

## Release intent

`release:patch` (default; the owner asked for a patch-sized behavior change to selection).

## Implementation result

As planned. `SelectableSubtitleText` is split into `Modifier.selectableWords` (gestures),
`SelectionHandles` / `SelectionHandle` (popups) and `SelectionDrag` (drag state) to stay within the
complexity limits.

## Validation result

- Passed in the cloud container: `formatCheck`, `complexityCheck`, `PhraseSelectionTest`,
  `compileDebugAndroidTestKotlin`.
- Instrumented tests and the preview build: see the PR's CI.
- Physical device: not yet tested.
