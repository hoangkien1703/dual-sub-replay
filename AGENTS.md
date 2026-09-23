# AGENTS.md

## Project

Android app (`:app`, package `com.kienhoang.dualsubreplay`) with a separate test-only `:benchmark` module: Kotlin 2.3.21, Jetpack Compose / Material 3, AGP 9.3.2, committed Gradle 9.5 wrapper (no local Gradle needed), JDK 17, compile/target SDK 36, minSdk 26.

## Commands (Windows)

```powershell
.\gradlew.bat formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest   # CI parity
.\gradlew.bat pixel2Api36DebugAndroidTest                                          # instrumented tests on managed device
.\gradlew.bat testDebugUnitTest --tests "com.kienhoang.dualsubreplay.data.SubtitleMergerTest"   # one test class
```

- Managed-device tests require the API 36 AOSP x86_64 system image. On headless hosts add `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (CI also passes `--no-parallel --max-workers=2`).
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Releases happen automatically after an approved PR merge

- When `hoangkien1703` merges a PR into `main`, `release-on-main.yml` checks the latest Android CI run for the PR's final head and requires both `verify-build` and `managed-device-tests` to succeed. Direct pushes and other mergers do not release.
- Default: bump the highest existing/reserved stable patch version and monotonically increase Android `versionCode`. **Do not manually bump Gradle version constants in feature PRs.**
- Before merge, use one of `release:patch`, `release:minor`, `release:major`, `release:skip`, or a standalone `Release-Version: X.Y.Z` in the PR description. Conflicting instructions fail closed. `release:skip` keeps the rolling preview only.
- Release versions live in a release-only commit/tag based on the exact merge; only the two Gradle version constants change. `main` keeps development defaults. No bot commits to `main` or branch-protection bypass is needed.
- Draft release metadata reserves version/name/code/source before building. Retry failed runs (or dispatch with a merged PR number) to resume that reservation. Never delete reservations or edit their hidden marker to retry. Published merges are a no-op.
- Production signature, package, and version are verified before upload; the draft is published only with both APK and checksum present. Release notes are generated automatically. Queued runs cannot allocate the same version, and older retries cannot replace a newer Latest release.
- The rolling preview artifact name remains `DualSub-Replay-preview.apk`.
- Release builds require the four `ANDROID_RELEASE_*` env vars plus `-PrequireReleaseSigning=true`. Never commit signing material (`*.jks`/`*.keystore` are gitignored).
- Test workflow changes with `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` and validate workflow YAML/shell. These tests also run in Android PR CI.

## Architecture invariants (enforced by tests)

- Exactly one WebView exists (`SingleYouTubePage` in `ui/YouTubeBrowserScreen.kt`). Online replay seeks the native YouTube page video via a JS polling bridge. Offline video downloading and local Media3 playback are removed; never add a second online player/WebView.
- Main-frame navigation goes through `classifyMainFrameUrl` → `YOUTUBE_WEB` (embed) / `GOOGLE_SIGN_IN` (embed during sign-in flow) / `OPEN_EXTERNAL` (browser) / `BLOCK`. JS snapshot/replay scripts must keep re-verifying the executing origin (`https:` + `*.youtube.com`); `PlaybackArchitectureTest` asserts the literal script text.
- Captions use YouTube's undocumented Innertube transcript endpoint, deliberately isolated in `data/YouTubeCaptionProvider.kt` (host allowlist, 8 MiB response cap) so it can be replaced without touching the rest of the app.
- Translation is on-device via ML Kit (`translation/OnDeviceTranslator.kt`); the app has no API keys.
- First-launch flow is `OnboardingScreen` → `GuideScreen` → main experience. Preserve the guide migration behavior: if `guide_completed` is absent, users who already completed onboarding are treated as guide-complete, while brand-new users see the guide. Do not simplify this to `getBoolean("guide_completed", false)` or existing users will see the guide after upgrading.

## Testing conventions

- Unit tests are plain JUnit4 — no Robolectric or mocking library. They call `internal` top-level functions directly (same package), so keep new logic in small testable `internal` functions. `org.json` is a unit-test-only dependency.
- `unitTests.isReturnDefaultValues = true`: Android framework calls return defaults instead of throwing in unit tests.
- Instrumented tests build Compose UI in isolation (`createComposeRule`) and WebView fixtures run offline — tests never hit the live YouTube site.
