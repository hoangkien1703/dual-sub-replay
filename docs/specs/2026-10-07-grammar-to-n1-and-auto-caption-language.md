# Grammar up to JLPT N1, and a smarter Auto caption language

## Status

Implemented. The owner asked in the project thread on 2026-10-07 for grammar explanations up to
N1 and for Auto to pick the spoken caption language, discussed the plan, chose "All 8 languages"
on the decision card, and asked for everything in one PR. That does not authorize merging or
publishing.

## Context / problem

1. **Grammar stops around N4.** [The grammar spec](2026-10-04-japanese-grammar-explanations.md)
   added about 50 hand-written rules. Anything they do not know is silently skipped: tapping
   らしいです listed only です, and ようだ, みたい, はず, わけ, わけにはいかない, ざるを得ない and most
   N3 to N1 points showed nothing. One Kotlin function per point cannot reach the roughly 600
   points of N5 to N1 without fighting the complexity check on every PR.
2. **Auto picked English for a Japanese video.** [The spoken-language spec](2026-09-25-spoken-language-caption-selection.md)
   finds the spoken language from the auto-generated (ASR) track, then the audio track id, then
   `defaultCaptionTrackIndex`. A video with only creator-written English and Japanese tracks, no
   ASR track and no audio-track language falls through to the default index, which creators often
   set to English.

## Goals

- A declarative pattern catalogue with one matcher, so a grammar point is one line of data.
- More than 300 new points covering N5 to N1, each with a JLPT level badge on its card and an
  explanation in all 8 interface languages.
- らしいです lists 〜らしい and です; multi-word points (わけにはいかない, ざるを得ない) are one card.
- Auto also weighs the video title's script and the user's learning language, so the Japanese
  video above gets Japanese captions.

## Non-goals

- No LLM or network service for grammar; no copied explanations (Bunpro, Renshuu, DOJG, Tae Kim).
- Not every N1 point: rare literary forms that IPADIC cannot segment reliably, or that never show
  up in spoken subtitles, are left out.
- No change to explicit (non-Auto) caption-language choices or to the page player's manual track.

## User-visible behavior

- **Grammar, before:** らしいです showed only "です"; わけにはいかない showed わけ, に, は and ない.
- **Grammar, after:** each grammar card shows its form, a small "N3"-style badge (read aloud as
  "JLPT level N3"), and the meaning in the interface language. らしいです shows 〜らしい (N4) and
  です (N5); わけにはいかない is one N3 card; やらざるを得ない is one N2 card.
- **Auto, before:** a Japanese-titled video with only English and Japanese creator tracks loaded
  English when English was the default track.
- **Auto, after:** the order is:
  1. the ASR track's language (YouTube's own speech recognition, still the strongest sign);
  2. the title's script, when a track exists in that language (kana means Japanese; Han means
     Chinese or Japanese; Hangul, Thai, Arabic, Hebrew, Devanagari, Cyrillic and Greek map to
     their languages). The description counts only when non-Latin letters dominate it;
  3. the audio track id's language;
  4. the learning language picked at onboarding, when a track exists in it;
  5. `defaultCaptionTrackIndex`.

  Latin-script titles give no sign, so English, Spanish or Vietnamese videos behave as before.

## Technical constraints / invariants

- Kuromoji IPADIC stays the only analyzer; no new dependency. `data/` keeps no learner-facing
  text: `GrammarMeaning` ids map to `strings_grammar_points.xml` in `ui/GrammarPointText.kt`.
- Every string exists in all 8 `values-*` folders (`AppLanguageTest`, lint `MissingTranslation`).
- The caption provider stays behind `CaptionProvider`; the new `learningLanguage` argument has a
  default so other callers and fakes stay simple. The page sync script still re-verifies its
  origin and only uses the player's own `getOption`/`setOption`.

## Proposed approach / plan

1. `data/GrammarPattern.kt`: a small spec language (word or base form, `~` suffix, `@` part of
   speech, `%` conjugation, `!` exclusion, `?` optional, `^` previous word, `>` next word,
   ` / ` alternatives, `NEG` macro) and a matcher returning the longest match at a position.
2. `data/JapaneseGrammarCatalogue.kt`: 339 entries by level (N5 12, N4 59, N3 70, N2 118, N1 80).
   Existing rules stay for the context-dependent points (に, が, と, て-forms) and get levels.
3. `japaneseGrammar` merges catalogue and rule hits: longest first, then catalogue order, then
   rule order, rejecting any hit that overlaps an accepted one.
4. `ui/GrammarExplanations.kt`: a JLPT badge beside the form.
5. `YouTubeCaptionProvider.spokenCaptionLanguage` gains the title-script and learning-language
   steps; the page script mirrors the title step (it has no learning language).
   `AppViewModel` reads `learning_language`, now saved at onboarding, falling back to a saved
   non-Auto caption language. `RecentCaptionTracks` keys on it and bumps its version, so old
   Auto picks are fetched again.

## Acceptance criteria

- [x] Every catalogue point is found in its own example sentence by the real analyzer, with its
  own meaning (`JapaneseGrammarCatalogueTest`, 339 examples).
- [x] らしいです gives 〜らしい and です; わけにはいかない is one N3 card; やらざるを得ない is one
  N2 card; quoting と言う is not read as "called" (`JapaneseGrammarTest`).
- [x] Existing grammar tests still pass, with rule points keeping their levels.
- [x] A Japanese title beats an English default track; the ASR track still beats the title; a
  title script without a matching track is ignored; the learning language only decides when the
  video gives no better sign (`YouTubeCaptionProviderTest`).
- [x] A different learning language is a different cache entry (`RecentCaptionTracksTest`).
- [x] Every string exists in all 8 languages with matching placeholders.
- [ ] The Japanese video from the report loads Japanese captions on a phone (owner, live YouTube).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including the new catalogue tests | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes with the updated fakes | CI |
| Page script | The title rule in `CAPTION_TRACK_SYNC_FUNCTIONS` picks Japanese for a Japanese title and leaves Latin titles alone | Node harness with the extracted script |
| Physical-device/manual | Tap らしいです and a few N2/N1 points in a Japanese video; check badges and meanings | Owner's phone, preview APK |
| Live YouTube | Open the reported Japanese video with Auto; Japanese captions load | Owner's phone |

## Risks / edge cases

- IPADIC splits words in surprising ways, so a point can be missed or, rarely, fire on the wrong
  words. Each point has an example test, and exclusions guard the false positives found while
  building (ください as a command, quoting と言う, 何でも as "or something").
- Han-only titles map to both Chinese and Japanese; the first with a track wins, so a Chinese
  video with both tracks and a kanji-only title could pick Japanese if Chinese is absent.
- A video titled in Japanese but spoken in English, with no ASR track, now picks Japanese.
  The user can still choose a language explicitly.

## Release intent

`release:patch` (default; app code changes, no owner override).

## Implementation result

As planned. Deviations:

- Overlap handling changed from "drop hits fully inside a winner" to "drop any hit overlapping a
  winner", because longer catalogue points partly overlapped shorter rule hits.
- `AppViewModel` gained a small `fetchCaptionTrack` helper to keep `loadVideo` under the
  complexity limit.

## Validation result

- Passed locally: `formatCheck`, `complexityCheck`, `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, `assembleDebugAndroidTest`; the Node harness for the page script (6 scenarios).
- Final-head CI, including managed-device tests: see the PR.
- Not verified: physical phone and live YouTube. Offline tests do not prove them; the owner can
  check with the PR's preview APK.
