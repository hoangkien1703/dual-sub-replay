# Tapped word first, Custom repeat count, Whole transcript, reading in the tap bar

## Status

Approved for implementation. Owner picks 2026-11-17 (thread "Review new GitHub comments") for
Discussion #81 comments 19663025 (lion737), 19671270 and 19672145 (25kiuo):

- The tapped word's AI meaning goes first (no extra requests: the owner asked, and it adds none).
- Custom repeat count.
- Whole transcript, plus export. The owner noted the panel's window is deliberate: a 6-hour video
  once used up the app's memory. The playing panel keeps it; a separate sheet pages from disk.
- The reading in the tap bar is a setting, off by default (owner).

The Beta section for other video sources (19671491: a video file plus a subtitle file, then
Bilibili) is a separate PR and spec.

## Context / problem

- lion737 (19663025): "Meaning in this sentence" takes too long after a tap. His ideas: prepare
  ahead, a faster model, a cache. Prepare meanings ahead (off by default) and the cache already exist
  (v1.9.6). Measured 2026-11-17 with the cloud Gemini key: `gemini-flash-lite-latest` answers this
  kind of question in 0.3 to 0.9 s, `gemini-flash-latest` in 3 to 6 s. The code adds a wait of its
  own: `AiKeyPacer` keeps every request on a key 6 s apart and hands out turns in order, so a word
  tapped just after the line's own request (or a Prepare-ahead request) waited up to 6 s, and behind
  every background request already queued.
- 25kiuo (19672145): Repeat each line's count as a slider "from 1 to infinite". The chips were 2, 3,
  5 and Forever. One play is no repeat, and a slider alone can't hit an exact number on a phone.

## Goals

1. A tapped word's AI meaning (and the request for the rest of its line) goes ahead of background
   AI requests and is not held to the 6 s spacing.
2. No extra AI requests and no extra tokens: only the order and timing change. The key stays within
   10 requests in any minute (`AiRequestBudgetTest`).
3. Repeat each line: chips 2, 3, 5, 10, Forever and Custom. Custom shows a slider (2 to 99) with −
   and + buttons and the count.
4. Whole transcript: every line of the video, a search box, a tap on a line jumps there, Export as a
   text or SRT file with or without the translation. Memory stays bounded for a 6-hour video.
5. Settings → Word learning → Pronunciation in the tap bar (off): a single tapped word's IPA
   (English), kana (Japanese) or pinyin (Chinese) shows above its meaning in the bar.

## Non-goals

