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

- Keep each pull request to one focused change, explain the user-facing impact, and link its spec (or state why a spec is not required). Record actual implementation and validation results against acceptance criteria. Update lasting project decisions in the relevant documentation in the same PR.
- When you open a pull request from a fork, tick **Allow edits by maintainers** (GitHub shows it beside the Create pull request button, and in the PR sidebar afterwards). The maintainer rarely uses it, but it lets them merge `main` into your branch or fix a typo instead of waiting a day for a one-line change.
- Add or update tests for changed behavior.
- Preserve the single-WebView playback architecture and the origin checks around JavaScript playback bridges.
- Do not manually bump `appVersionName` / `appVersionCode` in normal PRs. Say in the PR template which release you expect (see the [release-intent mapping](docs/specs/README.md#release-intent-is-a-decision-not-automation)); the owner applies the actual GitHub label or exact-version directive, so do not add, change or remove release labels yourself. A label mentioned only in prose has no effect.
- Include screenshots or a short recording for visible UI changes, and say whether you tried it on a real phone (and which one).
- Complete the PR template with relevant commands/scenarios and honest results; distinguish not run from passed. Documentation-only edits need appropriate link/consistency checks, not claims of runtime testing. Existing PR CI still applies.
- If an AI agent wrote or changed the code, say so in the PR and point it at [AGENTS.md](AGENTS.md#ai-agents-opening-or-updating-pull-requests).

## How review works

The maintainer's job is to review: check the code, security and privacy, try the build on a phone, and decide. Fixing the code is the contributor's job.

1. **CI runs first.** On your first pull request the owner has to approve the workflow before CI starts, so a short wait is normal. Both Android CI jobs (`verify-build`, `managed-device-tests`) must pass. If one fails, the job summary lists the failed tests; please fix them yourself. Once CI is green, every pull request, from a fork too, gets a test APK named `DualSub-Replay-PR<number>-build<run>-preview.apk` on the [`preview` release](https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview).
2. **The maintainer reviews and suggests.** Expect comments that explain what to change and why, sometimes with a suggested change you can apply in one click. The maintainer does not usually rewrite your pull request or open a competing one.
3. **You update your pull request.** Push new commits to the same branch (please avoid force-pushing once review has started, so the reviewer can see what changed). Answer every comment, either with a fix or with a reply explaining why not; disagreement is welcome when you give a reason. Let the reviewer resolve their own comments.
4. **Repeat until everything is good.** When review is done, CI is green on the latest commit and the app works on a phone, the owner merges. Merging is always the owner's decision; nobody else merges, enables auto-merge or publishes releases.

The maintainer may push to your branch only for small mechanical things (merging `main`, resolving a conflict, a typo), and will say so in a comment.

Security problems go through a [private report](SECURITY.md), not a pull request or a public issue.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
