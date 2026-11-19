# Live stream captions

## Status

Implemented (owner pick 2026-11-19: "1. Read YouTube live captions"). Merge and release stay with the owner.

## Context / problem

The owner opened a YouTube live stream (signed in, Premium) and the panel said "No subtitles on YouTube" with
"Make subtitles with AI". Research on 2026-11-19 (cloud browser, desktop and m.youtube.com):

- Live streams never list caption tracks in the player response: 0 of 14 live news streams did, including
  ones whose player shows captions. Signing the app's own download in would not help, because the signed-in
  page player also lists 0 tracks.
- Some live streams send TV-style captions inside the video. On ABC News Live the player drew them in
  `.caption-window.ytp-caption-window-rollup[lang="en"]` > `.ytp-caption-segment`, in capitals, two or three lines
  rolling up word by word, with no `/api/timedtext` request and the CC button labelled "unavailable".
  CBS News 24/7 and Al Jazeera showed none.
- The live subtitles fallback already reads that text, but `liveTranslationKey` needs a language, which came
  only from the active caption track (none here), so nothing showed; and the no-captions card hid it anyway.
- Gemini's `generateContent` with a live YouTube link reads nothing (29 prompt tokens, "unable to access");
  the same call on a normal video works (2,071 tokens). "Make subtitles with AI" cannot work on a live stream.
- The owner's own stream had no captions at all: its subtitle-like line is burned into the picture by the
  streamer (YouTube's progress bar is drawn over it).

## Goals

- Show and translate a live stream's own captions when its player draws them.
- On a live stream with no captions, say so and don't offer AI subtitles that can't work.

## Non-goals

- AI subtitles for live streams (audio pieces to Gemini) and reading text burned into the picture: offered as
  options 2 and 3, not picked.
- Changing the app's own caption download (no cookies leave the page).

## User-visible behavior

- Live stream whose player draws captions: the live subtitles panel shows one finished sentence at a time in
  sentence case (`>>` speaker marks removed) with its translation, instead of the "No subtitles" card.
- Live stream without captions: "No captions on this live stream" with a short explanation (captions show up
  here if the stream starts sending them; the replay usually gets captions after it ends). No AI button.
- Other videos: unchanged.

## Technical constraints / invariants

- Page scripts keep re-verifying the origin; the capture script only reads rendered caption text and the
  caption window's `lang` and roll-up class ([captions](../project/tech-stack.md#captions)).
- AI features stay on a tap; nothing new spends a key.

## Proposed approach / plan

1. `webLiveCaptionConfigurationScript`: when no caption track is active, take the language from
   `.caption-window[lang]`; report `rollUp` when `.ytp-caption-window-rollup` is on the page.
   `WEB_PLAYBACK_SNAPSHOT_SCRIPT` passes `rollUp`; `parseWebPlaybackSnapshot` reads it into `LiveCaptionSample`.
2. `ui/LiveRollUpCaptions.kt`: `RollUpCaptionAssembler` joins roll-up windows into running text (word overlap,
   the last, maybe half-typed word joins on the next window) and hands on the latest finished sentence: at
   `. ? !`, before a `>>` speaker mark, after a 2.5 s pause, or after 30 words. `rollUpSentenceText` removes
   speaker marks and turns mostly-capital text into sentence case (English `i` becomes `I`). One translation
   request per sentence instead of one per word. Other samples pass through unchanged.
3. `WEB_PAGE_CAPTION_TRACKS_SCRIPT` sends `videoDetails.isLive`; `PageTranscriptSource.isLive(videoId)`;
   `DualSubUiState.liveStream`, refreshed with each playback snapshot while live subtitles stand in
   (`loadVideo` is at detekt's length limit).
4. `LiveSubtitlePanel` shows the no-captions card only while there is no live text; `NoCaptionsSection` shows
   `LiveStreamNoCaptionsCard` (no actions) for a live stream. Strings in all 9 interface languages.

## Acceptance criteria

- [x] A roll-up sample with no active track but a caption window `lang` gives a translation key (unit test).
- [x] Rolling ABC-style windows give whole sentences once, in sentence case, without repeats (unit tests).
- [x] Non-roll-up samples pass through unchanged (unit test), so normal videos' live fallback is the same.
- [x] The page reports live streams; the no-captions card for a live stream has no AI button; live text
      replaces the card (unit + Compose tests).
- [ ] On a phone, ABC News Live (or another stream with captions) shows translated sentences.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `LiveRollUpCaptionsTest`, `PageTranscriptSourceTest`, `PlaybackArchitectureTest`, `LiveSubtitleRecoveryTest` pass | Cloud container |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| Managed-device/emulator | `NoCaptionsCardUiTest` (new live cases save `no-captions-live-stream`, `live-stream-captions`) | PR CI |
| Physical-device/manual | Open ABC News Live and the owner's GTA stream | Owner's phone, preview APK |
| Live YouTube | DOM shape checked in a cloud browser 2026-11-19 (above) | Done for research only |

## Risks / edge cases

- Names lose their capitals in sentence case ("abc news"); translation still reads them.
- A sentence shows after it ends (about one sentence behind the speaker), which is the price of whole-sentence
  translations; long sentences show after 30 words.
- YouTube may rename the caption window classes; then live text falls back to today's behavior (no language,
  nothing shown) and the live card still shows.

## Release intent

`release:patch` (changes the APK; patch releases only, owner rule 2026-11-13).

## Implementation result

As planned, except `liveStream` is set in `updateLiveFallbackPlayback` instead of beside `noCaptions`.

## Validation result

See the PR for CI. Unit tests listed above passed in the cloud container on 2026-11-19. Not yet tested on a phone.
