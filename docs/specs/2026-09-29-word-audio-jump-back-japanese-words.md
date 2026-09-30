# Word audio on tap, jump back to the spoken line, whole Japanese words

## Status

Released in v1.3.1 (PR #87, merged 2026-09-30). All four Android CI jobs passed on the final head
`5f6754b`, and the owner tested the preview on their phone on 2026-09-30 and reported it works
well. The owner asked for all three changes on 2026-09-29 in the project thread. Bundling the
dictionary grew the release APK from 29.8 MB to 41.2 MB; on 2026-09-30 the owner chose to download
it on first use instead.

## Context / problem

The owner reported three things in the dual subtitle panel:

1. Tapping one word selects it and shows its translation, but the word is only spoken after
   pressing **Pronounce**. Learners want to hear it straight away, and to hear it again and again
   without waiting for the speech engine each time. The owner's rule for the audio: keep it after
   the first play, and drop it once a different word is chosen.
2. After scrolling the transcript away from the spoken line there is no quick way back. A
   reference app shows a small "Now playing · 00:09" pill while the user scrolls away, hides it
   once they stop to read, shows it again when they scroll again, and never shows it while the
   spoken line is on screen.
3. Japanese words are split badly. `LanguageAwareTokenizer.tokenizeJapanese` cuts at script
   boundaries and at any hiragana that looks like a particle, so `あったかいです` becomes
   `あった / か / い / です` and `手伝って` loses its ending. Tapping selects a fragment, not the
   word.

## Research: how others segment Japanese for learners

- **MeCab / Kuromoji (IPADIC)**: a dictionary plus a Viterbi lattice over connection costs. It is
  the standard morphological analyzer; jisho.org uses MeCab to recognise conjugated verbs.
  Kuromoji is the pure-Java port with the same IPADIC dictionary (Apache-2.0,
  `com.atilika.kuromoji:kuromoji-ipadic`). Output is morphemes with part of speech,
  conjugation form, base form and reading.
- **Morphemes are not learner words.** Analyzers split `食べて` into `食べ` + `て` and
  `思います` into `思い` + `ます`. Learner tools group them again: `bunsetsu` (a Kuromoji wrapper)
  attaches te-forms, past and polite endings, auxiliary verbs such as `〜ている`, `〜てもらう`,
  `〜てみる`, and adjective endings to the stem. Yomitan/10ten instead scan from the tapped
  character and deinflect the longest match against JMdict, which needs a large dictionary.
- **Sudachi** has better multi-granularity output but its smallest dictionary is several times
  larger than IPADIC. **Lindera** is Rust, which this app cannot ship in the full build without a
  new native toolchain.
- **ICU `BreakIterator` / `Intl.Segmenter`** cost nothing on Android (API 24+), but a check on the
  owner's sentences split `手伝って` into `手 | 伝 | って` and `食べられなかった` into
  `食 | べら | れ | なか | っ | た`, and they give no part of speech.

**Chosen:** Kuromoji IPADIC for analysis, plus a small grouping pass in the app modelled on
`bunsetsu`, because it is the only option that gives correct morphemes and part of speech in pure
Java, works in both the full and F-Droid builds, and keeps the tokenizer a local, testable function.
The dictionary is 13 MB, so (owner's decision) the APK keeps only Kuromoji's code and downloads
the unchanged Maven artifact, pinned by size and SHA-256, the first time Japanese text is
tokenized. It then loads (about 50 MB of heap) on a background thread; until then the old
heuristic is used.

## Goals

- Tapping a single subtitle word speaks it when **Pronounce tapped words** is on (the existing
  setting, default on). The first play is synthesized to a small audio file; replaying the same
  word plays that file at once. Selecting or pronouncing a different word deletes it.
- A "Now playing · m:ss" pill over the transcript while the user scrolls with the spoken line off
  screen. It hides 2 seconds after scrolling stops, comes back on the next scroll, never shows while
  the spoken line is visible, and tapping it scrolls back to the spoken line.
- Tapping a Japanese word selects the whole learner word: stem with its conjugation and auxiliary
  endings, numbers with counters, nouns with suffixes, and suru-verbs.

## Non-goals

- No change to how the transcript follows playback or to the phrase selection gestures.
- No Chinese analyzer change (Chinese keeps the current heuristic).
- No Wi-Fi-only setting for the dictionary download; it follows the translation models, which
  also download on any network.
- No offline audio for words that were never played; the cache holds one word.

## User-visible behavior

- **Before:** tap `思い` in `思います`, see a fragment selected and hear nothing. Scroll away from
  the spoken line and scroll back by hand.
- **After:** tap anywhere in `思います`, the whole verb is selected and spoken, and **Pronounce**
  replays it instantly. While scrolling away from the spoken line, a pill offers a way back.

## Technical constraints / invariants

- Unit tests are plain JUnit4 on `internal` functions. The Kuromoji jar is a normal dependency, so
  unit tests can analyze real sentences.
- One WebView, one online player: pronunciation keeps using Android TextToSpeech and a local file.
- F-Droid build: Kuromoji and IPADIC are free software; no proprietary dependency is added.
- R8 must keep Kuromoji's class names, because it loads its dictionary relative to its package.

## Proposed approach / plan

1. `data/JapaneseMorphology.kt`: lazy Kuromoji loader (single background thread, failure falls
   back to heuristics and retries after a minute) with `revision` and `status` flows, and
   `groupJapaneseMorphemes` that turns morphemes into learner words with part of speech, reading
   and base form. `data/JapaneseDictionaryStore.kt` downloads the IPADIC jar from Maven Central
   (Google's mirror as fallback), verifies it, and builds Kuromoji from its entries. The APK
   excludes the dictionary files; the selection bar says when the dictionary is downloading.
2. `LanguageAwareTokenizer.tokenize` uses the grouped words for Japanese (`ja`, or kana in text
   without a Chinese language code) once Kuromoji is loaded. UI `remember` keys include the
   revision, so lines re-tokenize when it finishes loading.
3. `PronunciationEngine.synthesize` writes speech to a file; `pronounceWord` takes the render step
   as a parameter. `PronunciationCache` keeps one word's file. `WordPronouncer` plays the cached
   file with `MediaPlayer`, falls back to direct speech when synthesis or playback fails.
4. `PhraseActions.select` runs when a selection changes: speak a single word when auto-pronounce is
   on, otherwise drop cached audio for a different word.
5. `SubtitleTimeline` gets a `JumpBackPill` driven by `activeRowPlacement` and a user-scroll
   state from the list's drag interactions.

## Acceptance criteria

- [x] `groupJapaneseMorphemes` keeps `思います`, `あったかい`, `手伝ってもらって`,
  `食べられなかった`, `勉強しています`, `５歳`, `お母さん` and `でした` whole, and keeps particles
  such as `は`, `と`, `が` separate (`JapaneseMorphologyTest`).
- [x] Existing tokenizer, phrase and timing tests still pass with either analyzer.
- [x] The dictionary store installs only a file with the pinned size and SHA-256, falls back to
  the mirror, and loads Kuromoji from the stored file (`JapaneseDictionaryStoreTest`).
- [x] The release APK carries no dictionary files and stays near v1.3.0's size.
- [x] `PronunciationCache` returns the stored file only for the same word and language and deletes
  it when another word is prepared (`PronunciationCacheTest`).
- [x] `pronounceWord` with a render step reports success from synthesis and still tries other
  voices on failure (`PronunciationTest`).
- [x] `activeRowPlacement` reports visible, above or below, and `jumpBackPillVisible` shows the
  pill only while browsing with the spoken row off screen (`JumpBackPillTest`).
- [x] On the managed device, scrolling the transcript away shows the pill, it hides after the
  learner stops, returns on the next scroll, and tapping it brings the spoken line back
  (`SubtitleUiTest.jumpBackPillShowsWhileScrollingAwayAndReturnsToTheSpokenLine`), and the exact
  Maven artifact passes the pinned checksum and loads on ART (`JapaneseMorphologyDeviceTest`,
  which installs it from a test-APK asset instead of the network). Passed in CI
  `managed-device-tests`.
- [x] Owner's phone: tapping a Japanese word selects the whole word and speaks it; tapping
  Pronounce again replays at once; the pill behaves as described on a live video. The owner
  tested the preview on 2026-09-30 and reported it works well.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local Linux, CI |
| Release shrink | `assembleRelease -PtestReleaseSigning=true` keeps `com.atilika.kuromoji` class names and contains no dictionary files (downloaded on first use) | Local Linux |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including Kuromoji loading on device | CI `managed-device-tests` |
| Physical-device/manual | Tap words, replay, scroll away and back | Owner's phone |
| Live YouTube | Same on a Japanese video | Owner's phone |

## Risks / edge cases

- The first Japanese video shows heuristic words while the 13 MB download and load finish; lines
  update when it is ready, and any selection on them is cleared. Offline, it retries a minute
  later on the next Japanese line.
- Downloads come from Maven Central or Google's mirror of it. A changed or damaged file fails the
  checksum and is never loaded.
- Low-memory devices may fail to load Kuromoji; the error is caught and heuristics remain.
- Some TTS engines cannot synthesize to a file; the app then speaks directly without caching.
- Grouping is a heuristic over IPADIC tags; rare constructions may still split or over-group.

## Release intent

Planned: patch as the default, with `release:minor` recommended for the owner to choose because
these are new features. Outcome: after testing on their phone, the owner asked for v1.3.1, so
`release:patch` was applied and the merge released v1.3.1.

## Implementation result

As planned, with these details:

- `pronounceWord` gained a `render` parameter (speak by default), placed before `createEngine`
  so existing trailing-lambda calls keep working. `AndroidPronunciationEngine` shares one
  utterance helper between `speak` and `synthesize`, and `playSpeechFile` plays a recording with
  `MediaPlayer` (media usage, speech content), releasing it on cancel.
- The cache is kept when a selection is simply cleared, so tapping the same word again replays
  at once; it is deleted when another word or phrase is selected or pronounced, and when the
  pronouncer closes. Leftover files from an earlier run are removed on the next recording.
- The existing **Pronounce tapped words** setting (default on) now also covers tapping a word in
  the subtitles; its description says so. Punctuation and phrases are not spoken on select.
- The pill lives in `ui/JumpBackPill.kt`. It counts a row as visible when at least half of it is
  on screen, shows an up or down arrow toward the spoken line, and uses YouTube's clock format
  (`0:09`). Only the learner's drags start browsing; the app's own follow scrolls do not.
- Grouping rules beyond the plan: `だ` stays on a verb as past tense (`読んだ`), `高くない`
  keeps its independent `ない`, compound verbs join (`思い出す`), and only the prefixes お, ご,
  御 and 第 join the next word (`今` stays separate before `２月`).
- Kuromoji's two jars ship the same `META-INF` license files, so packaging picks the first.
- Download on first use (owner's choice after the first push): packaging excludes
  `com/atilika/kuromoji/ipadic/*.bin`. Kuromoji 0.9.0's builder always reads from its own
  package, so `JapaneseDictionaryStore` subclasses it and repeats its loading steps against the
  downloaded jar. The jar is kept as downloaded (13 MB of storage), not unpacked (33 MB). Without a
  configured store (unit tests) the analyzer uses the dictionary on the classpath. A Gradle task
  copies the same Maven artifact into the device-test APK as an asset.

## Validation result

- Passed locally (Linux, JDK 21, Android SDK 36), bundled version:
  `./gradlew testDebugUnitTest` (359 tests, 0 failures) and
  `./gradlew formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest`.
- Bundled version (first push): release APK 43,196,573 bytes (41.2 MB), over the 40 MB stretch
  target; v1.3.0 was 29,822,538 bytes.
- Download version, passed locally: `./gradlew testDebugUnitTest` (365 tests, 0 failures), the
  CI-parity command above, and `assembleRelease -PtestReleaseSigning=true`:
  `tools/report_apk.py` reports 29,870,705 bytes (28.49 MB), no dictionary files in the APK,
  Kuromoji classes kept. The device-test APK contains `assets/japanese-dictionary.jar`.
- Not run locally: `pixel2Api36DebugAndroidTest` (no KVM on this host) and the F-Droid build
  (needs the NDK). Android CI on `4f2162a` (the download version) passed all four jobs:
  `verify-build`, `managed-device-tests` (including the jump-back pill and the on-device
  Kuromoji load from the pinned jar), `fdroid-build` and `fdroid-device-tests`. They passed
  again on the final head `5f6754b` (run 36691027357).
- Owner's phone test with live YouTube: the owner tested the preview on 2026-09-30 and reported
  it works well.
