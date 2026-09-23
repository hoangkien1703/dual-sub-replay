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
| Tests | JUnit4 4.13.2; Compose/Android instrumentation, API 36 AOSP x86_64 managed Pixel 2 | [App test configuration](../../app/build.gradle.kts) |
| Performance | Separate test-only `:benchmark` module, Macrobenchmark and app Baseline Profile | [Benchmark build](../../benchmark/build.gradle.kts), [methodology](../quality-and-performance.md) |

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
- `data/SubtitleMerger.kt` prepares readable segments; `SubtitleStore.kt` keeps
  transcript text/timing on disk and exposes bounded playback windows. Preserve
  bounded loading and cancellation when changing long-video behavior.
- `translation/OnDeviceTranslator.kt` uses downloadable ML Kit models with memory
  and disk caches. `ui/PlaybackTranslation.kt` and `TranslationCoordinator.kt`
  support playback-prioritized work; obsolete loads must not update current state.
- `data/VocabularyRepository.kt` stores study cards/review data in local SQLite;
  `PracticeTransfer.kt` handles JSON/Anki TSV transfer. App preferences use Android
  SharedPreferences. `ui/WordPronouncer.kt` manages Android speech-engine fallback.

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
- Translation stays on-device; no user-provided/developer-provisioned service key is
  required. YouTube's internal client key handling is an extraction detail, not a
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
updates. See [release operation and recovery](../../README.md#automatic-official-releases)
and [PR preparation](../../AGENTS.md#preparing-prs-for-owner-merge); specs do not control automation.
