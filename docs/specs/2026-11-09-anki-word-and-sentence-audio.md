# Word and sentence audio on Anki cards

## Status

Implemented, waiting for a phone test. Owner picked "Word voice + real sentence clip" on 2026-11-09 after discussing lion737's request in discussion #81. Merge and release need the owner's OK.

## Context / problem

lion737 (discussion #81, 2026-11-09) learns English and Russian and asked for two sounds on every Anki card:

1. **Word audio:** the word said on its own.
2. **Sentence audio:** the line it came from, cut from the video itself ("the real audio clip, not text-to-speech").

Their mock-up has four fields (Word, Word audio, Sentence, Sentence audio) and a front side with only the word's sound.

Today a card has Word, Meaning, Reading, Sentence, Translation and Source, and no sound at all.

What we checked before picking (2026-11-09):

- The word card already makes a sound file for each word it says (Google's online voice while Google translates, else the phone's voice through `TextToSpeech.synthesizeToFile`).
- Inside the YouTube page, `HTMLMediaElement.captureStream()` on the page video (a Media Source `blob:` video, so same-origin for the page) gives a live audio track. In desktop Chromium on youtube.com it carried the real sound (peak 0.78), also while the video was muted, and asked for no permission. YouTube videos with DRM (bought films) would be silent.
- Android's own screen-audio capture (`MediaProjection` + `AudioPlaybackCapture`) asks for consent every session on Android 14+, shows a casting icon and needs another foreground-service type. Rejected.
- Downloading YouTube's audio streams (as NewPipe or yt-dlp do) breaks often and goes against YouTube's terms more clearly. Rejected.
- AnkiDroid's provider takes media (`content://com.ichi2.anki.flashcards/media`, `file_uri` + `preferred_name`, read through a granted content URI) and lets an app change a note's fields. AnkiConnect Android has `storeMediaFile` and `updateNoteFields`.

## Goals

- **Word audio:** the same voice the word card uses for that word (Google's voice, else the phone's).
- **Sentence audio:** the line's real sound, recorded inside the YouTube page while the line plays. Nothing is downloaded and no permission is asked.
- A word saved, or sent, before its line finished playing gets its clip once the line has played. A card already sent then gets the clip added to its Sentence audio field.
- Cards keep today's front (word and sentence), so nobody's reviews change. The word's sound plays on the front and the sentence's sound on the back. People who want an audio-only front change the card template once in Anki.
- Settings → Flashcards & Anki → **Add audio to cards** (on by default, shown under Send to). It records only while Send to can be used (AnkiDroid installed or AnkiConnect picked), so people who don't use Anki pay nothing.

## Non-goals

- No sentence clips in Practice or Export files (a possible follow-up).
- No clips for lines that never played on this phone (no download).
- No word audio cut from the video: a single word cut from fast speech sounds clipped.
- No change to cards that were sent before this version.

## User-visible behavior

- Settings → Flashcards & Anki → Send to → **Add audio to cards**: "The word in the voice the word card uses, and the line's real sound from the video. The line is recorded while it plays, so a card sent before its line ends gets the sound when it does."
- The word card's Send to and Practice → Export → Send all work as before. With the setting on, each card carries the two sounds when they are ready.
- New cards use a new note type, **DualSub Replay audio**, with fields Word, Meaning, Reading, Sentence, Translation, Source, Word audio, Sentence audio. AnkiDroid can't add fields to the old "DualSub Replay" type, so old cards keep it.
- AnkiConnect Android can't make note types, so there the word's sound goes on the front of the Basic card and the sentence's sound on the back.

## Technical constraints / invariants

- One WebView. The recorder is a page script that only reads the page video's sound (`captureStream` → Web Audio at 24 kHz → a script node that writes silence). It never changes YouTube's requests, the video, its volume or its speed. Like the other page scripts it checks it runs on `https:` + `*.youtube.com`.
- The page keeps the last 90 s of played sound in memory, stamped with the video time. Paused, seeking, buffering and ad time is not kept. A new video clears it.
- Clips are AAC in `.m4a` (about 40 kbit/s, mono), in `files/line-audio/`, one per line (`<videoId>-<startMs>-<endMs>.m4a`). Clips no saved word or waiting card needs are deleted after a day. The folder is left out of Android backups.
- Word sounds are made when a card is sent, in `cacheDir/card-audio/`, and deleted after sending.
- Media go to AnkiDroid through a FileProvider (`${applicationId}.cardaudio`, not exported, only those two folders), with a read grant for AnkiDroid only, taken back after the copy.
- Cards waiting for their sentence live in `anki_audio_pending` preferences (app, note id, line, field), at most 300, for at most 14 days.
- All new text in all 9 interface languages. The AI assistant can switch the setting (`anki_card_audio`).

## Plan

1. Spec (this file).
2. `ui/LineAudioScripts.kt`: the page recorder (`ensure`, `clip`, `stop`).
3. `data/LineAudio.kt`: clip window, PCM decoding, AAC encoding, `LineAudioStore`, `AnkiAudioPending`.
4. `ui/LineAudioCapture.kt`: `LineAudioPageScripts` in `SingleYouTubePage` (keeps the recorder on, saves clips for saved words and waiting cards, then updates waiting cards), `LineAudioBridge` (a clip now, for Send to).
5. `ui/WordAudioRecorder.kt`: the word card's voices, recorded to a file.
6. `data/AnkiDroidNotes.kt`, `data/AnkiConnectNotes.kt`: new fields and note type, media, later field updates.
7. Setting, search, AI assistant setting, strings in 9 languages.
8. Tests.

## Acceptance criteria

- [ ] With the setting on and AnkiDroid installed, playing a line and then sending a word from it gives a card whose Word audio and Sentence audio fields play in AnkiDroid.
- [ ] Sending a word while its line is still playing (paused mid-line) gives a card now, and its Sentence audio fills in once the line has played to its end.
- [ ] A saved word whose line plays later gets a clip; sending it from Practice → Export carries both sounds.
- [ ] With the setting off, nothing records (no page recorder) and cards have empty audio fields.
- [ ] AnkiConnect (desktop-style) gets the DualSub Replay audio note type; AnkiConnect Android gets the sounds on the Basic card.
- [ ] No permission prompt beyond AnkiDroid's existing one.
- [ ] Unit tests: note fields, AnkiConnect fields, clip window, PCM decoding, pending store, script origin checks.
- [ ] Device test: the recorder captures a fixture page's sound in the WebView, and a clip encodes to a playable `.m4a`.

## Validation

- Before building: the recorder idea was checked in desktop Chromium on a real youtube.com video (sound captured, also while muted, no prompt).
- Passed locally (cloud container, 2026-11-09): `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`.
- Passed: the final page script on a fixture page in Playwright Chromium: a 2 s clip cut at the right place (loud and quiet seconds where expected), and a line that hasn't played answers null.
- New unit tests: `LineAudioTest`, `LineAudioScriptsTest`, sound cases in `AnkiDroidNotesTest` and `AnkiConnectNotesTest`.
- New device tests (PR CI): `LineAudioWebViewTest` (AAC `.m4a` encoding read back by `MediaExtractor`; the recorder in a real WebView, skipped only when the emulator can't play the fixture's sound), and the switch in `StudyToolsUiTest`.
- Not yet checked: a real phone with AnkiDroid (media naming, playback, the later Sentence audio update), WebView's tap rule for the audio context on YouTube, AnkiConnect Android, CPU use while recording.

Implementation notes:

- A clip's tail may be missing when the video is paused right at the line's end (Pause after each line), so the line is cut then without waiting for the tail.
- Word sounds are recorded one by one when sending, so Practice → Export → Send all with many words takes longer (one Google voice request per word).

## Follow-up: empty Sentence audio in v1.8.29

lion737 reported (discussion #81, 2026-11-09) a card whose Sentence audio stayed empty. First guess: the WebView's `mediaPlaybackRequiresUserGesture` keeps the recorder's audio context suspended. CI disproved it: under that rule the context runs in the main frame (Chromium applies the tap rule to Web Audio only in cross-origin frames). The wake code was removed again; `LineAudioWebViewTest` now keeps the rule on, as the app does, so this stays checked. A probe with a real YouTube video in the emulator was inconclusive (a WebView with no window never loaded the video). Cause still open: next step is a phone test.

Phone test 2026-11-10 (owner's phone, temporary on-screen logs, removed before merge): the recorder runs and cuts clips (2.6 s for a finished line). The card in question was sent while the video was paused partway through its line (tapping a word pauses the video), so the line had no clip yet: the card went in with its word sound and waited for the line to finish, as designed, and was looked at before then. Not yet checked: that the waiting card gets its sound once the line plays on. Planned follow-up: tell the learner when a card waits for its sentence sound, and check the late fill-in on a phone.

## Follow-up: the phone's voice for words

lion737 also asked to switch the word voice to a male one. While Google translates subtitles, the word card and Anki word audio used Google's online voice (female), so the voice picked in Android's text-to-speech settings was ignored. Owner pick 2026-11-09: a setting, not an in-app voice list. Settings → Word learning → **Use the phone's voice** (off by default, `phone_voice`): words are spoken and recorded for Anki with the phone's own voice. It sits next to Pronunciation volume because it changes the word card too. The AI assistant can switch it, Settings search finds it, and Reset all settings clears it. All 9 interface languages.

## Follow-up: a Flashcards & Anki section

Study tools had grown into a long list mixing play modes with Anki and word-tracking settings. Owner pick 2026-11-09: a new More settings section, **Flashcards & Anki**, holds Send to (AnkiDroid or AnkiConnect, deck) and Add audio to cards. Known words, Mark new words and Character details move to Word learning, next to the word card settings they change. Study tools keeps the play modes, repeat, loop button, skip silence, blur box and page tokens. Settings search, the AI assistant guide and the device tests follow the new places; preference keys don't change, so nothing is lost on update.
