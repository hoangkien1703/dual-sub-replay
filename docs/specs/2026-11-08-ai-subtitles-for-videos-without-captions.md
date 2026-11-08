# AI subtitles for videos without captions

## Status

Implemented. Owner picked "Gemini AI subtitles + clear message" on 2026-11-08. Merge and release need the owner's OK.

## Context / problem

A Reddit user (2026-11-08, about an hour a night in the app) watched "La casa de las flores" clips that have no captions at all, not even YouTube's automatic ones. The app then shows the live-subtitles panel with "Waiting for YouTube captions…" and an English diagnostic line ("App download: This video does not provide a public caption track."). Nothing tells the person what they can do.

Research in the project thread (2026-11-08):

- Gemini's API takes a public YouTube link directly (`file_data.file_uri`, preview, free; free keys may send up to 8 hours of YouTube video a day; public videos only). See Google's [video understanding](https://ai.google.dev/gemini-api/docs/generate-content/video-understanding) page. It listens to the video's sound, so it needs no captions.
- Live test with the owner's key: the clip `yjsAuMiLc_A` (2:50, no caption tracks) gave 63–70 Spanish lines in 9–21 s. On a Spanish video with YouTube captions (`rqiOwOmYO80`), Gemini's line starts were a median 0.1–0.2 s from YouTube's own word timing, and every matched line was within 1 s. `gemini-flash-lite-latest` answered a 3-minute part in 6–9 s; `gemini-flash-latest` took over 30 s for the same part.
- Found while testing: a part that starts after the end of the video answers 400; a missing or private video answers 403 "The caller does not have permission"; with a clip offset, the model sometimes counts times from the start of the part instead of the video; asked for times as numbers of seconds, `gemini-2.5-flash` wrote minutes as decimals, so times are asked as `MM:SS.mmm` text.
- On-device speech-to-text was rejected for now: 100–240 MB models, native libraries that grow the APK, and it could only hear the video while it plays.

## Goals

- A video with no caption track says so plainly, in the interface language, and offers one button: **Make subtitles with AI**.
- With a Gemini key, that button makes subtitles for the whole video. The first part shows within seconds; the rest follows by itself.
- Without a Gemini key, the button opens More settings → AI assistant, where a free key is added.
- AI subtitles then work like any caption track: transcript, replay, translation, word cards, saved words.
- Made subtitles are kept on the phone, so reopening the video asks Gemini nothing.

## Non-goals

- No on-device speech-to-text and no audio capture.
- No automatic start without a tap, and no setting for it (possible later).
- No other AI service: only Gemini takes a YouTube link.
- No AI subtitles for videos that have captions (YouTube's own are better and free).

## User-visible behavior

- **No captions:** the live panel's line becomes "This video has no subtitles on YouTube, not even automatic ones. AI can listen to it and write them." with **Make subtitles with AI** and a short note "Free with a Gemini key. Uses Google's servers." The old diagnostic lines stay below, smaller, for reports.
- **No Gemini key:** the note says "Needs a free Gemini key. Add one in AI assistant settings." and the button reads **Add a free Gemini key**, opening More settings → AI assistant.
- **Making:** a progress bar and "Making subtitles with AI… 3:00 of 9:24" (or "3:00 done" when the length is unknown). The first part starts where the video is playing. When it arrives, the transcript loads; the later parts load once all are done.
- **Loaded:** the source label reads "Spanish (AI subtitles)". Everything else works as with YouTube's captions.
- **Problems** (shown in place of the progress, with **Try again**): the video is private, age-restricted or not available to Gemini; every Gemini key reached its limit ("Gemini's free limit is used up for now. Try again later."); Gemini is busy; no connection; Gemini found no speech.
- Leaving the video stops the work. Opening it again loads the parts already made and carries on with the rest by itself.

## Technical constraints / invariants

- Uses the AI assistant's saved Gemini keys only (`AiKeyStore`, `AiProvider.GEMINI`), whichever service the assistant uses now; never a project key (AGENTS.md AI assistant invariant). Keys take turns through `AiKeyPacer.Shared`, so subtitle translation and this share the free limits politely.
- Requests go only to `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` with `x-goog-api-key`. Only the YouTube watch address of the video is sent; no audio, captions or personal data.
- `CaptionProvider` stays the boundary for YouTube's captions; AI subtitles are read from their own store before it is asked.
- Made subtitles live in `cacheDir/ai-subtitles/` (30 newest videos), so Android may clear them, and they are never in backups.
- AI tracks are not "generated" YouTube tracks, so the app does not try to read live YouTube captions or word timing for them.
- User-visible text in `strings_status.xml` for all 9 interface languages.

## Proposed approach / plan

- `data/AiSubtitles.kt`: request body, reply parsing (`MM:SS.mmm`, `HH:MM:SS.mmm`, `MM:SS:mmm`, plain seconds; part-relative times shifted; empty, reversed and out-of-part lines dropped), the order of parts, merging parts, and `AiSubtitleStore` (JSON per video).
- `assistant/AiVideoSubtitles.kt`: `AiVideoSubtitleMaker` asks each part with each key in turn and the models `gemini-flash-lite-latest`, then `gemini-2.5-flash`, then `gemini-flash-latest` (next model on 404 or busy). 400 for a part starting after 0 means the video ended. 403 without "API key" means the video is not open to Gemini.
- `ui/AppViewModel.kt`: `noCaptions` on a `NoPublicCaptionsException`; `makeAiSubtitles()`; `fetchCaptionTrack` reads the store first; a loaded unfinished track resumes the rest.
- `ui/LiveSubtitleRecovery.kt`: the no-captions block; `ui/DualSubApp.kt`: the "(AI subtitles)" label and the two actions through composition locals.

## Acceptance criteria

- [x] A video whose lookup ends in "no public caption track", or whose fully loaded page player lists no track, sets `noCaptions`; other failures do not.
- [x] Reply parsing turns `00:21.464`, `1:02:03.5`, `06:00:237` and `12.5` into the right milliseconds, shifts part-relative times, and drops empty or impossible lines.
- [x] Parts start at the playing position's part and wrap around; a known length stops them at the end.
- [x] A rate-limited key hands the part to the next key; a 404 or busy model hands it to the next model; 403 "caller does not have permission" is reported as an unavailable video, not a bad key.
- [x] The store keeps finished and unfinished tracks per video, ignores bad files and keeps at most 30.
- [ ] Owner phone test: a video without captions shows the message, makes subtitles, and translation and replay work on them.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` incl. `AiSubtitlesTest`, `AiVideoSubtitlesTest` | Cloud container |
| Static checks | `./gradlew formatCheck complexityCheck lintDebug assembleDebug` | Cloud container |
| UI | `LiveSubtitlePanelNoCaptionsTest` screenshots (no key, key, making, problem) on the CI device | PR CI |
| Live API | Clip `yjsAuMiLc_A` and `rqiOwOmYO80` parts with the owner's key (results above) | Cloud container, 2026-11-08 |
| Device | Owner opens a captionless video on the preview build | Owner's phone |

## Release intent

Patch (default for an APK change). The owner may pick minor for a new feature.

## Results

- Implemented as planned. Deviation: the progress shows only before anything shows; after the first part loads, later parts come in quietly (a part that fails for a passing reason — limit, busy, connection — is asked again after 30 s, 60 s and 120 s; reopening the video carries on after that).
- Live run of the app's own Kotlin request and parser (cloud container, owner's key, 2026-11-08, test not committed): `yjsAuMiLc_A` 0–5:00 gave 72 Spanish lines in 10.1 s; `rqiOwOmYO80` 5:00–10:00 gave 33 lines in 9.6 s with video times; `yjsAuMiLc_A` from 5:00 answered past the end in 0.4 s; `aaaaaaaaaaa` gave "video unavailable".
- Local checks: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed (1,175 unit tests including 22 new, 0 failures).
- Owner phone test 1 (preview build691, music video `UTOZWO1rGzE`): the card did not show. The page player listed 0 tracks, but the app's own download failed with "Web: Player returned UNPLAYABLE: Video unavailable" instead of "no public caption track". Fix: `meansNoCaptionsAtAll` also counts the page's `NO_TRACKS` (set only after the page player finished loading and listed nothing for 8 s). Gemini answered the same video's 0–5:00 part with 29 Chinese lines in 9.4 s.
- Not run here: device tests (no /dev/kvm in the cloud container; PR CI runs `NoCaptionsCardUiTest`), the owner's phone test.
