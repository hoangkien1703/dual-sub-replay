# Say "no captions" on the video, not "waiting for captions"

## Status

Implemented. The owner said yes in the project thread on 2026-11-24 to fixing this after a Reddit report. This status doesn't authorize a merge or a release.

## Context / problem

A Reddit user opened a video whose Chinese text is burned into the picture and that has **no YouTube captions at all**. The box over the video said "Live subtitles · Waiting for YouTube captions and their language. Play the video with captions enabled." They kept pressing CC, but there was nothing to turn on.

The app already has a no-captions card ([AI subtitles spec](2026-11-08-ai-subtitles-for-videos-without-captions.md)), but two gaps hid it:

1. **The box over the video ignored it.** `NoCaptionsSection` shows only in the transcript panel (`LiveSubtitlePanel`). In "on the video" view, in landscape and in fullscreen, `learningOverlayContent` always showed the live-subtitles status line and *Retry full transcript*.
2. **A late "no tracks" finding was never passed on.** `noCaptions` is decided once, when the track fails to load. If the page's player hadn't finished loading by then (for example, a paused video that hasn't started yet), the page problem was `NO_ANSWER` or `NO_PLAYER`. The app's own download often fails for other reasons, such as the bot check, so `noCaptions` stayed false. The page keeps watching after that point, and it does find the empty track list later, but it said so to no one.

"Live subtitles" here means captions read live from the page's player, not a live stream. It is wrong for a video with nothing to read.

## Goals

- When YouTube lists no caption track for the video, the box over the video says so and offers AI subtitles the same way the panel card does.
- A "no tracks" finding that arrives after the wait turns the waiting message into the no-captions message.
- Keep the "Play the video with captions enabled" hint only while captions may still come.

## Non-goals

- No change to how tracks are found, nor to AI subtitles themselves.
- No new setting. Nothing turns AI on by itself (AGENTS.md default settings).

## User-visible behavior

| Situation | Before | After |
| --- | --- | --- |
| No captions, "on the video" view or fullscreen, no Gemini key | "Live subtitles · Waiting for YouTube captions… Play the video with captions enabled." + Retry full transcript | "This video has no captions, not even automatic ones. AI can listen to it and write them." + **Add a free Gemini key** (opens Settings → AI assistant) |
| Same, with a Gemini key | same as above | Same line + **Make subtitles with AI**, then "Making subtitles with AI… 5:00 of 9:24" |
| AI subtitles failed | n/a | The problem line (for example the daily limit) + Make subtitles with AI |
| Live stream with no captions | "Live subtitles · Waiting…" | "No captions on this live stream", no button |
| Bilibili without subtitles | "Live subtitles · <Bilibili's message>" + Retry | Bilibili's message + Retry |
| Captions exist, none loaded yet | unchanged | unchanged: "Live subtitles · Waiting for YouTube captions… Play the video with captions enabled." + Retry |
| Page finds no tracks only after the wait | Waiting message forever | Turns into the no-captions card (panel) or the line above (on the video) |

All texts reuse existing strings that are already translated into every interface language. There are no new strings.

## Technical constraints / invariants

- Captions: the page still only reads its own player's details. Nothing new is fetched or sent (`PageTranscriptSource` only reports what it already saw).
- AI: the button only does what the panel card does on a tap (`makeAiSubtitles`, or open Settings → AI assistant).
- Detekt baseline: `LearningSubtitleOverlay` keeps its signature. The button kind comes in `LearningOverlayContent.noCaptionsAction`, and the click reuses `onRetryTranscript`.

## Proposed approach / plan

1. `PageTranscriptSource`: when a request that is already only watching (`late`) reaches the no-tracks grace, it records `NO_TRACKS` and announces the video on `lateNoCaptions`. `watchLater` now decides its reason under the same lock, so the two can't cross.
2. `AppViewModel` collects `lateNoCaptions`. If the video is still on live fallback with no player captions, it sets `noCaptions` and refreshes the diagnostic lines.
3. `CaptionPresentation`: `showsNoCaptions()`, `noCaptionsOverlayAction()` and `showsOverlayRetry()` are pure functions. The overlay content shows the no-captions line, the AI progress or the AI problem instead of "Live subtitles · …".
4. `LearningPlayerRoot`: picks the button from the assistant's Gemini key state. It either makes the subtitles or bumps a new `externalAiSettingsRequestId` into `DualSubApp`.

## Acceptance criteria

- [x] With `noCaptions` on a YouTube video, the overlay shows no "Live subtitles" or "Waiting for YouTube captions" text and no Retry, but does show the no-captions line and the right button (`NoCaptionsOverlayTest`, `NoCaptionsCardUiTest.theBoxOverTheVideo…`).
- [x] Without `noCaptions`, the overlay is unchanged (`whileCaptionsMayStillComeTheWaitingHintAndRetryStay`, `LiveSubtitleRecoveryTest`).
- [x] A no-tracks finding after the wait is announced once and stored as `NO_TRACKS`. A finding within the wait isn't announced again (`PageTranscriptSourceTest`).
- [ ] Screenshots from the PR's device tests look right next to the existing no-captions card.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `PageTranscriptSourceTest`, `NoCaptionsOverlayTest`, `LiveSubtitleRecoveryTest`, `LearningPlayerRootTest`, `PlaybackArchitectureTest` pass | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug compileDebugAndroidTestKotlin` pass | Cloud container, then PR CI |
| Managed-device/emulator | `NoCaptionsCardUiTest` saves `overlay-no-captions-*.png` | PR CI `managed-device-tests` |
| Physical-device/manual | Open a video without captions in "on the video" view and fullscreen, before and after pressing play | Owner's phone, preview APK |
| Live YouTube | Same as above | Owner's phone |

## Risks / edge cases

- A video whose player lists its tracks only after more than the 8-second grace could briefly show "no captions". This was already true for the panel card. A late transcript still reloads the video (`lateTranscripts`).
- Overlay status now allows 3 lines instead of 2, so a long status line takes one more line on the video.

## Release intent

`release:patch`: the APK changes. The owner's standing rule is patch releases only.

## Implementation result

As planned. The box's button sits under the status line for the no-captions case and keeps its old place above the status line for Retry.

## Validation result

- 2026-11-24, cloud container: the unit tests above pass (24 + 6 + 7 + 15 + 25 + 5). `formatCheck`, `complexityCheck` and `lintDebug` pass, and the device tests compile.
- Device screenshots and the full suites: see the PR's CI.
- Not run: physical device and live YouTube.
