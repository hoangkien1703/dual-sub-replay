# Sentence sound that plays, Front / Back / Off for card sounds and picture, loop in the background

## Status

Implemented. The owner picked "All except speed" on 2026-11-12 after reading the new comments in discussion #81 (lion737 19558131, 19558514, 19560463; 25kiuo 19556994, 19562439). The quick speed button was left out: YouTube's own player already has a speed menu. Merge and release need the owner's OK.

## Context / problem

1. lion737 (screen recording, 2026-11-12): the line's sound on Anki cards is still silent, or only plays sometimes. Every card shows two play buttons, so the Sentence audio field has a `[sound:]` file, but only the word's voice plays. Two causes found in the code:
   - Android's own type table (`android.mime.types`) says a `.m4a` file is `audio/mpeg`. The plain `FileProvider` reports that type, and AnkiDroid names the copy it keeps after the reported type, so every sentence clip (AAC in MPEG-4) was kept as `.mp3`. Players that trust the name don't play it.
   - v1.8.36 plays a line once when a card is sent while it is paused half way. The recorder then held two runs for the line: the older broken one and the replay. `clip()` looked at the oldest run first, found it broken, and gave nothing, so the card waited for a sound that never came.
2. lion737 asks for four switches: original audio front, original audio back, word audio front, word audio back. The back template starts with `{{FrontSide}}`, so anything on the front also plays on the back: "front and back" would play twice. One Front / Back / Off picker per sound does the same with fewer rows and no double sound.
3. lion737 asks for Front / Back / Off for the picture too.
4. 25kiuo: in the background (Home, screen off) the line loop and Repeat stop; the video just plays on. The page poll stopped with the screen's lifecycle and the app's playback request was off while the app wasn't visible, so StudyPlayback never saw the time move.

## Goals

- A sentence clip lent to AnkiDroid is reported as `audio/mp4`, so AnkiDroid keeps it as `.m4a`.
- `clip()` takes the newest run that holds the whole line, so a line paused half way and replayed gives its clip.
- A clip whose samples never pass ±64 (silence) is never put on a card.
- Flashcards & Anki: three pickers, **Word sound**, **Sentence sound**, **Picture**, each Front / Back / Off. Defaults keep today's cards: word sound where it is for the chosen Card front, sentence sound on the back, picture off. Earlier settings (Add audio to cards, Sentence sound on the front, Picture on cards) are carried over once.
- For Listening the sentence sound is always on the front, so its picker offers Front / Off.
- Changing Card front moves the word sound to its usual place for that front, unless it is Off.
- The note type's template changes once when a place changes (AnkiDroid, AnkiConnect); basic note types get the sounds and picture in the right field.
- The AI assistant can set the three places (`anki_card_word_sound`, `anki_card_sentence_sound`, `anki_card_picture_place`); setup links and backups carry them; Reset all settings clears them.
- With a loop, Repeat, a play mode other than Normal or Skip silence on, the page keeps being read about once a second while the app is in the background and the video plays, so the loop and Repeat keep working with Home or the screen off.

## Non-goals

- No speed button (YouTube's own menu has it).
- Old cards whose clip was saved as `.mp3` are not repaired; sending the card again gives a working clip.
- No background polling when nothing steers playback.

## Acceptance criteria

- [x] `cardMediaType("x.m4a")` is `audio/mp4`; other files keep Android's type (unit test).
- [x] A silent clip is never attached (unit test `aSilentClipNeverGoesOnACard`).
- [x] A line paused half way and replayed gives its clip; a clip lent to AnkiDroid keeps its `.m4a` name (device tests in `LineAudioWebViewTest`).
- [x] Each place puts the word sound, sentence sound and picture on the chosen side, default templates stay byte for byte the same, and the template key changes only when a place changes (unit tests in `AnkiCardLayoutTest`, `AnkiConnectNotesTest`).
- [x] Earlier settings carry over to the same cards (unit test `FlashcardPlacesTest`).
- [x] The pickers show and store their choice; the sentence sound is on the back until moved (Compose tests in `StudyToolsUiTest`).
- [ ] Loop and Repeat keep working with Home and with the screen off on a real phone (owner, preview APK).
- [ ] A new card sent to AnkiDroid plays the line's sound (owner, preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Cloud |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Cloud |
| Managed-device/emulator | `LineAudioWebViewTest`, `StudyToolsUiTest` pass | PR CI (no KVM in cloud) |
| Live YouTube | Recorder `clip()` with a pause half way and a replay gives the replayed run | Desktop YouTube in a cloud browser |
| Physical-device/manual | Send a card, play it in AnkiDroid; loop a line, press Home and turn the screen off | Owner's phone |

## Risks / edge cases

- Background polling uses some battery while a loop or Repeat is on and the video plays; it stops when playback pauses or nothing steers.
- The `.m4a` name relies on AnkiDroid naming the copy from the reported type (AnkiDroid's `CardContentProvider` does this); the device test checks the type the provider reports.

## Release intent

`release:patch`: bug fixes and a settings change inside an existing section.

## Implementation result

As planned. `CardMediaProvider` replaces the plain `FileProvider` for the card media authority. `CardPlace` and `CardSides` in `AnkiCardLayout.kt` replace the two booleans. `StudyOffScreen.steering` tells the page poll and the view model that playback needs the page time while off screen.

## Validation result

Local: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed. The recorder fix was checked on desktop YouTube in a cloud browser. Device tests and the phone checks are in the PR.
