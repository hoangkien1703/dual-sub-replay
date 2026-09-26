# Tapping a caption row seeks to its spoken words

## Status

Implemented. Local checks pass. Final-head CI and the owner's phone check are pending. The owner
asked for a fix and a PR. That request does not authorize merging or publishing.

## Context / problem

The owner reported that tapping a sentence sometimes plays audio that does not match its words,
as if the timestamp were wrong. Their screenshot shows English auto-generated captions split into
short rows ("How do we give it a realistic enough scenario so" / "it doesn't think it's being
tested?").

Tapping a row seeks to `row.startMs` (`DualSubApp.kt`, `webController.replayFrom`). The first row
of a sentence starts at a real cue start. Every later row starts at the time of its first word, so
it is only as good as the word timings.

Auto-caption cues stay on screen after the next cue starts: their display end overlaps the next
line by a second or more. `SubtitleMerger.merge` estimated the words of a cue without word offsets
(legacy XML, srv3 without `<s t>`, or json3 events with no `tOffsetMs`) over the cue's **display**
time. Two things went wrong:

1. The cue's words were spread up to its display end, so later words started seconds late.
2. Those late words overlapped the next cue's words. Sorted by start, the two cues interleaved,
   the text alignment check failed, and the whole merged line fell back to one estimate over the
   display range, which shifted every row start in it.

An offline reproduction with eight overlapping untimed cues put row starts up to 2.7 s after the
first spoken word. Cues with real word offsets were already correct, because
`CaptionDocumentParser.speechEndMs` trims json3 words at the next cue's start.

## Goals

- A row that starts inside an untimed auto-caption cue seeks near its first spoken word.
- Real word timings keep their start anchors.

## Non-goals

- No change to the seek script, row splitting, grouping, translation or highlight logic.
- No pre-roll before the seek point.
- Estimates stay estimates: an untimed cue still has no per-word truth.

## User-visible behavior

- **Before:** on captions without word timing, tapping a row in the middle of a sentence could
  start playback seconds late, past the words shown.
- **After:** playback starts at or near the row's first word. The karaoke underline on those rows
  follows the speech more closely for the same reason.

## Technical constraints / invariants

- Seeking still goes through the single WebView bridge (`webReplayScript`); unchanged.
- Unit tests stay plain JUnit4 and call `internal` functions directly.

## Proposed approach / plan

1. `SubtitleMerger.cueSpeechEnds(ordered)` gives each cue its speech end: the next later cue's
   start when that falls inside the cue, else its display end (reusing `speechEndMs`). Linear.
2. `preparedCueWords` estimates untimed cues over `[start, speechEnd)`. Timed words must start
   before the speech end; their ends are trimmed to it, and starts are never moved.
3. The whole-line fallback in `timedOrEstimatedWords` also estimates up to the line's speech end.
   Segment `startMs`/`endMs` stay as before, so grouping and display are unchanged.

## Acceptance criteria

- [x] Untimed overlapping cues get words in text order, each starting and ending inside its own
  cue's speech span (`SubtitleMergerTest.untimedOverlappingCuesEstimateWordsInsideTheirOwnSpeech`).
- [x] The short row "it doesn't think it's being tested?" starts inside the cue where "it" is
  spoken (`rowSplitInsideAnUntimedCueSeeksNearTheSpokenWord`; 14.18 s before, inside 12–14 s after).
- [x] Timed words keep their starts; only an end that runs into the next cue is trimmed
  (`timedWordsKeepTheirStartsAndEndAtTheNextCue`).
- [x] Cues sharing a start time end where the next later cue begins
  (`cuesSharingAStartEndWhereTheNextLaterCueBegins`).
- [ ] Final-head CI `verify-build` and `managed-device-tests` pass.
- [ ] Owner phone check: on an auto-captioned video, tapping rows in the middle of sentences
  starts at the row's words.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including the new `SubtitleMergerTest` cases | Local + CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local + CI |
| Managed-device/emulator | Existing `pixel2Api36DebugAndroidTest` suite | CI |
| Physical-device / live YouTube | Last acceptance criterion | Owner's phone |

## Risks / edge cases

- A timed srv3 word that starts after the next cue begins now makes that cue fall back to an
  estimate inside its speech span. YouTube's json3 words are already trimmed there, so this only
  affects malformed data.

## Release intent

`release:patch`: a user-visible bug fix with no new setting (project default).

## Implementation result

Implemented as planned.

## Validation result

- Local (Linux, JDK 21, Android SDK 36): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug assembleDebugAndroidTest` passed, with 299 unit tests and 0 failures.
- With the old `SubtitleMerger` put back, all four new tests fail.
- Offline reproduction with overlapping untimed json3 lines: the largest row start error dropped
  from 2.7 s to about 0.3 s. Timed lines produced identical rows before and after.
- Managed-device tests: not run locally. CI runs them.
- Physical phone / live YouTube: not run (YouTube blocks caption requests from the build server).
  Pending owner check.
