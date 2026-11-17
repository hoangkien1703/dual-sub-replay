# Song lyrics and AI transcript

## Status

Implemented. Owner picks on 2026-11-17: the AI transcript choice and song card ("create this PR now so I can test it"), free synced lyrics before Gemini and a larger saved-transcript store ("ok try 1 and 2"), LRCLIB after a comparison of sources, and 600 saved transcripts. Merge and release need the owner's OK.

## Context / problem

On a lyric video (owner's screenshots, 2026-11-17), YouTube's auto-generated English captions came out as tiny, wrong pieces: "Another day", "Everybody", "In games", "Ain't got nothing", "Myself", while the lyrics on screen read "I really got 'em thinking this was all just fun and games". Their translation is useless.

AI subtitles ([spec](2026-11-08-ai-subtitles-for-videos-without-captions.md)) already let Gemini listen to a video and write subtitles, but only for videos with no captions at all. Gemini spends the user's free quota and sometimes refuses well-known lyrics, so free timed lyrics should come first for songs.

Research (2026-11-17, project thread):

- **LRCLIB** (`lrclib.net`, free, open, no key, timed LRC lyrics): live search found timed lyrics for 20 of 22 songs across English, Vietnamese, Chinese, Japanese, Korean, Spanish, French, German and Turkish. It misses small artists and Vietnamese titles typed without accents. Picked.
- Musixmatch (free plan: no timed lyrics, 30% of each song), YouTube Music's hidden lyrics API (terms risk, breaks often), NetEase/QQ (unofficial, often blocked outside China; a possible later Chinese-only source), Genius (no timing, no official lyrics access) and on-device Whisper (75–240 MB, weak on singing) were rejected.
- Gemini: `gemini-2.5-flash` wrote 56–58 lyric lines for `dQw4w9WgXcQ` 0–5:00 in 16–24 s, first line at 0:18.4–0:18.6; LRCLIB has the same line at 0:18.90.

## Goals

- **Song card** above the transcript, for everyone: "This looks like a song with auto-made captions, which often get the lyrics wrong." with **Get real lyrics** and an X. Shown when the video looks like a song, the loaded track is auto-generated, no human-made track exists in that language, and the card was not closed for this video.
- **Original language → AI transcript (Gemini)** at the end of the list for any open video.
- Both: a saved transcript loads at once; for a song, LRCLIB first; Gemini only when no lyrics fit; without a Gemini key, "No synced lyrics match this video. Add a free Gemini key…" with **Add a free Gemini key**. Picking AI transcript for a non-song without a key opens Settings → AI assistant.
- YouTube's own tracks stay in the list while the transcript shows, so the person can switch back.
- Saved transcripts: the 600 most recent videos (was 30).

## Non-goals

- Nothing is sent to LRCLIB or Gemini until the person taps (AGENTS.md default settings rule). No new setting: the X hides the card for that video.
- No other lyrics source yet.

## User-visible behavior

- Tap: "Looking for synced lyrics…" above YouTube's captions, then the lyrics replace them and the header reads "English (song lyrics)". Without a match: Gemini's "Making subtitles with AI…" progress, then "English (AI subtitles)".
- Problems (limit, busy, refused, …) show in the same place with **Try again** and an X.
- Picking one of YouTube's tracks goes back to it for this video. Opening the video again later shows the saved transcript (Auto prefers a transcript the person made).

## Technical constraints / invariants

- LRCLIB: `GET https://lrclib.net/api/search?q=<song>` with an app User-Agent, 10 s timeout, 2 MiB cap, no redirects. The query is the title without brackets, "lyrics"/"official video"-type words and "feat." parts, plus the artist of a "- Topic" or VEVO channel. A result is taken only with timed lyrics, a length within 3 s of the video's, and a word of its name in the title; the closest length wins. No match is better than the wrong song.
- Lyrics are stored like AI subtitles (`AiSubtitleTrack`, `lyrics = true`, one complete part, the loaded track's language) in `cacheDir/ai-subtitles/`, 600 newest (about 3–12 MB at most).
- Gemini path unchanged (keys, models, pacing). The prompt now asks for "everything spoken or sung" and leaves out only "music without words".
- `AiSubtitleTrack.youtubeTracks` keeps YouTube's track list (optional JSON field; older files read as none).
- "Looks like a song": the page player's details of the open video (`microformat.playerMicroformatRenderer.category == "Music"` or `videoDetails.musicVideoType`, read by `WEB_VIDEO_STATE_SCRIPT` only when `videoDetails.videoId` matches the address), or title words (lyrics, official video/audio, MV, karaoke, cover, 歌詞/歌词/가사, lời bài hát, letra, paroles, testo), or channel ("- Topic", VEVO, Lyrics).
- The AI assistant's Original language setting cannot pick the AI transcript (not one of the video's tracks).

## Plan

- `data/SyncedLyrics.kt` (new): query, URL, LRC parsing, picking, `SyncedLyricsSource`.
- `data/AiSubtitles.kt`: `youtubeTracks`, `lyrics`, 600 entries, prompt. `data/SubtitleModels.kt`: `CaptionTrackResult.lyrics`.
- `ui/AppViewModel.kt`: `fetchCaptionTrack` skips the store when a YouTube track is picked; `useAiTranscript(geminiReady)`; `findSongLyrics`; `closeAiTranscriptSuggestion()`; the no-captions button tries lyrics first for songs.
- `ui/AiTranscriptSuggestion.kt` (new): song check, card rule, banner. `ui/DualSubApp.kt`, `ui/DualSubUiState.kt`, `ui/SubtitleSettingsDialog.kt`, `ui/NoCaptionsCard.kt`, `ui/AiSubtitleRunner.kt`, `playback/WebVideoPlayback.kt`.

## Acceptance criteria

- [x] Search query, LRC parsing, length and name matching (`SyncedLyricsTest`).
- [x] The list offers AI transcript last, shows it selected while loaded, and the assistant cannot pick it (`SourcePreferenceTest`).
- [x] YouTube's tracks round-trip with a saved transcript and are listed while it shows (`AiSubtitlesTest`, `AiSubtitleRunnerTest`).
- [x] Song detection; the card's rule, also without a key (`AiTranscriptSuggestionTest`).
- [x] The page's Music flag is read (`WebVideoPlaybackTest`).
- [ ] Card, looking, making, problem and no-key screenshots (`AiTranscriptBannerUiTest`, PR CI).
- [ ] Owner phone test on the lyric video from the screenshots and a popular song.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` | Cloud container |
| Static checks | `./gradlew formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| UI | `AiTranscriptBannerUiTest` screenshots | PR CI device |
| Live API | LRCLIB search on 22 songs; Gemini on `dQw4w9WgXcQ` (results above) | Cloud container, 2026-11-17 |
| Device | Owner opens a lyric video on the preview build | Owner's phone |

## Release intent

Patch (owner's standing rule: patch releases only).

## Results

- Implemented as planned.
- Not run here: device tests (no /dev/kvm; PR CI runs them) and the owner's phone test. Whether YouTube's mobile page includes `microformat` in `getPlayerResponse()` is unverified; title and channel words cover lyric videos either way.

## Follow-up: compilations (owner pick 2026-11-17, "try that after this PR")

A long music video made of many songs (2 hours or more) never matches one song's length, so before this it went straight to Gemini (24 parts for 2 hours, about a quarter of a free key's daily video allowance).

- **Songs from the description**: lines with one timestamp and a name ("00:00 1. Ed Sheeran - Perfect", "[07:25] Shape of You | Ed Sheeran", "1:01:10 - Last Song"), at least 3, the first at 0:00, in order and inside the video (YouTube's own chapter rule) (`descriptionChapters`, `data/SongChapters.kt`). The description comes from the player details the caption lookup already reads (page and app download), kept in memory for the last 8 videos (`VideoDescriptions`).
- **Per song**: LRCLIB is asked for each song, starting with the one playing, one request at a time ("Looking for synced lyrics… song 5 of 32"). A song is taken when its length is within 6 s of the chapter's (songs fade into each other), its lines moved to the chapter's start and cut at its end (`LyricChapter`).
- **Gaps**: Gemini (with a key) writes only the 5-minute parts no song with lyrics covers whole (`AiSubtitleTrack.coveredParts`); in a part it does write, its lines inside a song with lyrics are left out. Without a key the gaps stay empty, the track is saved complete (nothing resumes by itself later), and the panel says "Synced lyrics were found for some songs. Add a free Gemini key…"; after adding one, Try again writes only the gaps.
- The header says "(song lyrics)" when lyrics cover at least half of the video.
- Acceptance: `SongChaptersTest` (description formats and rejections, order from the playing song, moving and cutting lines, covered parts, filtering Gemini lines, round trip), `AiSubtitleRunnerTest.aCompilationAsksGeminiOnlyBetweenTheSongsWithLyrics`, `AiTranscriptBannerUiTest` screenshots. Live LRCLIB: "Lewis Capaldi – Someone You Loved", "Shape of You | Ed Sheeran" and "Ed Sheeran - Perfect" chapter titles all found.
- Not verified: real compilation descriptions on a phone; m.youtube.com's player details are expected to carry `shortDescription` (the app already reads it for language hints).

## Fix: Make subtitles with AI after a song (Kien, 2026-11-17)

On v1.9.9, a captionless video opened after a video with lyrics or an AI transcript kept that track as loaded, so the list showed "AI transcript (Gemini)" selected and **Make subtitles with AI** did nothing. Each load now starts with no loaded track, and the AI transcript counts as showing only while its lines are on screen (`NextVideoForgetsLoadedTrackTest`, PR CI).

## Fix: Gemini copied another language's on-screen subtitles (Kien, 2026-11-17)

On a Chinese song with Vietnamese subtitles in the picture, Gemini named the language zh-Hans but wrote the Vietnamese lines; the header said "Chinese (AI subtitles)" and Google Translate returned the Vietnamese unchanged. Four other uploads of the same song gave Chinese lines in a live test, so it happens only sometimes.

- The prompt asks for the words heard, in the language's own writing system, and never text shown on screen.
- A part named in a language with its own script (Chinese, Japanese, Korean, Thai, Arabic, Hebrew, Hindi, Cyrillic, Greek) whose lines have under 10% of their letters in that script is treated like a refusal: the next model is asked, and if every model does it, the "wouldn't write subtitles" problem shows (`writtenInLanguage`).
- Saved transcripts from before the check drop such parts once, so asking again writes them anew; new files carry `scriptChecked` so a part is never dropped twice.
- Acceptance: `AiSubtitlesTest.linesInAnotherLanguagesLettersAreNotTaken`, `savedLinesInAnotherLanguagesLettersAreAskedAgain`.
