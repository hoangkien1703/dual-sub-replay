# Keep captions in the video's spoken language

## Status

Implemented. Local unit tests, format and complexity checks, lint, and the debug and
android-test builds pass. Final-head GitHub Android CI and the live-YouTube check on a phone are
still pending. The owner asked for this fix and a PR, and refined the goal in review. That request
does not authorize merging or publishing.

## Context / problem

Two separate places chose a caption language without knowing what language is spoken.

1. **The app's transcript** (`YouTubeCaptionProvider.selectTrack`) ranked tracks only by the
   preferred-language list and by manual (+20) versus auto-generated.
   - With Source set to **Auto**, the preferred list is empty. The same happens when the
     preferred language has no track on the video.
   - Every creator-uploaded track then tied, and the **first one in YouTube's list** won, for
     example Arabic on an English video.
   - `resolvedSourcePreference` switches back to `auto` whenever the loaded track differs from
     the one requested, so this path is common.
2. **YouTube's own captions in the WebView** keep the last language the viewer used across
   videos. The owner's example: switch from an English video to a Japanese one, and the page
   still shows English captions.
   - Live capture only accepts page captions in the transcript's language
     (`AppViewModel.updateTranscriptPlayback`). The live word highlight therefore loses its sync.
   - In live-fallback mode (no transcript), the wrong page language is translated.
   - `ensureCaptions` returned early whenever captions were already on. When it did turn them on,
     it picked `tracklist[0]`.

Real player responses checked on 2026-09-25:

| Video | Tracks | Audio id | Default caption |
| --- | --- | --- | --- |
| `C3oPjuudXas` (Japanese speech) | … `ja`, `a.ja` … | `en-US.4` | `en` |
| `UJyBgOdh9IA` (Japanese speech) | `en`, `ja` (no auto track) | `ja.4` | `en` |
| `UIBMwq2BgvE`, `RVk1vnqnaxQ` | Arabic listed first | none | `en` |

Findings from this data:

- The auto-generated track's language is right even when the creator labelled the audio wrongly.
- The creator's default caption is usually the English translation, so it is only a last resort.

## Goals

- Always choose the caption language that matches the audio, preferring creator-written captions
  over auto-generated ones. This applies to both the app's transcript and YouTube's on-page
  captions.
- Keep the page's captions on the same track as the transcript, so the live highlight can match
  them.

## Non-goals

- Detecting the language from the audio itself on the device.
- Turning YouTube captions on when the user has them off, except for the existing live-capture
  behavior.
- Overriding a caption language the user picks on the YouTube page after the app has applied its
  choice.

## User-visible behavior

**Before:**

- With Auto, an English video with an uploaded Arabic track could load Arabic.
- After an English video, a Japanese video kept English YouTube captions, and the highlight
  drifted.

**After:**

- The transcript and YouTube's on-page captions both use the spoken-language track. Creator
  captions come first, then auto-generated ones.
- An explicitly chosen source language that exists on the video still wins. The page follows it,
  so the page and the transcript stay the same.
- If the user then changes the page's language by hand, that choice is kept for the video.

## Technical constraints / invariants

- The provider change stays inside `YouTubeCaptionProvider.kt` and uses only data already in the
  player response. There are no new network requests.
- The page script re-verifies the executing origin (`https:` + `*.youtube.com`), like the other
  scripts, and adds no JavaScript bridge. It only calls the player's own
  `getOption`/`setOption('captions', 'track', …)`.
- There is still exactly one WebView.

## Proposed approach / plan

1. **Find the spoken language.** `spokenCaptionLanguage(renderer)` checks, in order:
   - the default audio track's auto-generated track;
   - otherwise any auto-generated track;
   - otherwise the default audio track's `audioTrackId` language;
   - otherwise its `defaultCaptionTrackIndex`, as a last resort;
   - otherwise `null`.
2. **Score tracks.** `selectCaptionTrack(renderer, preferred)` adds:
   - explicit preference: `1000 - 100 * index`;
   - spoken language: `+500`;
   - creator-written track: `+20`.

   With no signal at all, the result is the same as before.
