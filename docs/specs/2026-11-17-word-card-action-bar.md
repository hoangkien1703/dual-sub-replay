# Word card action bar

## Status

Implemented. The owner picked the design from mockups on 2026-11-17 (Vietnam time) and asked for a PR.
Device tests run in PR CI; the phone check is still to do.

## Context / problem

The word card showed up to eight controls stacked under each other: Explain the grammar with AI,
an Online example checkbox, a Deck chip, Save to vocabulary, I know this word, a split Send to
AnkiDroid button with its ▾ list, and a long status line. The owner found it confusing: a new user
could not tell which button to tap.

Mockups (project files `word-card-redesign/`): option C (icon bar) was picked. Then More moved to
the far right, Ask AI took Know it's place, and "I know this word" moved into More. Save became a
wide "Save to Practice" with its icon (Practice is the app's own feature; Anki needs another app),
and More stays at the far right even when nothing else shows.

## Goals

- One bar at the bottom of the card, always in view: a wide **Save to Practice**, then **Ask AI**
  and **Anki** when they apply, and **More** at the far right.
- Features that need setup show only once set up: Ask AI only while the assistant is on, Anki only
  while Send to shows, "I know this word" only while Known words is on, and the deck only when there
  is more than the language's own deck.
- Fewer per-word choices on the card: the Online example box becomes a setting.
- New optional setting: Save to Practice also sends the card to the flashcard app.

## Non-goals

- No change to what is saved, how cards are built, or how Practice and Anki decks work.
- Practice → Export keeps its Send all button with the ▾ list of apps.

## User-visible behavior

- Card: word and speaker, meaning, part of speech, reading, grammar points and character details
  scroll as before. Under a line, the bar stays in view.
- **Save to Practice** (about 2.4 slots wide, bookmark icon, may wrap to two lines): saves the word.
  After saving it shows "In Practice" in the quieter accent shade and can't be tapped; the line
  under it says "Saved. Find it any time in the Practice tab."
- **Ask AI** (sparkle icon): only while Settings → AI assistant is on; asks the assistant to explain
  the line's grammar and closes the card (was the "Explain the grammar with AI" button; its old
  text is now what a screen reader says). It no longer needs the line to be in a grammar language.
- **Anki** (cards icon): only while Send to shows (Send to flashcard apps on, and AnkiDroid installed
  or AnkiConnect picked). Sends to the app picked in Settings → Flashcards & Anki → Send words to;
  the word card's ▾ list is gone. After sending it shows a tick and "Sent"; tapping again asks
  before sending a second card. Problems and Open AnkiDroid show under the bar, as before.
