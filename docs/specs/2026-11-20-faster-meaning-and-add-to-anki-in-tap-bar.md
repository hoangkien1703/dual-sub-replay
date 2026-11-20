# Faster meaning in a sentence, Add to Anki in the tap bar, Tap bar buttons

## Status

Approved for implementation. Owner pick 2026-11-20 (thread "Review new GitHub comments": "All three
in one PR") for Discussion #81 comments from lion737: 19674441, 19674625, 19682138, 19684083,
19755925, 19761331, 19761402, and his answers 19768618 and 19770367 to our question 19766837.

## Context / problem

- **Slow meaning.** On v1.9.12 with Gemini Flash-Lite, AI translation on and Prepare meanings ahead
  on, "Meaning in this sentence" takes about 22 s after a tap (stopwatch), for the first word of a
  line and for the second. Flash-Lite itself answers such a question in 0.5 to 1.7 s (measured
  2026-11-20 from the cloud), so the wait is the app's:
  - `AiKeyPacer` keeps a key at 10 requests a minute. Background requests (subtitle windows and
    Prepare meanings ahead, one every 6 s) fill the whole minute, so an urgent tap waited for the
    oldest of them to leave the 60 s window.
  - A tap in a line whose request was running (Prepare meanings ahead, or the first tap's line
    request) waited for that whole request, which may still be queued behind the pacer, up to its
    45 s timeout.
  - Every tapped word gets a new model object. A thinking setting or JSON schema the service refused
    was forgotten with it, so each tap could fail once and then wait for the key's next background
    turn (6 s) before asking again.
- **Subtitle timeout.** His screenshot "AI translation paused … InterruptedIOException: timeout" is
  the subtitle translation: a request ran past 45 s, and the AI then rested 30 s (Google lines).
- **Anki in two taps.** Tapping a word shows the bar; sending a card needs Translate (the word card)
  and then Anki. He asks for Add to Anki in the bar next to the speaker, an Anki button next to every
  subtitle line for sentence cards, and a fully customizable bar (drag to reorder, hide, add).

## Goals

1. A tapped word never waits behind background AI requests: background requests leave 4 of the 10
   requests a minute free for taps, and a tap waits at most 2 s for its line's running request.
2. Line meanings are stored word by word while the reply arrives.
3. A refused thinking setting or JSON schema is remembered for the app run, and the retry of an
   urgent request stays urgent.
4. A subtitle request that timed out before any sentence arrived is sent again at once with half
   its sentences, instead of resting 30 s.
5. **Add to Anki** in the tap bar, right after Pronounce, while Send to can be used. It sends the
   card the word card would send (the bar's meaning: meaning in the sentence and dictionary meaning
   as the word card saves them, sentence, sound, picture). A phrase or a whole line selected with
   the handles makes a sentence card.
6. **Settings → Word learning → Tap bar buttons**: one chip per action (Copy, Translate, Pronounce,
   Add to Anki, Open in Google Translate). On shows the icon in the bar; every action stays in the ▾
   list. Defaults: today's bar plus Add to Anki.

## Non-goals

- No Anki button on every subtitle line (owner agreed to the pushback: it clutters the main screen;
  selecting the line and Add to Anki covers sentence cards).
- No drag-and-drop toolbar editor; the order stays fixed.
- No new AI setting; nothing new spends a key in the background. Prepare meanings ahead stays off by
  default; with it on it now gets at most 6 requests a minute per key instead of 10.
- No change to the Send to logic, the note type or the card layout.

## Design

- `AiKeyPacer(urgentReserve = AI_TRANSLATION_KEY_URGENT_RESERVE = 4)`: a background request's minute
  cap is `perMinute - urgentReserve`; urgent ones keep the whole `perMinute`.
- `AiRefusedSettings` (process-wide sets) keyed by service, address and model (plus the effort value
  for thinking). `completeWhenBusy`'s next turn takes the request's own urgency.
- `LineMeanings.awaitWord(word, …, maxMs)` replaces `awaitRunning`: it waits for the word's key in the
  cache, the request's end, or `LINE_MEANING_WAIT_MS` (2 s). `LineMeaningStream` stores each
  finished numbered line while the reply streams.
- `AiSubtitleTranslator.translateNow(halveOnTimeout = true)`: on an `InterruptedIOException`
  (anywhere in the cause chain) with nothing translated yet, it sends the first half again once.
- `TapBarButton` (`ui/TapBarButtons.kt`): five boolean preferences `tap_bar_*` (SHARE in
  `SETTING_SPECS`, in `RESETTABLE_SETTING_KEYS`, one `SearchableSetting` that also finds each chip's
  name).
- `PhraseActions.ankiCard` (from `AppViewModel.ankiCard`, `savedWordFrom` like the word card) and
  `rememberCardSend` (the word card's `WordCardSend`, now keyed by any value). The bar keeps its
  meanings in `QuickMeaning`; Add to Anki waits for the dictionary meaning and the AI's meaning (at
  most 25 s), then `WordCardSend.tap` (Send again asks first). Problems and the card's sound note
  show under the bar with `WordCardStatus`.

## Acceptance criteria

- [ ] `AiRequestBudgetTest.aTappedWordGoesAtOnceWhilePreparingAhead`: taps every 15 s while a line
      starts every 2 s for 10 minutes never wait; every existing budget scenario still passes.
- [ ] `AiTranslationAccessTest`: background requests leave room for taps; a refused thinking setting
      is not sent by the next tapped word's model.
- [ ] `LineMeaningsTest`: a word is stored as soon as its line arrives; a tap waits at most 2 s.
- [ ] `AiSubtitleTranslatorTest`: a timed-out request is asked again at once with half its sentences.
- [ ] `PhraseSelectionUiTest`: Add to Anki sends the bar's combined meaning in one tap; Tap bar
      buttons hide icons while the ▾ list keeps every action; Add to Anki hides without Send to.
- [ ] Screenshots of the bar with Add to Anki checked before handoff.
- [ ] Phone check by the owner / lion737: meaning appears within a few seconds with Prepare meanings
      ahead on.

## Validation

- 2026-11-20 local (cloud container): `testDebugUnitTest` for `LineMeaningsTest`, `AiTranslationAccessTest`,
  `AiRequestBudgetTest`, `AiSubtitleTranslatorTest`, `WordMeaningInContextTest`, `SettingsSearchTest`,
  `BackupSharingTest`, `PhraseSelectionTest`: passed. `formatCheck`, `complexityCheck`, `lintDebug`,
  `compileDebugAndroidTestKotlin`: passed. The full unit suite and the device tests (including the new
  `PhraseSelectionUiTest` cases and their screenshots) run in PR CI (no `/dev/kvm` here).
- Deviation: with Prepare meanings ahead or skipping ahead, background AI requests now get 6 a minute per
  key instead of 10 (`AiRequestBudgetTest` throughput floors lowered from 90 to 55 in 10 minutes).
- Not yet verified: a real phone with lion737's setup (Gemini Flash-Lite, AI translation, Prepare meanings
  ahead on). The Gemini OpenAI-compatible endpoint's answer to `reasoning_effort: "none"` for
  `gemini-flash-lite-latest` was not checked live (the cloud key only works on the native endpoint, where
  `thinkingBudget: 0` now answers 400).
