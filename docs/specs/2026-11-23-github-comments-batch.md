# Third line fixes, pause between repeats, Meaning and Fill the gap fronts, slow replay, Beta links

## Status

Implemented. Owner pick 2026-11-23 (thread "Review new GitHub comments": "Yes, all six in one PR")
for Discussion #81 comments 19785800, 19797185, 19797669, 19797827 (25kiuo, lion737), 19798367
(lion737's movie card mockup) and Discussion #257 (ansidalia-lansi). Contributors are credited by
their GitHub login only (lion737 asked to stay anonymous). Patch release.

## Context / problem

- **Third line disappears after a seek back** (25kiuo, 19797827). The third line runs its own
  `translatePlaybackWindow` loop. One failed row (a slow Google answer behind a VPN in China, with no
  on-device model for the pair) stopped it until "Retry translation", and `ThirdLines` only held the
  rows of the current window, so lines played before showed no third line after a seek back. Likely
  cause from reading the code; not reproduced on a phone in China.
- **Third line words can't be tapped** (25kiuo, 19797185): the line was plain text.
- **Pause between repeats** (Discussion #257): shadowing needs time to say the line aloud between
  plays of Repeat each line.
- **Card direction** (Discussion #257: English front, German back) and **a movie card with the word
  missing** (lion737, 19798367).
- **Slow replay once** (25kiuo, 19797669, asked twice): hear a fast line slower once, then back to
  the normal speed, without a speed button.
- **Paste a link** (25kiuo, 19797185 / 19797359): Share from the Bilibili or YouTube app already
  opens videos here, but a Bilibili link was dropped without a word while its Beta switch was off.

## Goals

1. The third line tries again after 10 s or at the next seek, and keeps every third line of the
   video and language it already has, so a seek back shows them at once without new requests.
2. Third-line words select like the translation's (tap, drag, bar, word card), in the third line's
   language, explained in the translation's language.
3. Repeat each line → **Pause between repeats**: Off (default), As long as the line, 2 s, 5 s.
4. Flashcards & Anki → Card front gets **Meaning** and **Fill the gap**.
5. Study tools → **Slow replay on a second tap** (off by default).
6. A shared link to a Beta site that is off asks "Turn on Bilibili (Beta)?" and opens the link.
7. Gear popup: **Hide while playing** becomes **Show while playing** (owner request 2026-11-23), and
   the Translation chip is ticked when the translation shows while playing (Always, the default).

## Non-goals

- No snail button on each line (pushback: it crowds the transcript, like the per-line Anki button
  declined on 2026-11-20); no swipe gesture.
- No separate "count", "length" and "only after the last one" pause settings (pushback): one choice,
  and the pause follows every play, the last too.
- No Youdao translation (needs a paid key; the reported problem is subtitles not found). No video
  clips in Anki cards (no video download; the card already has the line's sound and a picture).
- No paste-a-link box on the main screen: Share already works; only the silent drop is fixed.
- No third-line taps in the full-screen overlay or the TV layout (the subtitle panel only).

## User-visible behavior

- Third line: after a seek back, lines translated before show again at once. A failed request no
  longer leaves the line empty for the rest of the video.
- With Tap to learn on, a tap on a third-line word opens the bar and the word card like the other
  lines: read aloud in the third language, quick translation and "Meaning in this sentence" into the
  translation's language, and a saved word keeps the third line as its sentence's translation and
  the real translation as its third line.
- Repeat each line with a pause: after each play, the video stops at the line's start for the pause,
  then plays it again; after the last play it waits once more, then goes on. Pressing play during a
  pause goes on at once; tapping another line or seeking cancels the pause.
- Card front Meaning: the front shows the meaning (or the word, when no meaning was saved) and the
  sentence's translation; the back shows the word, its sound, reading, sentence and the line's
  sound. Fill the gap: the front shows the sentence with the word's place blank (the bold word is
  kept but not drawn, so the gap has its length); the back draws it again and shows the word, its
  meaning and the translation. On AnkiConnect Android's Basic cards the gap is "＿＿＿".
- Slow replay: tapping the line that is replaying again plays it once at 0.75×; the learner's own
  speed comes back after the line, or at once when another line is tapped.
- Sharing a bilibili.com, b23.tv or bilibili.tv link while that Beta site is off shows a dialog with
  Turn on and the X. Turn on switches it on (its home page does not also open) and opens the link.
  The Google Play build has no Beta sites, so nothing changes there.
- Gear popup: "Show while playing ✓ Translation" by default. Unticking it shows the translation only
  when paused, as ticking "Hide while playing" did. The stored `translated_caption_visibility` value
  (Always, Only when paused, Never) is the same as before, so saved settings need no migration;
  Never shows as not ticked, and a tap from Never shows the line again.

## Technical constraints / invariants

- [Study tools](../project/tech-stack.md#study-tools-and-anki): tools only send pause, seek and
  speed scripts to the one page video. The pause uses `webSeekPausedScript` and the play mode's own
  replay; slow replay uses a new speed script with the video-site origin check, slower only
  (0.5 to 1), with a page timer that always gives the speed back.
- [Settings](../project/tech-stack.md#settings-backup-and-sharing): `study_repeat_pause` (SHARE,
  string) and `slow_replay_second_tap` (SHARE, boolean) are in `SETTING_SPECS`, `STUDY_SETTING_KEYS`
  and `SearchableSetting`; the assistant can change both. Neither uses an AI key.
- Anki: Card front stays the note type's template; new keys `meaning` and `fill_gap` only change
  the template when picked.

## Proposed approach / plan

- `translatePlaybackWindow(known, retryAfterFailureMs)`; `ThirdLineTranslation` keeps `kept` per
  video and language, publishes all of it, and passes `THIRD_LINE_RETRY_MS`.
- `WordTap.language`, `LearningWordSelection.lineLanguage`, `PhraseActions.pronounceIn / selectIn /
  translateFrom`, `SelectableSubtitleText(lineLanguage)`, `TappableThirdLineText`,
  `withSecondLineAsThird`, `AppViewModel.translateLineText`; `meaningContext` uses only the tapped
  segment for a third-line word.
- `RepeatPause`, `repeatPauseMs`, `StudyAction.PlayFromAfter`, `StudyPlaybackRunner.playFromAfter`.
- `CardFront.MEANING` / `FILL_GAP` in `ankiCardTemplate`, `basicCardSidesOf`, `gappedSentence`.
- `SlowReplayTaps`, `replayLine`, `webSlowReplayScript`, `YouTubeWebController.setSlowReplay`.
- `offBetaSiteLink`, `turnOnBetaSite`, `BetaSiteOfferDialog`, `AppViewModel.betaSiteOffer`.

## Acceptance criteria

- [x] A failed third-line row is tried again after `retryAfterFailureMs` or at once on a seek, with
      no Retry (`TranslationFailureTest`).
- [x] Rows known from earlier windows are not translated again (`TranslationFailureTest`).
- [x] A third-line tap has the third line's language, is explained in the target language, and its
      card keeps the right sentences (`ThirdLineTapTest`).
- [x] Repeat each line asks for `PlayFromAfter` after every play, the last one too, with the pause's
      length; without a pause it behaves as before (`RepeatPauseAndSlowReplayTest`, `StudyPlaybackTest`).
- [x] Only a second tap on the same line within its slowed length (plus 1 s) is slow
      (`RepeatPauseAndSlowReplayTest`).
- [x] Meaning and Fill the gap templates and Basic-card fields (`AnkiMeaningAndGapFrontTest`).
- [x] A shared link to an off Beta site makes an offer; on sites, other links and the Play build
      don't (`BetaSiteOfferTest`).
- [x] Show while playing is ticked for Always and not for Only when paused or Never, and a tap
      switches Always and Only when paused (`CaptionVisibilityTest`, `SubtitleUiTest`).
- [ ] Both Android CI jobs pass on the final head.
- [ ] Owner phone test of the preview: third-line taps, repeat pause, slow replay, Turn on dialog.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | Targeted classes above plus the AI, settings-search, backup and playback tests: pass | Cloud container, 2026-11-23 |
| Android lint/build | `verify-build` in PR CI | GitHub Actions |
| Managed-device/emulator | `managed-device-tests` in PR CI | GitHub Actions |
| Physical-device/manual | Preview APK on the owner's phone | Owner |
| Live YouTube | Repeat pause and slow replay on a real video | Owner (not checked in the cloud) |

## Risks / edge cases

- The pause timer runs on the main thread; a seek, a tapped line, playing by hand or another video
  cancels it.
- A word that does not appear in its sentence has no bold part, so Fill the gap shows the whole
  sentence for it.
- The third line keeps at most one video's lines in memory (cleared when the video or language
  changes).
