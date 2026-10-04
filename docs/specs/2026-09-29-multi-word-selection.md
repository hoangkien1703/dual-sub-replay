# Select several subtitle words with a Copy / Translate / Pronounce bar

## Status

Validated. The owner asked for this in the project thread on 2026-09-29, agreed to the design
below, and chose "Show action bar" for single-word taps. The owner tested it on their phone on
2026-09-29 and reported that it works.

## Context / problem

Tapping a subtitle word opens `WordLearningDialog` for that one token. A word on its own often
does not carry its full meaning: "looking forward to", "give up" or 思ったより are translated
correctly only as a phrase. The owner wants to pick one or several neighbouring words, in the
original or the translated line, like the Renshuu app does, before translating.

The dialog also puts **Save word** as the confirm button right beside **Close**, which the owner
finds confusing.

## Goals

- Select one word or a run of neighbouring words in any tappable subtitle line: original and
  translated, in the transcript cards and in the floating/fullscreen overlay.
- Show a small bar right above the selection with **Copy**, **Translate** and **Pronounce**.
- **Translate** opens the existing learning card for the whole selection: it translates the
  phrase as one text, keeps auto-pronounce, the meaning field, the online example choice and
  saving.
- Move saving away from Close: Close becomes an ✕ at the top of the card and a full-width
  **Save to vocabulary** button sits under the meaning.

## Non-goals

- No drag handles or long-press text selection.
- No selection across two subtitle lines, or across the original and translated rows.
- No dictionary data beyond what the tokenizer already provides (part of speech, reading).
- No change to vocabulary storage, review scheduling or backup/import formats.

## User-visible behavior

- **Before:** tapping a word opens the learning card at once for that word only. Save sits next
  to Close at the bottom.
- **After:**
  - Tapping a word pauses the video, highlights the word and shows the bar above it (below it when
    there is no room above). With one word selected, the bar shows that word's translation under
    the buttons and says "Tap another word to select a phrase". Once the selection grows to a
    phrase, the bar shows the whole phrase's translation the same way (revised 2026-10-04, see
    below).
  - The bar uses the app's dark teal surface with a thin accent border, not a white panel.
  - Tapping another word in the same line extends the selection to every word between the two.
    Tapping a word inside the selection, tapping a blank part of that line, the bar's ✕, Back, or
    playing the video again clears it. Selecting in another line moves the selection there.
  - **Copy** copies the exact selected text (Japanese stays without added spaces) and the button
    shows "Copied".
  - **Pronounce** speaks the whole selection in its line's language. If no voice is available, the
    bar shows the same message the card shows.
  - **Translate** clears the selection and opens the learning card for it. For a phrase the card
    shows "Phrase" in place of the part of speech, the combined reading when there is one, and a
    "Word by word" list with each word's part of speech, reading and a speaker button.
  - The card's header holds the phrase, a speaker button and an ✕. Save is a full-width button
    under the meaning ("Save to vocabulary" → "Saving…" → "Saved").
  - Saved phrases appear in Saved words and reviews like single words.
- Tapping a blank part of a line that has no selection keeps its current action (replay the
  paragraph in the transcript, toggle the overlay actions in the overlay).

## Technical constraints / invariants

- One WebView and the existing pause path (`YouTubeWebController.pause`) only; no new player.
- Translation stays on-device through `translator.translateSingle`, which already accepts a
  phrase.
- `SavedWord` keeps its fields and id scheme, so no database migration: a phrase is saved as
  `word = phrase`, `reading = joined readings`.
- Plain JUnit4 for the selection and positioning logic, in small `internal` functions.
- PR #85 (active line stays visible on pause) is changing `SubtitleTimeline` in
  `ui/DualSubApp.kt`; stay out of that region so both PRs merge cleanly.

## Proposed approach / plan

1. `data/SavedWord.kt`: add `parts: List<AnalyzedToken>` to `WordTap` and
   `LearningWordSelection` (defaults to the single token), plus `learningSourceLanguage()` shared
   by the view model and the bar.
2. `ui/PhraseSelection.kt`:
   - Pure helpers: `nextPhraseRange` (start, extend, clear), `phraseTap` (builds the phrase token
     from the text and the word range), `subtitleWordTokens` (the same tokens tap-to-learn uses)
     and `phraseBarPosition` (above, or below when there is no room, clamped to the window).
   - `PhraseSelectionController`, provided through `LocalPhraseSelection`, holds the one active
     selection and the root actions (pause, pronounce, stop speech, speech message).
   - `SelectableSubtitleText` replaces the four `ClickableText` blocks in `SubtitleCard.kt` and
     `LearningPlayerRoot.kt`: highlights the range, shows the bar in a non-focusable `Popup`,
     handles Back and clears when the line's text changes or it leaves the screen.
3. `LearningPlayerRoot` creates the controller around both `DualSubApp` and the overlay;
   `DualSubApp` binds `webController.pause` and `WordPronouncer` to it.
4. `WordLearningSheet.kt`: rebuild the card as a `Dialog` with header ✕, full-width Save button
   under the meaning, and the word-by-word list for phrases. Keep existing test tags and the
   parameter order.
5. Tests: unit tests for the helpers and phrase saving; instrumented tests for the bar and the
   phrase card.

## Acceptance criteria

