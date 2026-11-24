# Real lyrics for long playlists, and AI only on a tap

## Status

Implemented. The owner picked "No auto AI + match songs" and "Read it on the phone" in the project thread on 2026-11-24. This status doesn't authorize a merge or a release.

## Context / problem

The owner opened a 1 h 40 min "Top Hits Spotify" playlist video (30 songs) and tapped **Get real lyrics**. It never worked, while short popular songs did. Two causes:

1. **Only description times were read.** [Compilation lyrics](2026-11-17-ai-transcript-for-songs.md) needs a description with a start time per song. This video lists its songs **only on its picture**, with no times anywhere, so the whole video was searched on LRCLIB as one song. Nothing matched.
2. **Gemini then started by itself.** With a key, "no lyrics found" fell straight through to Gemini, which spent the key on a 100-minute video. The owner wants free lyrics first. When those fail, the app should **offer** AI and never start it by itself.

AI subtitles for a long video also showed only their first 5-minute part until every part was in, so the panel ran out of lines while Gemini was still working.

## Goals

- **Get real lyrics** uses free LRCLIB lyrics only and never starts AI.
- When free lyrics don't match, or only some songs do, the card says so. With a key it offers **Write lyrics with AI** or **Write the rest with AI**. Without a key it offers **Add a free Gemini key**.
- A playlist that lists its songs without times gets each song's lyrics placed where it is sung. The list can come from the description or, from 10 minutes, from the text on its picture.
- New AI parts near the playing time show as soon as they are in.

## Non-goals

- No Gemini request to read the picture. Text is read on the phone.
- No change to the "AI transcript (Gemini)" choice or the no-captions "Make subtitles with AI" button. Both are explicit AI taps, so they still try free lyrics for a song first and then Gemini.

## How songs are placed (`LyricsAlignment.kt`)

YouTube's auto-generated captions get sung words wrong but their timing right. For each song:

1. Ask LRCLIB for it (no length filter; the song whose name and artist are in the listed title wins).
2. Pair up word pairs (bigrams) that the lyrics and the captions share. Pairs the captions repeat more than 6 times are dropped ("i love").
3. Take the longest chain of pairs that runs forward in both and keeps a pace of 0.75–1.35× (covers are slower or faster), with ±4 s slack.
4. Accept only with at least 6 anchors on 4+ lines, covering 20 % of the lines and 35 % of the song. Otherwise the song is left out, because wrong lyrics are worse than none.
5. Map each lyric line through the chain (piecewise linear). Overlapping songs keep the one with more lines.

Songs with description times use the old exact-length match first. If that fails, they use the same matching within ±15 s of the chapter. A single song with no length match is also matched against its captions (up to 20 minutes).

## Reading the picture (`VideoPictureText.kt`)

- Downloads `i.ytimg.com/vi/<id>/maxresdefault.jpg` (falls back to `sddefault`), scales it ×2, and reads it with ML Kit text recognition (Latin).
- Uses the **Play services** model (`play-services-mlkit-text-recognition`, about 0.3 MB in the APK). The bundled model adds about 9 MB across arm64 and armv7 and would break the 32 MB APK limit (v1.9.18 is 29.8 MB). The first read may wait up to a minute while Play services downloads the model. On a phone without Google Play services, the picture is skipped quietly.
- `pictureSongList` keeps lines like `01. Song — Artist` or `Song — Artist .01`, counts each song once (pictures often print the list on both sides), and needs at least 3.

## Acceptance criteria

- [x] Tapping **Get real lyrics** never calls Gemini (`TranscriptAsk.FREE_LYRICS`; banner UI test checks it calls `findLyrics`, not `useTranscript`).
- [x] No match with a key: "No free synced lyrics match this video." plus **Write lyrics with AI** (UI test, screenshot `ai-transcript-no-free-lyrics`).
- [x] Some songs: "Free synced lyrics found for 21 of 30 songs." plus **Write the rest with AI** (UI test, screenshot `ai-transcript-some-free-lyrics`).
- [x] Listed songs are placed where misheard captions sing them, slower covers included, and songs the captions don't sing are left out (`LyricsAlignmentTest`).
- [x] A picture list printed in two columns gives each song once; the description list wins over the picture; no captions means no picture read (`FreeLyricsLookupTest`).
- [x] AI parts within 10 minutes of the playing time load as they arrive (`AiSubtitleRunnerTest.aLongVideoShowsNewPartsNearThePlayingTimeAsTheyArrive`).
- [ ] On a real phone: the owner's 30-song playlist finds most songs. **Unverified**: the cloud has no Play services emulator, so ML Kit reading and live LRCLIB matching need the preview APK.

## Validation

Unit tests: `LyricsAlignmentTest`, `FreeLyricsLookupTest`, `AiSubtitleRunnerTest`, `SyncedLyricsTest`, `SongChaptersTest`. Device UI tests: `AiTranscriptBannerUiTest` on PR CI.
