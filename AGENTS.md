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

## Development workflow

1. Before significant work, read [mission](docs/project/mission.md), [technical context](docs/project/tech-stack.md), [ROADMAP.md](ROADMAP.md), relevant specs, and relevant source/tests. Stable intent belongs in the repository, not only chat history.
2. Create/update a [feature spec](docs/specs/README.md) with acceptance criteria, a small plan, and validation scenarios **before coding**. New features, significant/risky fixes, architecture, migrations, CI/release changes, and measurable performance work need specs; trivial changes need only a clear PR and acceptance criteria.
3. Record release intent before implementation where possible and resolve it before opening the PR. Existing owner authorization covers the agreed implementation; do not add a redundant approval round or invent approval. Ask about unresolved material scope/product decisions.
4. Implement the smallest coherent solution and validate against the criteria. Record actual implementation, deviations, and passed/failed/unverified checks in the spec. Offline tests do not establish physical-device or live-YouTube success.
5. Update relevant permanent docs in the same PR when an authorized decision changes architecture or conventions. Keep ROADMAP.md directional; PLAN.md is historical, including its outstanding QA.
6. Open a focused PR against `main` linking the spec (or explaining why none is needed). The owner remains final merge authority; do not merge, enable auto-merge, or publish releases unless explicitly instructed.

## Preparing PRs for owner merge

- Read `.github/workflows/release-on-main.yml` and `tools/automatic_release.py` before handoff; they define actual automation. Use the [release-intent mapping](docs/specs/README.md#release-intent-is-a-decision-not-automation).
- Follow explicit owner release intent; otherwise preserve **patch**, including docs/workflow PRs. Recommend alternatives if useful, but do not silently infer minor/major/skip. A docs-only PR uses skip when the owner requests it.
- Apply and verify the actual GitHub label or standalone exact-version PR-body directive. Prose/specs/checkboxes do not apply labels. Keep at most one release label OR one directive; remove superseded overrides without disturbing unrelated labels. If required metadata cannot be applied, report the missing action and do not call the PR ready to merge.
- State intent, reason, and expected version (or why skipped/unavailable). Inspect stable tags, releases including draft reservations, and the Gradle baseline before estimating. Bump-derived numbers are estimates until reserved; exact versions must exceed all existing/reserved stable versions. Do not manually change Gradle versions or create release tags in normal PRs.
- Before calling a PR ready to merge, verify its latest final-head Android CI run: both `verify-build` and `managed-device-tests` must succeed. New commits need fresh checks; report pending/failed/skipped/unavailable checks explicitly.
- Handoff: owner reviews the preview and green checks, then manually merges; existing automation publishes unless skipped. Green PR CI is not proof of release publication. Verify the release workflow/assets if asked to confirm publication; use existing reservation/retry recovery below.

## Releases happen automatically after an approved PR merge

- When `hoangkien1703` merges a PR into `main`, `release-on-main.yml` checks the latest Android CI run for the PR's final head and requires both `verify-build` and `managed-device-tests` to succeed. Direct pushes and other mergers do not release.
- Default: bump the highest existing/reserved stable patch version and monotonically increase Android `versionCode`. **Do not manually bump Gradle version constants in feature PRs.**
- Before merge, apply at most one GitHub label (`release:patch`, `release:minor`, `release:major`, `release:skip`) OR a standalone `Release-Version: X.Y.Z` in the PR description. Conflicting instructions fail closed. `release:skip` keeps the rolling preview only.
- Release versions live in a release-only commit/tag based on the exact merge; only the two Gradle version constants change. `main` keeps development defaults. No bot commits to `main` or branch-protection bypass is needed.
- Draft release metadata reserves version/name/code/source before building. Retry failed runs (or dispatch with a merged PR number) to resume that reservation. Never delete reservations or edit their hidden marker to retry. Published merges are a no-op.
- Production signature, package, and version are verified before upload; the draft is published only with both APK and checksum present. Release notes are generated automatically. Queued runs cannot allocate the same version, and older retries cannot replace a newer Latest release.
- The rolling preview artifact name remains `DualSub-Replay-preview.apk`.
- Release builds require the four `ANDROID_RELEASE_*` env vars plus `-PrequireReleaseSigning=true`. Never commit signing material (`*.jks`/`*.keystore` are gitignored).
- Test workflow changes with `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` and validate workflow YAML/shell. These tests also run in Android PR CI.

## Architecture invariants (enforced by tests)

- Exactly one WebView exists (`SingleYouTubePage` in `ui/YouTubeBrowserScreen.kt`). Online replay seeks the native YouTube page video via a JS polling bridge. Offline video downloading and local Media3 playback are removed; never add a second online player/WebView.
- Main-frame navigation goes through `classifyMainFrameUrl` → `YOUTUBE_WEB` (embed) / `GOOGLE_SIGN_IN` (embed during sign-in flow) / `OPEN_EXTERNAL` (browser) / `BLOCK`. JS snapshot/replay scripts must keep re-verifying the executing origin (`https:` + `*.youtube.com`); `PlaybackArchitectureTest` asserts the literal script text.
- Caption discovery uses watch-page metadata and undocumented Innertube player fallbacks, then timed-text downloads, isolated behind `data/CaptionProvider.kt` / `YouTubeCaptionProvider.kt` (host allowlist, 8 MiB response cap). Keep that replaceable boundary.
- Translation is on-device via ML Kit (`translation/OnDeviceTranslator.kt`); no user-provided/developer-provisioned service key is required.
- First-launch flow is `LanguageSetupScreen` → `GuideScreen` → main experience. Preserve the guide migration behavior: if `guide_completed` is absent, users who already completed onboarding are treated as guide-complete, while brand-new users see the guide. Do not simplify this to `getBoolean("guide_completed", false)` or existing users will see the guide after upgrading.

## Testing conventions

- Unit tests are plain JUnit4 — no Robolectric or mocking library. They call `internal` top-level functions directly (same package), so keep new logic in small testable `internal` functions. `org.json` is a unit-test-only dependency.
- `unitTests.isReturnDefaultValues = true`: Android framework calls return defaults instead of throwing in unit tests.
- Instrumented tests build Compose UI in isolation (`createComposeRule`) and WebView fixtures run offline — tests never hit the live YouTube site.