3. **Page sync script.** `webCaptionTrackSyncScript(target)` installs
   `window.__dualSubCaptionTrackV1`:
   - `choose()` picks the target track when there is a `CaptionTrackTarget` for the current
     video, which comes from the loaded transcript via `captionTrackTarget(state)`.
   - Otherwise it applies the same spoken-language rule in JavaScript.
   - `run()` switches the page's track only when captions are on and differ, including
     auto-translated captions. It gives up after five unconfirmed attempts, and never
     overrides a later manual change.
4. **Wiring.**
   - `WEB_PLAYBACK_SNAPSHOT_SCRIPT` calls `run()` on every poll.
   - `ensureCaptions` turns captions on with `choose()`'s track.
   - `SingleYouTubePage` re-installs the script on page load and whenever the target changes.

## Acceptance criteria

- [x] Auto with `[ar manual, en ASR]` selects English, and with an extra `en` manual track
  selects the creator-written one.
- [x] An unavailable preference falls back to the spoken language. An explicit available
  preference still wins.
- [x] The auto-generated `ja` track beats audio mislabelled `en-US` and an English default
  caption.
- [x] Without auto captions, audio id `ja.4` beats the English default caption.
- [x] With no signal at all, the first creator-written track is kept (regression).
- [x] Page script scenarios, run in Node against a fake player:
  - sticky English switches to Japanese creator captions;
  - once matched, the user's own later pick is kept;
  - the transcript target `a.ja` is followed;
  - captions that are off stay off;
  - the audio id decides when there is no auto track;
  - a stale target from another video is ignored;
  - auto-translated captions are replaced.
- [x] An instrumented WebView test (`captionTrackSyncSwitchesStickyLanguageToSpokenLanguage`)
  covers the sticky-language and target cases in a real WebView.
- [ ] On a phone: English video → Japanese video (and the reverse) switches YouTube's captions to
  the audio language, and the highlight follows speech.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `.\gradlew.bat testDebugUnitTest`: all pass | Local Windows, JDK 17 |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` succeed | Local and CI `verify-build` |
| Managed-device/emulator | `BrowseWebViewLifecycleTest` passes, including the new sync test | Local `pixel2Api36` and CI `managed-device-tests` |
| Physical-device/manual | Scenario in the last acceptance criterion | Owner's phone |
| Live YouTube | Same as the physical-device row; the real `setOption` track format is verified only here | Owner |

## Risks / edge cases

- **Real player behavior is unverified offline.** The fixtures model `getOption`/`setOption`, but
  `setOption` with a tracklist entry has only been checked against fakes. If YouTube ignores it,
  the script stops after five attempts and behavior matches today.
- **Wrong spoken-language guesses.** If a video has no auto track and its audio is mislabelled,
  the guess can still be wrong. That is YouTube's metadata and no worse than before.
- **The creator's default caption** is used only when nothing else is known.

## Release intent

`release:patch`: a user-visible bug fix with no new feature. This is the repo default. The
estimated version is v1.0.7 (latest stable release v1.0.6), subject to reservation.

## Implementation result

- `data/YouTubeCaptionProvider.kt`: `spokenCaptionLanguage`, `selectCaptionTrack`, and
  `isGeneratedTrack`. The `audioTrackId` fallback was added after checking the real responses
  above.
- `ui/YouTubeWebScripts.kt`: `CaptionTrackTarget`, `webCaptionTrackSyncScript`, the snapshot
  hook, and `ensureCaptions` using `choose()`.
- `ui/CaptionVisibility.kt`: `captionTrackTarget`.
- `SingleYouTubePage` and `DualSubApp` wiring.
- `config/quality/detekt-baseline.xml`: the existing `SingleYouTubePage` entries were
  re-keyed for the new parameter.

## Validation result

- **Passed locally:** `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest`, with 287 unit tests and 0 failures. The Node page-script scenarios
  passed 8 of 8.
- **Passed on the local managed emulator** (`pixel2Api36DebugAndroidTest`, class
  `BrowseWebViewLifecycleTest`): 7 of 7, including
  `captionTrackSyncSwitchesStickyLanguageToSpokenLanguage`.
- **Pending:** final-head CI (see the PR), and the phone / live-YouTube check by the owner.
