# Contributing to DualSub Replay

Thank you for helping make language learning on Android more accessible.

## Before you start

- Search existing issues and pull requests to avoid duplicate work.
- For a bug, include the Android version, app version, video URL when it is safe to share, caption languages, and reproducible steps.
- Read [AGENTS.md](AGENTS.md), the [project mission](docs/project/mission.md), and [technical context](docs/project/tech-stack.md). For significant changes, follow the [spec workflow](docs/specs/README.md): define acceptance criteria, a plan, validation, and release intent before coding. Discuss unresolved larger scope in an issue; an already-authorized change does not require another approval round.
- Never include credentials, signing files, personal YouTube data, or copyrighted video/caption dumps.

## Local setup

Requirements: JDK 17, Android SDK 36, and an Android Studio version compatible with Android Gradle Plugin 9.3. The Gradle 9.5 wrapper is included.

Run the CI-parity checks from PowerShell:

```powershell
.\gradlew.bat formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Managed-device tests additionally require the API 36 AOSP x86_64 system image:

```powershell
.\gradlew.bat pixel2Api36DebugAndroidTest
```

## Pull requests

- Keep each pull request focused, explain the user-facing impact, and link its spec (or state why a spec is not required). Record actual implementation and validation results against acceptance criteria. Update lasting project decisions in the relevant documentation in the same PR.
- Add or update tests for changed behavior.
- Preserve the single-WebView playback architecture and the origin checks around JavaScript playback bridges.
- Do not manually bump `appVersionName` / `appVersionCode` in normal PRs. Follow the [release-intent mapping](docs/specs/README.md#release-intent-is-a-decision-not-automation): apply and verify the actual GitHub label or exact-version directive before handoff. A label mentioned only in prose has no effect; report missing metadata explicitly.
- Include screenshots or a short recording for visible UI changes.
- Complete the PR template with relevant commands/scenarios and honest results; distinguish not run from passed. Documentation-only edits need appropriate link/consistency checks, not claims of runtime testing. Existing PR CI still applies.
- The owner reviews the preview and successful final-head CI before manually merging. Do not merge, enable auto-merge, or publish without an explicit owner request; existing automation handles releases after eligible merges.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
