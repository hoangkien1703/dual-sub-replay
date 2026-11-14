# Sentence sound after a long pause, and Play first for card sounds

## Status

Implemented. The owner picked "Fix sound + order picker" on 2026-11-14 after reading the new comments in discussion #81 (lion737 19582027, 19582125, 19583564, 19608419, 19608584). The Web and Reading sections (19583118) were declined, and FelixEhrlichKIT's word-by-word meanings (19609401) went to the backlog. Merge and release need the owner's OK.

## Context / problem

1. lion737 tested v1.8.38 and v1.9.1: the Sentence audio field of new cards stays empty. On v1.9.1 the first one or two cards got their sound, then none did. The code shows one way this happens:
   - The page recorder (`ui/LineAudioScripts.kt`) lets its audio context rest after the video has been paused for a minute, to save battery. It woke up only on the app's next check, up to 1.5 s after the video started playing again.
   - A card sent while its line is paused half way waits for the line, and the app replays that line from 0.75 s before it starts (0.25 s lead + 0.5 s early).
   - After a long pause (looking up words, checking AnkiDroid) the recorder is resting, so it starts recording after the line has begun. `clip()` finds no run that holds the whole line, and the card waits for good. The first cards are sent quickly, before the recorder rests, so they work.
   A second cause showed up when the preview was checked on an emulator with real YouTube: when the page video's source changes in the same element (a pre-roll ad ends, or another video opens in the app), `captureStream()` adds a new audio track. The old track stays `live` but only gives zeros. The recorder kept the old track, because it only re-attached when the element changed or the track ended, so it recorded silence. `audible()` then dropped the clip and the card waited for good. So every card from a video that started with an ad, or from the second video opened, got no sound.
2. lion737 asks to cut the sound right away from the subtitle times. The app can't download YouTube's audio (YouTube doesn't allow it; rejected in the 2026-11-09 spec), so it records the sound while the video plays. Sending mid-line already replays the line; that replay is what failed.
3. lion737 asks to pick which sound plays first, separately for the front and the back. The back template starts with `{{FrontSide}}`, so the back repeats the front's sounds in the front's order, and the order only matters on a side that has both sounds. One picker covers every case.

## Goals

- The recorder wakes the moment the page video starts playing (its `play` and `playing` events, heard on the document while capturing), not on the app's next check.
- A context in any state other than running or closed is resumed; a closed one is replaced on the next check.
- The recorder keeps the video element's captured stream and always listens to its newest live audio track: on the stream's `addtrack` event, on the video's play events and on every check.
- A line replayed for a card's sound starts 1 s before its lead (was 0.5 s), so the recorder has time to wake.
- Flashcards & Anki → **Play first**: Word sound (default, today's cards) or Sentence sound. Shown only while both sounds go on cards and the front isn't Listening (a Listening front always starts with the line).
- With Sentence sound first, a side with both sounds plays the line's sound, then the word's: in the DualSub Replay note type's template (the word's voice moves to just after the line's sound) and in basic note types' fields. Sounds on different sides, or a missing sound, leave the template as it is.
- The AI assistant can set it (`anki_card_sound_first`: word or sentence); setup links and backups carry it; Reset all settings clears it; Settings search finds it.

## Non-goals

- No separate front and back order (see context 3).
- No downloading of YouTube audio.
- No Web or Reading section; no word-by-word meanings (backlog).

## Acceptance criteria

- [x] The recorder listens for the page video's `play` / `playing` events and removes them when stopped (unit test `theRecorderWakesWhenThePageVideoStartsPlaying`).
- [x] A line paused half way, left until the recorder rests, and replayed from 1.75 s before the line with no app check in between gives its clip (device test `aLineReplayedAfterALongPauseStillGivesItsClip`, PR CI).
- [x] The recorder follows the newest audio track (unit test `theRecorderFollowsTheNewestTrackWhenTheVideoSourceChanges`).
- [ ] A new source in the same video element is recorded with sound (device test `aNewSourceInTheSameVideoElementIsStillRecorded`).
- [x] Play first orders the sounds only on a side with both; defaults stay byte for byte the same; the template key changes only when the order does (unit tests in `AnkiCardLayoutTest`, `AnkiConnectNotesTest`).
- [x] Play first shows and stores its choice, and hides with one sound off or a Listening front (Compose test `playFirstShowsOnlyWhileBothSoundsGoOnCards`, PR CI).
- [x] On an emulator with a real YouTube video (build 750, Pixel 7 API 37, AnkiDroid 2.25.3): a card sent mid-line and three cards sent after 1 min 55 s to 2 min 16 s of pause (Home and AnkiDroid in between) all got a 1.8-4.8 s clip with speech (peak about -2 dB). v1.9.1 left the same flow empty. Play first = Sentence played the sentence before the word in AnkiDroid's preview.
- [ ] On an emulator with a real YouTube video: a card from a video that started with an ad, and from a second video, gets its sound.
- [ ] A new card sent to AnkiDroid after a long pause plays the line's sound (owner, preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Cloud |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Cloud |
| Managed-device/emulator | `LineAudioWebViewTest`, `StudyToolsUiTest` pass | PR CI (no KVM in cloud) |
| Live YouTube | The long-pause flow above with the preview APK | Owner's laptop emulator via Remote Control |
| Physical-device/manual | Send cards after long pauses, play them in AnkiDroid; try Play first | Owner's phone |

## Risks / edge cases

- Waking on every play event only resumes an existing context; it adds no work while nothing plays.
- If a phone's WebView refuses to resume the context without a tap, the old behaviour stays (the page's next tap still resumes it); the emulator check covers WebView's rule.

## Release intent

`release:patch`: a bug fix and a small setting inside an existing section.

## Implementation result

As planned. `CardSoundFirst` and `CardSides.sentenceFirst` in `AnkiCardLayout.kt`; `sentenceSoundPlaysFirst` decides whether a side is reordered (template key suffix `+sentencefirst`). The recorder's `rest()` lets the device test put the context to rest without waiting a minute.

## Validation result

Local: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed. Device tests run in PR CI; the live YouTube and phone checks are listed above.
