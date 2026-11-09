# Dictation play mode

## Status

Implemented. Owner picked "Dictation mode with typing" on 2026-11-09 after discussing lion737's request in discussion #81. Merge and release need the owner's OK.

## Context / problem

lion737 (discussion #81, 2026-11-08) asked for two things:

1. **Dictation** like dailydictation.com, on YouTube videos: a line plays, its subtitle stays hidden while you write what you heard, then it appears. They suggested a fixed hide time of 2, 3 or 5 seconds.
2. **Easy pausing** like Language Reactor's AP (auto-pause) button.

Auto-pause already exists: Settings → Study tools → Play mode → *Pause after each line*, and *Listen first* also hides the line until the pause. The AI assistant can switch both. lion737 did not find them, and Search settings found nothing for "pause", because it only matched setting names ("Play mode"), not the choices inside them.

A fixed hide timer does not suit a phone: typing a line usually takes longer than 5 seconds, and a timer running out mid-sentence gives the answer away. So the line waits until the learner checks or shows it.

## Goals

- Settings → Study tools → Play mode → **Dictation**. Each line plays with its subtitle hidden, and the video pauses when it ends.
- The subtitle panel shows a box under the transcript: "Type what you heard", with **Replay**, **Show** and **Check**.
- **Check** marks each word of the line in the accent colour (heard right) or red and underlined (missed or wrong), says how many were right, and shows the line and its translation in the transcript. **Next** then plays the next line.
- **Show** reveals the line without checking, for people who write on paper.
- Search settings finds Play mode by its choices: "pause", "dictation", "listen", "repeat" and so on.
- The AI assistant can switch to Dictation (`study_play_mode` = `dictation`).

## Non-goals

- No hide timer and no word chips (a possible "easy mode" later).
- No dictation box in full screen, the overlay player mode, the TV layout or live subtitles. There Dictation acts like Listen first (subtitles show while paused), so the video is still usable.
- No score history or statistics.
- No slower replay button: YouTube's speed and the AI assistant's speed already do that.

## User-visible behavior

- **Hidden lines:** with Dictation on, every transcript row the learner has not revealed shows one dot per word (per character for Chinese and Japanese) instead of its text and translation, so the word count is a small hint. Tapping a row still replays it.
- **The box** sits at the bottom of the subtitle panel, above the keyboard. It follows the playing line, or the last line played when the video is between lines. A typed answer is kept per line, so Replay (or a tapped row) doesn't lose it.
- **Keyboard:** its action key is Check before the line is revealed, and Next after.
- **Check rules:** capital letters, punctuation and spacing don't count; letters with accents do. Words are compared in order (a missed word doesn't make the rest wrong). Chinese is checked per character. Japanese is checked per word, and a word typed in kana counts as right when it matches the word's reading (once the Japanese analyzer has loaded), so hiragana, katakana or kanji all work. Thai, Lao, Khmer and Burmese are checked per character.
- **After Check:** the line with right words in the accent colour, missed ones red and underlined, and "4 of 6 right" (or "Perfect!"), then **Replay** and **Next**. The row in the transcript shows the line and its translation as usual, so a missed word can be tapped and saved.
- Revealed lines stay revealed while the same video is open in the panel.
- The setting's description says that auto-generated captions can contain mistakes.

## Technical constraints / invariants

- One WebView. The play-mode driver only adds Dictation to the modes that pause at a line's end. Replay and Next use the existing `replayFrom` seek (a learner seek), so the driver pauses again at the end of the line.
- All new text in all 9 interface languages.
- The reveal and answers live in the panel's composition (not in the view model or preferences): nothing is saved.
- YouTube's own captions stay hidden in Dictation, as in Listen first.

## Plan

- `StudyPlayMode.DICTATION` (`dictation`), after Listen first. Driver: pause at the line's end like Pause after each line. `studyCaptionVisibility` and `shouldSuppressNativeCaptions` treat it like Listen first outside the panel; the panel stays shown while playing.
- `ui/Dictation.kt`: `DictationBox` (text field, buttons, result), `DictationState` (revealed ids, answers, results, per video), hidden-row dots.
- `data/DictationCheck.kt`: units (words, characters, or Japanese words with readings), normalization, an in-order alignment that may match one unit to several typed characters (for kana readings). Pure Kotlin, unit-tested.
- `SubtitleTimeline`: hidden rows in Dictation; `TranslatedSubtitleTimeline` adds the box under the list (not on TV).
- `SearchableSetting.alsoFinds`: Play mode also matches its choices' names.
- `AI_STUDY_PLAY_MODES` + description + alias ("dictation").

## Acceptance criteria

- Play mode lists Dictation after Listen first; picking it saves `dictation`.
- In Dictation, an unrevealed row shows dots, not its text; Check or Show reveals it.
- Typing "we begin to make a snowman" for "We begin to make a snowman." shows every word right and "Perfect!".
- Typing "we begin make snowman" marks "to" and "a" as missed and says "4 of 6 right".
- Japanese: "わたしはがくせいです" is right for 私は学生です when readings are known.
- Searching "pause" in Search settings shows Play mode.
- The AI assistant accepts `study_play_mode` = `dictation`.

## Validation plan

- Unit tests: `DictationCheckTest` (words, punctuation, case, accents, order, Chinese characters, Japanese readings), `StudyPlaybackDriverTest` (Dictation pauses at a line's end), `SettingsSearchTest` (choice names), AI action tests (the new value).
- Compose test `DictationUiTest`: hidden row, typing and Check, Show, Next callback; saves screenshots of each state (dark and light).
- Phone test by the owner on the preview APK: a real video, keyboard open, Replay and Next.

## Release intent

`release:patch` (new feature in the APK, default for owner PRs).

## Results

- Local (cloud container, no emulator): `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` pass. New unit tests: `DictationCheckTest` (9), Dictation cases in `StudyPlaybackTest`, `SettingsSearchTest`, `AiActionsTest`.
- `DictationUiTest` (Compose, with screenshots) runs only in PR CI (`managed-device-tests`).
- Not yet checked on a phone: keyboard over the panel, a real video's pauses, Replay and Next.
- Deviation: right words use the accent colour (the theme's `primary`) instead of green, since the app's colours come from the theme; missed words are red (`error`) and underlined so they don't rely on colour alone.
