# Contributing to DualSub Replay

Thank you for helping make language learning on Android more accessible.

## Before you start

- Search existing issues and pull requests to avoid duplicate work.
- For a bug, include the Android version, app version, video URL when it is safe to share, caption languages, and reproducible steps.
- For a larger feature or architecture change, open an issue first so the approach can be discussed.
- Never include credentials, signing files, personal YouTube data, or copyrighted video/caption dumps.

## Local setup

Requirements: JDK 17, Android SDK 36, and an Android Studio version compatible with Android Gradle Plugin 9.3. The Gradle 9.5 wrapper is included.

Run the CI-parity checks from PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Managed-device tests additionally require the API 36 AOSP x86_64 system image:

```powershell
.\gradlew.bat pixel2Api36DebugAndroidTest
```

## Pull requests

- Keep each pull request focused and explain the user-facing impact.
- Add or update tests for changed behavior.
- Preserve the single-WebView playback architecture and the origin checks around JavaScript playback bridges.
- Do not bump `versionName` or `versionCode` unless the pull request intentionally prepares a release.
- Include screenshots or a short recording for visible UI changes.
- Confirm that the verification commands pass and complete the pull request template.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