- No new AI switch, no prefetch while playing (Prepare meanings ahead stays off by default).
- No change to the model the learner picked; the reply to lion737 asks which model he uses.
- The playing panel's window (30 s back, 60 s ahead, at most 96 rows) does not change.
- No translation of the whole video while browsing: only the rows on screen once the list rests,
  and everything only when an export with translation asks for it (Google's limits, PR #111).

## User-visible behavior

- With Meaning in this sentence on, a word tapped in a new line is asked about at once, even right
  after another line's request; background requests wait for their next turn instead.
- Settings → Study tools → Play mode → Repeat each line: 2 times, 3 times, 5 times, 10 times,
  Forever, Custom. Custom keeps the current count (Forever turns into 2) and shows − slider + and
  "N times". The AI assistant's `study_repeat_count` takes 2 to 99 or forever.

- Settings → Playback → **Whole transcript** (off by default, owner 2026-11-17). When on, the gear
  popup (the subtitle panel's gear) shows **Whole transcript** while a full transcript is loaded (not
  with live subtitles). The sheet opens at the playing line, shows "m:ss" and each
  row's text; a sentence's translation shows under its first row once the list rests for 0.6 s.
  Search lists up to 500 matching rows. The Download icon opens Export: Text file or Subtitle file
  (SRT), Include the translation (on), then Android's own save screen (no permission). Export runs on
  after the sheet closes, with a progress bar and Stop; it writes one entry per sentence.
- Settings → Word learning → **Pronunciation in the tap bar** (off). The plan said "an option under
  the existing reading setting"; the only reading setting is the Anki card's Reading place, so the
  switch sits in Word learning beside the other tap settings instead.

## Design

- `AiKeyPacer.take(ids, urgent)`: each key keeps its last `perMinute` (10) send times. A background
  request may go `intervalMs` (6 s) after the key's last send and only when the minute has room; it
  does not reserve a future moment, so after waiting it checks again and an urgent request that went
  meanwhile pushes it back. An urgent request takes the first moment the minute has room, ignoring
  the 6 s spacing, and keeps that moment.
- `aiTranslationModel(..., urgent)` builds a model whose requests are urgent. `AppViewModel`
  uses it for the tapped word (`askMeaningInContext`) and for the line request a tap starts
  (`LineMeanings.prepare(..., urgent = true)`); Prepare meanings ahead and subtitle translation stay
  background.
- `STUDY_REPEAT_COUNTS` adds 10; `STUDY_REPEAT_CUSTOM_RANGE` (2..99, shared with the assistant's
  `AI_STUDY_REPEAT_RANGE`) is what `storedStudyRepeatCount` keeps.

- `WholeTranscript` (`ui/WholeTranscript.kt`) wraps the playing video's display `SubtitleStore` and
  its engine's `translate`/`prefetch`, set by `runStoredTranslation`'s `follow` and cleared before
  the store closes. Rows come in pages of 50, at most 8 pages kept; search and export read 500 rows
  at a time and drop them; export writes through a buffered writer (`TranscriptExporter`, view model
  scope, `CreateDocument`). A closed store makes reads return nothing and export stop.
- `LocalWholeTranscript` hands the flow, exporter and title to `DualSubExperience`.
  `WHOLE_TRANSCRIPT_PREFERENCE` gates the gear popup's link; it is in Settings search, the AI
  assistant (`whole_transcript`), shared settings, Reset and the Playback summary.
- `TAP_BAR_READING_PREFERENCE` (`ui/TapBarReading.kt`): `PhraseActions.readingInBar`; the bar shows
  `tapBarReading(token, language)`: the analyzer's reading, else English IPA. In Settings search,
  the AI assistant (`tap_bar_reading`), shared settings and Reset.

## Acceptance criteria / validation

- `AiTranslationAccessTest.aTappedWordGoesAheadOfAWaitingBackgroundRequest`: a background request
  waiting for 6 s is passed by a tap 1 s in, which goes at once; the background one then goes 6 s
  after the tap.
- `AiTranslationAccessTest.tappedWordsStillKeepTheMinuteBudget`: an 11th urgent request in a minute
  waits for the minute.
- `AiRequestBudgetTest.tappedWordsWhilePreparingAheadStayWithinTheBudget`: taps every 4 s while
  Prepare meanings ahead runs for 10 minutes stay within 10 requests a minute.
- `AiActionsTest`: the assistant sets 4, 10; refuses 1 and 100.
- `StudyToolsUiTest.customRepeatCountTakesAnyCountWithTheButtons` (+ screenshot
  `study-repeat-custom`).
- `WholeTranscriptTest`: paging, sentence translations, search, text and SRT export, a closed store,
  and a 6-hour transcript (14,400 lines) searched and exported through a writer that keeps nothing.
- `StudyToolsUiTest.theSleepTimerLivesInPlaybackAndOnlyJoinsTheGearPopupWhenAsked`: the Whole
  transcript switch is off by default.
- `WholeTranscriptUiTest` (+ screenshots `whole-transcript`, `whole-transcript-search`,
  `whole-transcript-export`, `gear-popup-whole-transcript`).
- `PhraseSelectionUiTest.theBarShowsTheWordsIpaOnlyWhenTheSettingIsOn` / `...WhileTheSettingIsOff`
  (+ screenshot `phrase-action-bar-reading`).

## Results

Unit tests: see the PR. Device check of the faster meaning needs a real key on a phone (unverified).
