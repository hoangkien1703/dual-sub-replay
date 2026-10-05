# Technical context

This is a source map and explanation of architectural boundaries. [AGENTS.md](../../AGENTS.md)
contains operational commands and contributor rules. Follow source/build files if a
version snapshot here drifts, and update relevant documentation with lasting changes.

## Stack and authoritative configuration

| Area | Current context | Source of truth |
| --- | --- | --- |
| Android application | `:app`, `com.kienhoang.dualsubreplay`; min SDK 26, compile/target SDK 36 | [App build](../../app/build.gradle.kts) |
| Language/build | Kotlin 2.3.21, AGP 9.3.2, JDK 17; committed Gradle 9.5.0 wrapper | [Root build](../../build.gradle.kts), [wrapper](../../gradle/wrapper/gradle-wrapper.properties), [CI](../../.github/workflows/android.yml) |
| UI | Jetpack Compose / Material 3; Compose BOM 2026.06.01 | [App dependencies](../../app/build.gradle.kts) |
| Translation/network | ML Kit Translate 17.0.3, OkHttp, Kotlin coroutines | [App dependencies](../../app/build.gradle.kts) |
| Japanese words | Kuromoji 0.9.0 (Apache-2.0, pure Java); its 13 MB IPADIC dictionary downloads on first Japanese use | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-09-29-word-audio-jump-back-japanese-words.md) |
| Tests | JUnit4 4.13.2; Compose/Android instrumentation, API 36 AOSP x86_64 managed Pixel 2 | [App test configuration](../../app/build.gradle.kts) |
| Performance | Separate test-only `:benchmark` module, Macrobenchmark and app Baseline Profile | [Benchmark build](../../benchmark/build.gradle.kts), [methodology](../quality-and-performance.md) |
| Website | Static English/Vietnamese landing page in `site/`, published to GitHub Pages from `main`; offline checks in `tools/tests/test_site.py` | [Pages workflow](../../.github/workflows/pages.yml), [discoverability](../promotion/discoverability.md) |

## Component boundaries

Paths below are relative to
[`app/src/main/java/com/kienhoang/dualsubreplay`](../../app/src/main/java/com/kienhoang/dualsubreplay).

- `ui/DualSubApp.kt` and `ui/LearningPlayerRoot.kt` compose the learning interface.
  `ui/AppViewModel.kt` coordinates selection, caption loading, translation, playback
  state, and preferences rather than owning another video player.
- `ui/YouTubeBrowserScreen.kt` owns `SingleYouTubePage`, the persistent WebView.
  `ui/YouTubeWebScripts.kt` reads/seeks the page's native video and captures eligible
  live caption data. Caption clock, snapshot gating, and presentation helpers keep
  playback observations separate from UI rendering.
- `data/CaptionProvider.kt` is the provider interface. `YouTubeCaptionProvider.kt`
  discovers caption tracks from watch-page player metadata and undocumented
  Innertube player fallbacks, then fetches timed text. `CaptionDocumentParser.kt`
  handles formats. Do not spread YouTube extraction details into UI/translation code.
  `RecentCaptionTracks.kt` wraps the provider with a small, expiring disk cache of recent tracks,
  so a video reopened after Android closed the app does not download its captions again.
- `data/SubtitleMerger.kt` prepares readable segments; `SubtitleStore.kt` keeps
  transcript text/timing on disk and exposes bounded playback windows. Preserve
  bounded loading and cancellation when changing long-video behavior.
- `translation/OnDeviceTranslator.kt` uses downloadable ML Kit models with memory
  and disk caches. It lives in `app/src/full/java`; the F-Droid build
  (`-Pdistribution=fdroid`) uses `app/src/fdroid/java` without ML Kit: Mozilla's Bergamot
  engine (native, from the `app/src/fdroid/cpp` submodules) with downloadable Firefox
  Translations models. `translation/BergamotCatalog.kt` in main holds its testable model
  catalog and routing logic. See [F-Droid distribution](../fdroid/README.md). `ui/PlaybackTranslation.kt` and `TranslationCoordinator.kt`
  support playback-prioritized work; obsolete loads must not update current state.
  Both translators also list, download and remove whole languages for the
  Settings → Translation → Languages on this device screen (`ui/LanguageDownloads.kt`).
- `translation/GoogleWebTranslator.kt` is the opt-in online engine (Settings → Translation →
  Google Translate, `translation/TranslationEngine.kt`). It calls Google Translate's unofficial
  web endpoint with its own caches; `AppViewModel.translateText` routes every translation through
  the chosen engine. A failure shows `ui/OnlineTranslationNotice.kt`'s dialog and a switch-back
  button on the transcript bar. `BuildConfig.ONLINE_TRANSLATION` is false in the F-Droid build.
  See the [spec](../specs/2026-10-05-opt-in-google-translate.md).