- **More** (three dots, always at the far right; a hidden Ask AI or Anki leaves its space empty):
  a small menu with "I know this word" (a tick box; only while Known words is on, and only for one
  word in the video's language) and **Word card settings**, which closes the card and opens
  Settings at Word learning.
- **Deck: English ▾** shows in the card only when named decks exist (or the word is in one).
  New decks can still be made from a saved word in Practice.
- Settings → Flashcards & Anki:
  - **Video example for saved words** (new, first row, on by default): new saved words get Play
    online example in Practice, as the old Online example box did by default. Each word can still
    change it in Practice. A word saved again keeps its own choice.
  - **Also send saved words** (new, under Send words to, **off by default**): Save to Practice also
    sends the card to the Send to app. While on, the Anki button hides; the line under the bar
    says "Saved to Practice and sent to AnkiDroid." If that send fails, the problem shows and the
    Anki button comes back to retry.
- The first "I know this word" no longer offers to turn Known words on, since the button only
  shows while Known words is on.
- The AI assistant can read and change both new settings (`send_saved_words`,
  `saved_word_video_example`).

## Technical constraints / invariants

- [Study tools and Anki](../project/tech-stack.md#study-tools-and-anki): one Send to button, never a
  button per app; AnkiDroid only through its provider and permission. The Anki button sends to the
  one app picked in Settings.
- [Default settings](../../AGENTS.md#default-settings): the new send setting is off; the video
  example keeps the old box's default (on).
- [Settings](../project/tech-stack.md#settings-backup-and-sharing): both keys in `SETTING_SPECS`
  (send: backup, video example: share), both switches in `SearchableSetting`, both in
  `STUDY_SETTING_KEYS` so Reset all settings clears them.
- Strings in all 9 interface languages.

## Proposed approach / plan

1. `ui/WordCardActions.kt`: `WordCardActionBar` (Save to Practice, slots, More menu),
   `WordCardSend` (the old split button's sending, permission, problem and sound-wait state),
   `knownWordToggle`, `WordCardStatus`.
2. `ui/WordLearningSheet.kt`: the scrolling part plus the bar under a divider; save sends too when
   Also send saved words is on; deck chip only with named decks; Online example box removed.
3. `ui/GrammarExplanations.kt`: Ask AI leaves the grammar section as `askAiAboutLine`.
4. `ui/StudyTools.kt`: remove `StudyWordActions`, the split button and the Known words offer.
5. Settings rows, search, transfer specs, assistant switches, strings, assistant guide.
6. `DualSubApp.kt`: Word card settings opens Settings → Word learning (`OnSettingsRequest`).

## Acceptance criteria

- [x] Default settings: the bar shows Save to Practice and More only, More's right edge is the bar's
  right edge, More's menu has Word card settings and no "I know this word"
  (`WordLearningDialogTest.aNewUsersBarHasSaveAndMoreAtTheFarRightAndMoreOpensWordCardSettings`).
- [x] Save to Practice saves with the video example on by default, then shows In Practice and the
  Practice tab note; with the setting off it saves without the clip
  (`WordLearningDialogTest.pronouncesOnce…`, `videoExampleSettingOffSavesTheWordWithoutItsClip`).
- [x] Ask AI shows only with the assistant on and asks about the whole line
  (`AiAssistantPanelUiTest` grammar tests).
- [x] Anki sits between Save and More and sends to the Settings app
  (`StudyToolsUiTest.theAnkiButtonSendsToTheAppPickedInSettings`).
- [x] Also send saved words hides Anki and Save sends; a failed send brings Anki back
  (`StudyToolsUiTest.alsoSendSavedWordsHidesTheAnkiButtonAndSaveSendsTheCard`).
- [x] "I know this word" only in More and only while Known words is on
  (`StudyToolsUiTest.theWordCardShowsHowOftenAutomaticSawTheWordAndMoreHoldsIKnowThisWord`,
  `iKnowThisWordWaitsUntilKnownWordsIsOn`).
- [x] The settings rows exist with their defaults and save their values
  (`StudyToolsUiTest.flashcardsAndAnkiHoldsTheVideoExampleAndAlsoSendSavedWords`); search finds them
  (`SettingsSearchTest`).
- [ ] The owner checks the card on the phone (preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes (settings defaults, reset keys, assistant switches) | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Cloud container |
| Managed-device/emulator | Word card, Study tools and AI panel UI tests above; screenshots `word-card-*`, `study-known-word-seen`, `flashcards-send-saved-words` | PR CI |
| Physical-device/manual | Tap a word: bar layout, More menu, Word card settings jump, Anki send with AnkiDroid | Owner's phone, preview APK |
| Live YouTube | Not applicable: no player or page change | — |

## Risks / edge cases

- Very long translations of "Save to Practice" wrap to two lines; the button grows taller, the
  icons stay one line (labels end in "…" if too long).
- An existing per-word Online example choice is unchanged; only new words follow the setting.
- With Also send saved words on and AnkiDroid not yet permitted, the permission prompt appears
  right after Save to Practice (a tap the user made), as it did for the Send to button.

## Release intent

`release:patch` (the owner's standing rule: patch releases only). Changes the APK.

## Implementation result

As planned. Unused strings of the removed controls (Save to vocabulary, the Known words offer,
Sending…, the example note) were removed from every language.

## Validation result

To be filled from CI on the PR.
