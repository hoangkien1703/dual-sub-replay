# AGENTS.md

## Project

Single-module Android app (`:app`, package `com.kienhoang.dualsubreplay`): Kotlin 2.3.21, Jetpack Compose / Material 3, AGP 9.3.2, committed Gradle 9.5 wrapper (no local Gradle needed), JDK 17, compile/target SDK 36, minSdk 26.

## Commands (Windows)

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest   # full local verification (CI parity)
.\gradlew.bat pixel2Api36DebugAndroidTest                                          # instrumented tests on managed device
.\gradlew.bat testDebugUnitTest --tests "com.kienhoang.dualsubreplay.data.SubtitleMergerTest"   # one test class
```

- Managed-device tests require the API 36 AOSP x86_64 system image. On headless hosts add `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` (CI also passes `--no-parallel --max-workers=2`).
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Releases happen automatically on push to `main`

- Every push to `main` publishes the rolling `preview` GitHub release (debug-signed) after both CI jobs pass.
- If `versionName` in `app/build.gradle.kts` has no matching `v<version>` tag, `release-on-main.yml` also publishes an official production-signed release. **Do not bump `versionName`/`versionCode` unless a release is intended.**
- The preview APK name (`DualSub-Replay-v<version>-preview.apk`) is hardcoded in several places in `.github/workflows/android.yml` (copy step, artifact upload/download, release body) — update all of them when bumping the version; CI does not derive it.
- A pushed tag `vX.Y.Z` must equal `versionName` or CI fails.
- Release builds require the four `ANDROID_RELEASE_*` env vars plus `-PrequireReleaseSigning=true` (the build throws otherwise). Never commit signing material (`*.jks`/`*.keystore` are gitignored).

## Architecture invariants (enforced by tests)

- Exactly one WebView exists (`SingleYouTubePage` in `ui/YouTubeBrowserScreen.kt`). Online replay seeks the native YouTube page video via a JS polling bridge. Saved offline clips may use a lifecycle-managed Media3 player for app-private local files; pause the YouTube player before local playback and never add a second online player/WebView.
- Main-frame navigation goes through `classifyMainFrameUrl` → `YOUTUBE_WEB` (embed) / `GOOGLE_SIGN_IN` (embed during sign-in flow) / `OPEN_EXTERNAL` (browser) / `BLOCK`. JS snapshot/replay scripts must keep re-verifying the executing origin (`https:` + `*.youtube.com`); `PlaybackArchitectureTest` asserts the literal script text.
- Captions use YouTube's undocumented Innertube transcript endpoint, deliberately isolated in `data/YouTubeCaptionProvider.kt` (host allowlist, 8 MiB response cap) so it can be replaced without touching the rest of the app.
- Translation is on-device via ML Kit (`translation/OnDeviceTranslator.kt`); the app has no API keys.
- First-launch flow is `OnboardingScreen` → `GuideScreen` → main experience. Preserve the guide migration behavior: if `guide_completed` is absent, users who already completed onboarding are treated as guide-complete, while brand-new users see the guide. Do not simplify this to `getBoolean("guide_completed", false)` or existing users will see the guide after upgrading.

## Testing conventions

- Unit tests are plain JUnit4 — no Robolectric or mocking library. They call `internal` top-level functions directly (same package), so keep new logic in small testable `internal` functions. `org.json` is a unit-test-only dependency.
- `unitTests.isReturnDefaultValues = true`: Android framework calls return defaults instead of throwing in unit tests.
- Instrumented tests build Compose UI in isolation (`createComposeRule`) and WebView fixtures run offline — tests never hit the live YouTube site.