- `data/JapaneseGrammar.kt` finds Japanese grammar points from Kuromoji morphemes with
  hand-written rules; `ui/GrammarExplanations.kt` words them (`strings_grammar.xml`) in the
  word card. Explanations are this project's own text, not copied from other grammar resources.
- `data/VocabularyRepository.kt` stores study cards/review data in local SQLite;
  `PracticeTransfer.kt` handles JSON/Anki TSV transfer. `data/ImmersionRepository.kt` keeps
  watched time per local day and language in a separate SQLite database; `ImmersionStats.kt`
  holds the testable tracking, batching and aggregation used by `ui/ProgressScreen.kt`. App preferences use Android
  SharedPreferences. `ui/WordPronouncer.kt` manages Android speech-engine fallback.
  `ui/PhraseSelection.kt` owns word/phrase selection in subtitle lines and its Copy /
  Translate / Pronounce bar; `ui/WordLearningSheet.kt` is the card Translate opens.
  `ui/PronunciationCache.kt` keeps the recorded speech of the last pronounced word only.
- `ui/AppLanguage.kt` holds the interface languages and applies the chosen one: Android 13+
  keeps it with `LocaleManager` (also shown in system settings through `res/xml/locales_config.xml`),
  older versions keep it in app preferences and apply it in `MainActivity.attachBaseContext` and
  for view model messages. `ui/AppLanguagePicker.kt` is the drawer button. Text lives in
  `res/values*/strings_<area>.xml`, one translated copy per offered language.
- `data/LanguageAwareTokenizer.kt` splits subtitle words. Japanese goes through
  `data/JapaneseMorphology.kt`, which loads Kuromoji lazily on a background thread and groups
  its morphemes into learner words; until it loads, the script-boundary heuristic is used.
  The APK excludes Kuromoji's dictionary files; `data/JapaneseDictionaryStore.kt` downloads the
  Maven artifact (pinned SHA-256) into app storage and reads the dictionary from it.

## Architecture invariants

- Keep exactly one online WebView/player. Replay and online vocabulary examples seek
  the same YouTube page video. Offline media downloading/local Media3 playback were
  removed; do not reintroduce another player as a shortcut.
- Preserve `classifyMainFrameUrl` navigation decisions and HTTPS YouTube origin checks
  inside executing JS, including asynchronous callbacks. Sign-in pages are a distinct
  navigation case, not permission to run playback scripts on arbitrary origins.
- Keep caption-provider host allowlists, response limits (currently 8 MiB), timeout
  budgets, and cancellation. Undocumented YouTube behavior belongs behind the provider
  boundary so it can change without rewriting the learning UI.
- Translation stays on-device by default; no user-provided/developer-provisioned service key is
  required. The only online engine is the opt-in Google Translate switch (off by default, never in
  the F-Droid build), which must keep offering a switch back to on-device when it fails. YouTube's internal client key handling is an extraction detail, not a
  new application API-key setup requirement.
- Preserve guide migration: a missing `guide_completed` inherits prior onboarding
  completion for existing users; new users still see the guide. Preserve vocabulary,
  review scheduling, backup/import compatibility, and retained legacy clip files.
- Preserve release/signing continuity and preview package isolation. Normal feature
  branches do not change Gradle version constants or release reservations.

## Validation and delivery boundaries

Plain JUnit4 tests exercise small `internal` functions without Robolectric or mocking
libraries. Android framework calls return defaults in local unit tests, so these
cannot establish actual Android/WebView behavior. Compose instrumentation and WebView
fixtures run offline; they do not establish live YouTube or physical-device behavior.
Choose evidence appropriate to the change in its [spec](../specs/README.md).

[Android CI](../../.github/workflows/android.yml) runs formatting/complexity checks,
unit tests, lint, APK builds, optimized-build checks, and managed-device tests.
[PR previews](../../.github/workflows/pr-preview-auto.yml) publish successful trusted
same-repository PR artifacts and clean up closed-PR previews.

After an owner merge, [release-on-main.yml](../../.github/workflows/release-on-main.yml)
and [automatic_release.py](../../tools/automatic_release.py) verify final-head PR CI,
reserve monotonic versions, and build from the exact merge via a release-only commit.
Production signature/package/version checks, APK/checksum publication, serialized
reservations and retries are existing safeguards. A skip label retains rolling preview
updates. See [release operation and recovery](../releasing.md#automatic-official-releases)
and [PR preparation](../../AGENTS.md#preparing-prs-for-owner-merge); specs do not control automation.
