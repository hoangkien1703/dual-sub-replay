# Meaning in this sentence from the translation, and the line's sound on cards

## Status

Implemented. The owner picked "All five (1 to 5)" on 2026-11-11 after reading lion737's comments in discussion #81 (19535052, 19535102, 19535211, 19535372, 19535953, 19539259, 19539801, 19549392). Merge and release need the owner's OK.

## Context / problem

lion737 (2026-11-11, with two screen videos and an AnkiDroid recording):

1. "Meaning in this sentence" doesn't match the translation line under the subtitle: "spitting" stayed *tüpürmək* (spit) while the line's translation says *çiskin* (drizzle). The AI got only the sentence and the word. lion737 asked the app to translate the context first and pick the word from it. The app already has that translation on screen, so it is sent with the request instead of a second translation.
2. Selecting several words ("I asked meteorologist and BBC weather presenter") gave only "mən soruşdum": the prompt asked for "one to four words" and replies over 80 characters were dropped.
3. The bar above a selected word shows only the dictionary meaning; lion737 wants the meaning in the sentence there too.
4. A Listening card had no sentence sound. The recording shows why: the line was paused part-way, the card was sent, and the learner went straight to AnkiDroid. The sound is only recorded once the line plays to its end, so it never came. Add audio to cards is on by default, so most learners hit this.
5. "Word + original audio" on the front, with on/off. The line's sound was on the back of every front but Listening.

Already there (the reply points to them): the on/off for Meaning in this sentence (Settings → Word learning) and for sound (Add audio to cards).

## Goals

- The request for the meaning in the sentence sends the line before, the sentence, the line after and the sentence's translation as the line under it shows it (all rows of a sentence split over rows), and asks for exactly the words of that translation that render the selection, or its own meaning when the translation leaves them out. Still one request.
- A selected phrase is asked as "Selected words", for the whole selection, and may get up to 240 characters.
- The selection bar shows the meaning in the sentence while Meaning in this sentence (AI) is on, in the Show first order, after the selection has stayed put for 500 ms. The bar and the word card share one request and its answer.
- When Send to sends a card whose line's sound isn't recorded yet and that line's video is open, the app plays the line once (from 750 ms before it to 400 ms after it) so the recorder gets it; the card says "Playing the line once so its sound goes on the card." Otherwise the old note stays.
- Flashcards & Anki → Card front → **Sentence sound on the front** (off by default, hidden for Listening): the line's sound moves from the back to the front. The note type's template changes once when the switch changes (AnkiDroid, desktop AnkiConnect); basic note types get the sound in the front field. The AI assistant can set it (`anki_card_front_sound`), shared setup links carry it, Reset all settings clears it.

## Non-goals

- No translation of the whole transcript by the AI.
- No replay for cards sent from Saved words (several words, maybe another video): they still wait for the line to play.
- No separate on/off for the word's voice and the line's sound.

## Acceptance criteria

- [x] `meaningContext` joins the translation of every row of the sentence, gives the neighbouring lines, and leaves the translation out when a row is untranslated or outside the transcript window (unit test).
- [x] The request contains the translation and asks for its words; a phrase is asked whole, without "one to four words" (unit test).
- [x] `cleanContextMeaning(..., phrase = true)` keeps up to 240 characters (unit test).
- [x] `soundReplayClip` starts 500 ms before the lead and ends after the tail, so `lineAudioReady` is true where it stops (unit test).
- [x] Sentence sound on the front moves `{{Sentence audio}}` to the front of every front but Listening, and the template changes once per switch change (unit tests).
- [x] Selection bar shows the meaning in the sentence big with "Dictionary: …" under it, or the other way round, and asks nothing while the setting is off (Compose test).
- [x] The switch is off by default, saves on, and is hidden for Listening (Compose test).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | WordMeaningInContextTest, AnkiCardLayoutTest, AnkiConnectNotesTest, LineAudioTest, BackupSharingTest, AppLanguageTest | Cloud |
| Android lint/build | formatCheck, complexityCheck, lintDebug, assembleDebug, assembleDebugAndroidTest | Cloud |
| Managed-device | PhraseSelectionUiTest (meaning in sentence in the bar), StudyToolsUiTest (Sentence sound on the front) | PR CI |
| Physical-device/manual | Tap "spitting" in a BBC Learning English video with Meaning in this sentence on: "çiskin"; select a long phrase; send a card while paused mid-line and hear the line replay, then check the card in AnkiDroid has the sound; turn on Sentence sound on the front with Card front Word | Owner's phone |

## Release intent

`release:patch`.

## Implementation result

As planned. Strings in all 9 languages.

## Validation result

See the PR for CI. Not yet tried on a phone.
