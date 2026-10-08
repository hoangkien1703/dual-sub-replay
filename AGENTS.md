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
5. Update relevant permanent docs in the same PR when an authorized decision changes architecture or conventions. Keep ROADMAP.md directional and each implementation plan inside its feature spec; keep QA evidence in docs/qa/.
6. Open a focused PR against `main` linking the spec (or explaining why none is needed). Open it ready for review, never as a draft: the owner wants to test and merge without extra GitHub steps, and marking a draft ready restarts CI. The owner remains final merge authority; do not merge, enable auto-merge, or publish releases unless explicitly instructed.

## Preparing PRs for owner merge

- Read `.github/workflows/release-on-main.yml` and `tools/automatic_release.py` before handoff; they define actual automation. Use the [release-intent mapping](docs/specs/README.md#release-intent-is-a-decision-not-automation).
- Follow explicit owner release intent; otherwise preserve **patch**, including workflow PRs. Recommend alternatives if useful, but do not silently infer minor/major. **Standing owner decision (2026-10-04): apply `release:skip` without asking when every changed file is documentation or promo media** (`*.md`, `docs/**`, `site/**`, `tools/promo-video/**`, `.github/ISSUE_TEMPLATE/**`, `.github/pull_request_template.md`). If any other file changes (app or test code, Gradle, `fastlane/`, workflows, release tools) or you are unsure, keep patch and ask.
- Apply and verify the actual GitHub label or standalone exact-version PR-body directive. Prose/specs/checkboxes do not apply labels. Keep at most one release label OR one directive; remove superseded overrides without disturbing unrelated labels. If required metadata cannot be applied, report the missing action and do not call the PR ready to merge.
- State intent, reason, and expected version (or why skipped/unavailable). Inspect stable tags, releases including draft reservations, and the Gradle baseline before estimating. Bump-derived numbers are estimates until reserved; exact versions must exceed all existing/reserved stable versions. Do not manually change Gradle versions or create release tags in normal PRs.
- When the final-head CI passes, send the owner the preview release page link, https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview, so they can test on a phone before merging. **Never send a direct `.apk` download link** (owner decision 2026-10-08): the owner can't download from it and downloads the APK from the release page themselves. `pr-preview-auto.yml` uploads it to that rolling `preview` release as `DualSub-Replay-PR<N>-build<run>-preview.apk`, so name the asset to pick; a newer build of the same PR replaces the older asset.
- Before calling a PR ready to merge, verify its latest final-head Android CI run: all four jobs (`verify-build`, `managed-device-tests`, `fdroid-build`, `fdroid-device-tests`) must succeed. New commits need fresh checks; report pending/failed/skipped/unavailable checks explicitly.
- Handoff: owner reviews the preview and green checks, then manually merges; existing automation publishes unless skipped. Green PR CI is not proof of release publication. Verify the release workflow/assets if asked to confirm publication; use existing reservation/retry recovery below.

## Releases happen automatically after an approved PR merge

- When `hoangkien1703` merges a PR into `main`, `release-on-main.yml` checks the latest Android CI run for the PR's final head and requires all four Android CI jobs (`verify-build`, `managed-device-tests`, `fdroid-build`, `fdroid-device-tests`) to succeed. Direct pushes and other mergers do not release.
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
- In the GitHub build, translation uses Google Translate's unofficial web endpoint by default (`translation/GoogleWebTranslator.kt`, owner decision 2026-10-05); Settings → Translation switches to on-device ML Kit (`app/src/full/java/.../translation/OnDeviceTranslator.kt`). No user-provided/developer-provisioned service key is ever required. When Google still fails after its retries (1 s, 3 s, 7 s; none for a block), that video quietly translates on-device, a top-right icon explains why, and Google is checked again every 2 minutes and on every load. The F-Droid build has no online engine (`BuildConfig.ONLINE_TRANSLATION`). See the [Google Translate spec](docs/specs/2026-10-05-opt-in-google-translate.md). `-Pdistribution=fdroid` swaps in `app/src/fdroid/java` and drops ML Kit so F-Droid can build it; it translates with Mozilla's Bergamot engine, built with NDK r28c from the `app/src/fdroid/cpp` submodules. Keep every proprietary dependency out of that variant (the `fdroid-build` CI job checks the APK). See [docs/fdroid](docs/fdroid/README.md).
- The GitHub build's AI assistant (`assistant/`, `ui/AiAssistant*.kt`, owner decision 2026-10-08) is the only feature that uses the user's own API key, for Gemini, OpenRouter, OpenAI, OpenCode Zen/Go, or an OpenAI-compatible HTTPS address. It is on by default, sends nothing until a key is added and a question asked, and More settings → AI assistant turns it off completely (the translation problem icon then returns). Keys are AES-GCM encrypted with an Android Keystore key in `ai_assistant_keys` preferences; keys and `files/ai-chats/` stay excluded from backups (`BackupRulesTest`). Chats are kept 7 days by default (Off / 7 / 30 days / Forever). Subtitle and error text, and attached text files, go to the model as quoted data; attached pictures and files stay in memory and are never written to chat history. Never add a project server or shared key. The assistant acts only through the fixed tool list in `assistant/AiActions.kt` (owner decision 2026-10-08): small setting changes, playback, speed and Save to vocabulary run at once with an Undo chip; YouTube search, opening a link the user typed, and translation changes wait for the user's tap, and so do setting changes after subtitles, files or error text were read. It can never touch keys, the AI settings, or saved words. The F-Droid build has no assistant (`BuildConfig.AI_ASSISTANT`). See the [AI assistant spec](docs/specs/2026-10-08-ai-assistant-panel.md) and the [actions spec](docs/specs/2026-10-08-ai-assistant-actions.md).
- User-visible text lives in `app/src/main/res/values/strings_<area>.xml` (English, the default) with the same file in every translated `values-*` folder; never add a UI string literal in Kotlin. The offered interface languages are `APP_LANGUAGES` in `ui/AppLanguage.kt` and `res/xml/locales_config.xml`; with no saved choice the app follows the device. `AppLanguageTest` and lint `MissingTranslation` fail when a language lacks a string or a placeholder differs. Diagnostic exception text in `data/` and `translation/` stays English. See the [interface languages spec](docs/specs/2026-10-02-app-interface-languages.md).
- First-launch flow is `LanguageSetupScreen` → `GuideScreen` → main experience. Preserve the guide migration behavior: if `guide_completed` is absent, users who already completed onboarding are treated as guide-complete, while brand-new users see the guide. Do not simplify this to `getBoolean("guide_completed", false)` or existing users will see the guide after upgrading.

## UI conventions

- Every screen, panel, dialog, sheet and overlay uses the app theme: `DualSubTheme` (dark teal with the user's accent, `ui/theme/Theme.kt`). UI drawn outside `DualSubApp`, such as overlays that `LearningPlayerRoot` places beside it, wraps itself in `DualSubTheme`; otherwise Material's light purple defaults show (`AiAssistantPanelUiTest` checks the assistant panel's color).
- Take colors from `MaterialTheme.colorScheme` roles, never from Material's defaults or new hex literals. If a component needs a role that `dualSubColorScheme` leaves at Material's purple default (for example `tertiary`), set that role in `Theme.kt` instead of coloring the component directly. The amber/red problem tints in `OnlineTranslationNotice.kt` and `AiAssistantUi.kt` are the only shared status colors.
- Before handing off a UI change, look at it in the screenshots the Compose tests save (`saveUiEvidence`) or on a device, and check that it matches the screens around it.

## Testing conventions

- Unit tests are plain JUnit4 — no Robolectric or mocking library. They call `internal` top-level functions directly (same package), so keep new logic in small testable `internal` functions. `org.json` is a unit-test-only dependency.
- `unitTests.isReturnDefaultValues = true`: Android framework calls return defaults instead of throwing in unit tests.
- Instrumented tests build Compose UI in isolation (`createComposeRule`) and WebView fixtures run offline — tests never hit the live YouTube site.
