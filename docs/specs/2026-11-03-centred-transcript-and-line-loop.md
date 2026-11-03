# Centred transcript and endless line loop

## Status

Implemented (owner pick 2026-11-03, "Both in one PR"). Merge and release still need the owner's
go-ahead.

## Context / problem

lovoro asked on [discussion #81](https://github.com/hoangkien1703/dual-sub-replay/discussions/81#discussioncomment-19376779)
for two things:

1. Loop one sentence over and over.
2. A lyrics-style transcript: the playing line stays in the middle of the screen and the list
   scrolls up as each line ends, so the eyes don't move up and down.

Before this change:

- Settings → Study tools → Play mode → Repeat each line played every line 2, 3 or 5 times
  (`STUDY_REPEAT_COUNTS`). Nothing looped a line until the learner moved on.
- The transcript panel (the default view) kept earlier lines on screen: the playing line walked
  down the list, and when it reached the bottom the list jumped it back to the top
  (`shouldPromoteActiveSubtitle`). That jump is the eye movement lovoro describes.

## Goals

- Settings → Reading → Playing line in the transcript: **Moves down** (default, the behaviour so
  far) or **In the middle** (owner 2026-11-03: an option for now, not the default). In the middle,
  the transcript scrolls smoothly so the playing line stays centred, like song lyrics.
- Repeat each line gets a fourth count, **Forever**: the line plays again and again until the
  learner taps another line or seeks.
- The AI assistant can change both (`transcript_follow`, `study_repeat_count` = `forever`).

## Non-goals

- No separate karaoke screen: the transcript panel already shows the timeline with word
  highlighting.
- No extra on-screen loop button (owner prefers settings to buttons, 2026-10-16).
- The overlay on the video is unchanged; it already shows one line in a fixed place.

## User-visible behavior

- In the middle: the line being spoken stays in the middle of the transcript panel. When it ends,
  the list scrolls up (about a third of a second) and the next line takes its place. The first
  lines of a video sit higher, and the last lines lower, because the list cannot scroll past its
  ends. A seek of more than 40 lines jumps without animation.
- While the learner drags the transcript and for 2 s after (the "Now playing" pill's browsing
  time), the transcript does not follow; the next line after that brings it back to the middle.
  Tapping the pill also centres the line.
- Moves down: exactly the old behaviour.
- Forever: with Repeat each line chosen, each line starts again from its beginning at its end, with
  no limit. Tapping another line (in the transcript or overlay) or seeking moves on, and that line
  then loops. Like every play mode it needs the full transcript, not live subtitles.
- Reset all settings returns both settings to their defaults (Moves down, 2 times).

## Technical constraints / invariants

- Play modes only send seek/pause/speed scripts to the one page video (study tools invariant in
  AGENTS.md); Forever is a count, so it reuses the `REPEAT_LINE` path in `StudyPlaybackDriver`.
- New strings exist in all 10 interface languages (`AppLanguageTest`, lint `MissingTranslation`).
- `AiAssistantActionsTest` keeps the AI choices equal to the app's own lists.
- `PageCpuUseTest` budget: centring adds one short scroll animation per line change, the same order
  as the old promote animation.

## Proposed approach / plan

1. `TranscriptFollow` enum (`center`, `page`), preference `transcript_follow`, in
   `DualSubUiState`, `AppViewModel.setTranscriptFollow`, resettable.
2. `SubtitleTimeline`: in centre mode every line change calls `centreRow` (instant on the first
   placement or a far seek, else `animateScrollBy` by `centreScrollDelta`), skipped while the
   learner browses. `rememberLearnerBrowsing` moves up from `JumpBackPill` so both share it.
3. `STUDY_REPEAT_FOREVER = 0` joins `STUDY_REPEAT_COUNTS`; `StudyPlaybackDriver.repeat` never stops
   for it. Chip label "Forever"; AI value `forever`.
4. Strings in 10 languages, unit tests, Compose screenshot of the Reading card.

## Acceptance criteria

- [x] `centreScrollDelta` centres a row smaller than the viewport and aligns the top of a taller row
  (unit test).
- [x] `StudyPlaybackDriver` with Forever repeats a line more than 5 times and moves on after a seek
  the driver did not ask for (unit test).
- [x] `storedStudyRepeatCount(0)` is Forever; unknown counts still fall back to 2 (unit test).
- [x] `storedTranscriptFollow(null)` is Moves down; `center` is kept (unit test).
- [x] AI choices match `TranscriptFollow` and `STUDY_REPEAT_COUNTS` (unit test).
- [ ] On a phone, the playing line stays centred while a video plays (owner's preview check).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Cloud container |
| Android lint/build | `./gradlew formatCheck complexityCheck lintDebug assembleDebug` passes | Cloud container / PR CI |
| Managed-device/emulator | PR CI `managed-device-tests` passes; settings screenshot checked | PR CI |
| Physical-device/manual | Preview APK: centred transcript, Forever loop, Moves down unchanged | Owner's phone |
| Live YouTube | Not run in the container | Owner's phone |
| Performance | `PageCpuUseTest` stays within budget in PR CI | PR CI |

## Risks / edge cases

- A row taller than the panel cannot be centred; its top is aligned instead.
- Translations revealed on pause can make rows taller; the existing `KeepActiveRowOnScreen` still
  keeps the playing row on screen, and the next line centres again.
- Forever with live subtitles does nothing (play modes need the full transcript); the settings note
  already says so.