- [x] Tap, extend backwards/forwards, and clear rules hold (`PhraseSelectionTest`).
- [x] A phrase keeps the exact source text between its first and last word, including Japanese
  without spaces, and lists every word as a part (`PhraseSelectionTest`).
- [x] A single selected word shows its translation in the bar, and a phrase shows the whole
  phrase's translation as soon as it is selected (`PhraseSelectionUiTest`, revised 2026-10-04).
- [x] The bar sits above the selection, flips below it near the top of the window, and stays
  inside the window horizontally (`PhraseSelectionTest`).
- [x] A saved phrase keeps the phrase text and joined readings, and its id differs from its first
  word's id (`PhraseSelectionTest`).
- [x] Tapping two words in a transcript line shows the bar; Translate sends the whole phrase with
  its parts; Copy and Pronounce do not open the card (`PhraseSelectionUiTest`).
- [x] The card shows the ✕ close and the Save button, and a phrase shows its word-by-word list
  with per-word speech (`WordLearningDialogTest`).
- [x] Existing word card behavior still passes (`WordLearningDialogTest` existing cases).
- [x] Owner's phone: select a phrase in the overlay and the transcript on a live video,
  translate it, save it, and find it in Saved words.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes, including `PhraseSelectionTest` | Local Linux, CI |
| Android lint/build | `./gradlew formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the new UI tests | CI `managed-device-tests` |
| Physical-device/manual | Scenario in the last acceptance criterion | Owner's phone |
| Live YouTube | Same scenario on a real video with captions | Owner's phone |

## Risks / edge cases

- The bar is a separate popup window; in fullscreen it must not bring back system bars. It is
  non-focusable for that reason.
- Very long selections produce long TTS and translation requests; they are limited to one
  subtitle line, which keeps them short.
- Phrases saved from the translated line play the original sentence as their example, as single
  words already do.

## Release intent

Exact version `Release-Version: 1.3.0` in the PR description. The owner asked on 2026-09-29 to
merge this and create v1.3.0, since it is a new feature.

## Revision after the owner's phone test (2026-09-29)

The owner found the white bar too bright and asked for a colour that matches the app, and changed
the single-word behaviour: the first tapped word shows its translation right in the bar, while
extending to more words keeps the phrase flow without translating immediately. The bar now uses
`surfaceContainerHighest` with an accent border, and `PhraseActions.translate`
(`AppViewModel.translateSubtitleText`) fills the single-word translation. The owner also asked for the bar to
close when the video plays again: `BindPhraseActions` clears the selection when `playbackPaused`
turns false (`PhraseSelectionTest.selectingPausesOnceAndResumingClearsTheSelection` covers the
controller side).

## Revision: phrases translate immediately (2026-10-04)

The owner asked in the project thread on 2026-10-04 for a multi-word selection to show its
translation immediately, like a single word. This reverses the 2026-09-29 choice to keep phrases
untranslated until **Translate** is pressed.

- **Acceptance criteria:** extending a selection to a phrase replaces the bar's single-word
  translation with the phrase's translation, shown as "Translating…" until it arrives; each
  change to the selection translates the new text once; Copy, Translate, Pronounce and the
  learning card are unchanged.
- **Plan:** `SelectableSubtitleText` passes `quickTranslate` for every selection instead of only
  single words. `QuickTranslation` already restarts (cancelling the previous request) when the
  selected text changes.
- **Release intent:** `release:patch` (default; the owner gave no other intent).
- **Validation:** `PhraseSelectionUiTest.tappingTwoWordsSelectsThePhraseAndTranslateSendsIt` now
  expects the phrase's translation in the bar and both texts sent to `translate`.

## Implementation result

As planned, with these details:

- `PhraseSelectionController` is created in `LearningPlayerRoot` around both `DualSubApp` and the
  floating overlay, because the overlay is composed outside `DualSubApp`. `BindPhraseActions` in
  `DualSubApp` connects it to `YouTubeWebController.pause` and `WordPronouncer`.
- Selections clear when the bar's ✕, Back, a tap inside the selection or a blank tap in that line
  is used, when Translate opens the card, when the line's text changes, or when the line leaves
  the screen. The popup is not focusable, so it never takes focus from the video or the overlay.
- `findWordAtOffset` now reuses `subtitleWordTokens`, so tap-to-learn and selection share one
  tokenization.
- The card became a `Dialog` with the header ✕ and the Save button under the meaning. Existing
  test tags stay; new tags are `close_word_card`, `pronounce_part_N` and the `phrase_*` bar tags.
- README and the Saved words empty message describe the new flow; the technical context lists
  `ui/PhraseSelection.kt`.

## Validation result

- Passed locally (Linux, JDK 21, Android SDK 36):
  `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`,
  332 unit tests with 0 failures, including 10 in `PhraseSelectionTest`.
- Not run locally: `pixel2Api36DebugAndroidTest` (no KVM on this host). CI's
  `managed-device-tests` passed on `141f75c`, including `PhraseSelectionUiTest` and the new
  `WordLearningDialogTest` case; `fdroid-build` and `fdroid-device-tests` passed too. Final-head
  CI status is on the PR.
- Passed: the owner's phone and live YouTube scenario (owner's report on 2026-09-29, including
  the darker bar, single-word translation and closing the bar on resume).
