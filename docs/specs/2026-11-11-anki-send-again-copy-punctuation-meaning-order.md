# Send a card again, copy end punctuation, meaning order

## Status

Implemented. The owner picked "Fix 1, 2 and add order choice (3)" on 2026-11-11 after reading lion737's comments in discussion #81 (19525580, 19525802, 19526000). It is part of PR #213 (one preview APK, owner preference). Merge and release need the owner's OK.

## Context / problem

lion737 (2026-11-10, after v1.8.31):

1. "I can't send a card to Anki a second time" after changing settings or the card. Sent words were remembered and the word card's Send to button stayed off.
2. Copying a selected line leaves out the end punctuation (". ? !"). The selection covers word tokens only, so Copy stopped at the last word.
3. A setting to choose which meaning comes first: the meaning in the sentence (AI) or the dictionary meaning. On 2026-11-10 the owner chose no order setting; lion737 asked again after reading the reply, and the owner picked adding it on 2026-11-11.

Not in this change: Listening cards with sound cut at the wrong point (needs an example video and line), and sentence sound on every card (the sound is recorded only while the line plays in the app; no YouTube download).

## Goals

- Word card: tapping "Sent to AnkiDroid/AnkiConnect" asks "Send this card again?"; **Send again** adds another card (AnkiConnect with `allowDuplicate`).
- Copy in the selection bar keeps the line's opening (¿ ¡ quotes, brackets) and closing (. ? ! … quotes, 。？！) punctuation when the selection reaches that end of the line. A comma in the middle stays out.
- Settings → Word learning → Meaning in this sentence (AI) → **Show first**: Meaning in this sentence (default) or Dictionary meaning. With the dictionary first, the card shows it big with "In this sentence: …" under it, and saves "getmək (gedirəm)".
- The AI assistant can set it (`meaning_order`: `sentence_first` / `dictionary_first`). Reset all settings clears it.

## Non-goals

- No change to the bulk Send to in Saved words (it still skips words sent before).
- No update of an existing Anki note; Send again adds a new one.

## Acceptance criteria

- [x] `unsentAnkiWords(..., again = true)` returns every word once.
- [x] `copiedPhrase` keeps ¿…? and 。 at the line's ends, and leaves out a comma in the middle.
- [x] `combinedMeaning(..., contextFirst = false)` gives "getmək (gedirəm)"; `storedMeaningOrder` falls back to sentence first.
- [x] Word card with Dictionary first: big "getmək", "In this sentence: gedirəm" under it, saved "getmək (gedirəm)" (Compose test).
- [x] Show first appears when Meaning in this sentence is on, and saves `dictionary_first` (Compose test).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | AnkiDroidNotesTest, PhraseSelectionTest, WordMeaningInContextTest, AiAssistantActionsTest, SubtitleHighlightTest | Cloud |
| Android lint/build | formatCheck, complexityCheck, lintDebug, compileDebugAndroidTestKotlin | Cloud |
| Managed-device | WordLearningDialogTest.showFirstDictionaryMeaningPutsTheSentenceMeaningUnderIt, LearningPlayerUiTest settings test | PR CI |
| Physical-device/manual | Send a word twice to AnkiDroid; copy a full line with "?" and paste; switch Show first | Owner's phone |

## Release intent

`release:patch` (with the rest of PR #213).

## Implementation result

As planned. Strings in all 9 languages.

## Validation result

See PR #213 for CI. Not yet tried on a phone.
