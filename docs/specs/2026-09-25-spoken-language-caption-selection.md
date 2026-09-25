# Choose the caption track in the video's spoken language

## Status

Implemented. Local unit tests, format and complexity checks, lint, and the debug build all
pass. Final-head GitHub Android CI and the live-YouTube check on a phone are still pending. The
owner asked for this fix and a PR. That request does not authorize merging or publishing.

## Context / problem

- The owner reports that some English-speaking videos load Arabic captions, which breaks the
  spoken-word highlight: it times Arabic text against English speech.
- `YouTubeCaptionProvider.selectTrack` ranked tracks only by the preferred-language list and by
  manual (+20) versus auto-generated.
- With Source set to **Auto**, `preferredCaptionLanguages("auto")` is empty. The same happens
  when the preferred language has no track on the video. In both cases every creator-uploaded
  track tied, and the **first one in YouTube's list** won, even when it was a translation.
- `resolvedSourcePreference` switches the preference back to `auto` whenever the loaded track
  differs from the one requested, so this path is common.

## Goals

- With no usable explicit choice, load the track in the language actually spoken.
- The corrected language then flows into translation and the word highlight unchanged.

## Non-goals

- Detecting the language from the audio or the caption text on the device.
- Changing the source-language picker, or what happens after the user explicitly picks a
  language.

## User-visible behavior

- Before: with Auto, an English video with an uploaded Arabic track could show Arabic captions
  with a broken highlight.
- After: the English captions load. Creator-written English captions are preferred over
  auto-generated ones. If the user explicitly picks a language that exists on the video, that
  track is still used.

## Technical constraints / invariants

- Selection stays inside the `YouTubeCaptionProvider.kt` boundary and uses only data already in
  the player response (`playerCaptionsTracklistRenderer`). There are no new network requests.
- Logic lives in small `internal` top-level functions so plain JUnit can test it.

## Proposed approach / plan

1. `spokenCaptionLanguage(renderer)` finds the spoken language from these signals, in order:
   - the auto-generated (ASR) track of the default audio track (`audioTracks[defaultAudioTrackIndex].captionTrackIndices`);
   - otherwise any ASR track;
   - otherwise the default audio track's `defaultCaptionTrackIndex`;
   - otherwise `null`.

   YouTube only generates ASR captions in the audio's language, so that is the strongest signal.
2. `selectCaptionTrack(renderer, preferred)` scores each track:
   - explicit preference: `1000 - 100 * index`;
   - spoken language: `+500`;
   - creator-written track: `+20`.

   With no signal at all, the result is the same as before.
3. `isGeneratedTrack` shares the ASR check with the `isGenerated` result flag.

## Acceptance criteria

- [x] Auto with `[ar manual, en ASR]` selects English.
- [x] Auto with `[ar manual, en manual, en ASR]` selects the creator-written English track.
- [x] An unavailable preference (`vi`) with `[ar manual, en ASR]` selects English.
- [x] An explicit `ar` preference still selects Arabic.
- [x] On multi-audio videos, the default audio track's ASR language wins.
- [x] With no ASR track, the default audio track's `defaultCaptionTrackIndex` decides.
- [x] With no language signal, the first creator-written track is still chosen (regression).
- [ ] On a phone, a live English video with an Arabic upload loads English captions with a
  correct highlight.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `.\gradlew.bat testDebugUnitTest`: all pass, including 7 new `YouTubeCaptionProviderTest` cases | Local Windows, JDK 17 |
| Android lint/build | `.\gradlew.bat formatCheck complexityCheck lintDebug assembleDebug` succeed | Local and CI `verify-build` |
| Managed-device/emulator | CI `managed-device-tests` succeed; no UI change is expected | CI |
| Physical-device/manual | With Source set to Auto, open an English video that has an Arabic uploaded track: English captions load and the highlight follows speech | Owner's phone |
| Live YouTube | Same as the physical-device row | Owner |

## Risks / edge cases

- Videos with a wrong ASR language: YouTube's own mistake then carries over. No worse than
  today's first-track choice.
- `defaultCaptionTrackIndex` may reflect the creator's default rather than the audio. It is
  used only when there is no ASR track.

## Release intent

`release:patch`: a user-visible bug fix with no new feature. This is the repo default. The
estimated version is v1.0.7 (latest stable release v1.0.6), subject to reservation.

## Implementation result

Implemented as planned in `data/YouTubeCaptionProvider.kt`. The private `selectTrack` was
replaced by `selectCaptionTrack`. There are no ViewModel or UI changes.

## Validation result

- Passed locally: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug`.
  `YouTubeCaptionProviderTest` ran 16 tests, 0 failures.
- Pending: final-head CI (see the PR), and the physical-device / live-YouTube check by the owner.
