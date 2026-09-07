# Reduce APK size and simplify DualSub Replay

## Agreed scope

Remove all offline video downloading and local video playback. Retain vocabulary, spaced repetition, pronunciation, on-device translation, and online examples through the existing single YouTube WebView. Preserve compatibility and all supported languages/architectures.

Primary official universal APK limit: 80,000,000 bytes. Stretch target: 40,000,000 bytes. Report APK download size separately from installed storage and downloaded translation models. Do not change versions or publish without explicit release approval.

## Baseline (2026-09-06)

- Source: `93121fd`, v0.9.7, version code 28.
- Official v0.9.7 release metadata: 244,660,852 bytes.
- Local debug APK: 254,940,118 bytes; approximately 224.9 MB compressed native libraries across four ABIs.
- ARM64 FFmpeg archive: 35.45 MB; Python archive: 14.06 MB; translation library: 7.21 MB.
- Release shrinking disabled. Local approximately 77 MB release APK is stale and must not be used as the v0.9.7 baseline.
- Issue: https://github.com/hoangkien1703/dual-sub-replay/issues/54
- Source/debug merged manifests contain no storage-write or overlay permission; inspect the actual official APK before concluding anything about OEM permission labels.

## Milestone 1: Remove offline video features

- Remove yt-dlp, FFmpeg, Media3, and direct WorkManager dependencies; backend, worker, enqueue/retry/cancel paths, local player, progress observers, storage totals, and offline UI controls.
- Keep the independent Online example choice and saving without an example. Preserve sentence boundaries and replay stopping through the existing WebView.
- Remove downloader JNI archive preservation rules; retain general native packaging initially.
- Change dialog/save interfaces to meaning and online choice only. New records contain no offline configuration. Decode legacy records without scheduling work; preserve IDs, meanings, languages, context, online choice and review history. Never enable online playback automatically on formerly offline-only cards.
- Replace download reconciliation with vocabulary loading. Cancel app-owned legacy JobScheduler jobs targeting WorkManager SystemJobService during upgrade. Verify restart/reboot cannot revive downloading.
- Do not automatically delete old user clips or databases. Disclose unused retained files in upgrade notes.
- Update README, settings/guide, architecture guidance, current notices and dependency inventory. Preserve historical notices/source links for old releases.
- Replace removed downloader tests with absence checks and legacy-record/upgrade tests.

## Milestone 2: Optimize release and enforce artifacts/permissions

- Enable release code/resource shrinking with the optimized default ProGuard file. Use consumer rules and narrow evidence-based additions only; never blanket suppress warnings or keep packages.
- Preserve universal APK, existing official filename/link, translation languages and post-install models. Keep debug convenient. Let R8 remove unused icons before considering manual replacements.
- Add reusable APK reporting: total bytes, compressed DEX/resources/assets/per-ABI totals, largest entries, package/version, permissions, signer and forbidden components.
- CI builds optimized release without production secrets; enforces 80 MB, reports 40 MB target; rejects downloader/local-player dependencies; uploads size reports and R8 mapping.
- Smoke-test an optimized test-signed release, isolated from production signing/publishing. Do not equate debug tests with release verification.
- Compare official v0.9.7 and new APK permissions with merger provenance. Remove download-only foreground/data-sync/notification declarations. Investigate transitive WorkManager before exclusions; review wake/boot permissions.
- Assert no storage-write, broad-storage, overlay or package-install permissions. Retain needed networking. In-app subtitle panels must remain Compose UI.
- Identify manufacturer/exact settings screen for OEM background-popup reports; do not infer manifest permission from label alone.
- If above 40 MB, report actual breakdown rather than silently dropping features or architectures.

## Milestone 3: Vocabulary reliability

- Replace full-table lookups for one word with parameterized primary-key queries. Preserve display order instead of replacement-insert reordering.
- Keep IO dispatching and serialized writes. Decode each row independently, retain malformed rows in storage, surface a recoverable warning, and display valid rows.
- Test repeated saves, concurrent updates, malformed rows, and 1,000–10,000-word collections. Retain SQLite; no Room migration.

## Milestone 4: Isolated maintenance and measured performance

- Separately extract settings UI, settings persistence, and WebView scripts/parsing into focused internal units. Preserve origin checks and architecture tests; do not mix broad refactors into size removal.
- Measure cold launch, memory, frames and CPU for browsing/captions/translation/practice. Check playback-driven unrelated recomposition and translation client churn before optimizing.
- Preserve lifecycle polling and media-clock progression; no mutation-only karaoke timing or reduced polling without synchronization evidence. Keep response limits, allowlists, cancellation and fallbacks.

## Verification and delivery

Run `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` and `./gradlew.bat pixel2Api36DebugAndroidTest`, plus optimized release build/smoke tests. Instrumented fixtures remain offline.

Cover absent offline UI, vocabulary CRUD/pronunciation/reviews, single-WebView bounded online replay, every legacy clip status, queued/completed upgrade state, fresh launch/restart/reboot, onboarding migration, transcript/live-caption recovery, timing modes, APK contents and permissions.

Validate ARM64 phone and API 36 emulator. Upgrade v0.9.7 with matching signer; preserve vocabulary/settings. Check lifecycle, YouTube controls/sign-in, rotation, pronunciation, subtitle timing, permissions and crashes. Manual live YouTube QA is separate from fixture tests. Record exact APK bytes.

Use `codex/` branches; no version bump, main push, merge or publication without approved release. Show rendered UI/docs and evidence before merge. Approved release retains `DualSub-Replay.apk` plus SHA-256; verify published signer/hash/size/launch/upgrade.

Complete milestones in order. Record changes, commands, results and limitations below. Never report unrun tests as passed.

## Execution log

- Milestone 1 implementation complete: removed downloader/local player, offline UI/state, and download permissions; legacy job retirement is isolated and old files are untouched.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: PASS (2026-09-06); `pixel2Api36DebugAndroidTest`: 27/27 PASS. Initial build found one stale storage-label reference, fixed before passing. PowerShell requires quoting the dotted managed-device Gradle property.
- Milestone 2: release shrinking and APK/permission gates implemented. `assembleRelease -PtestReleaseSigning=true`: PASS with consumer rules only. Universal optimized test APK: 29,696,309 bytes (below 40 MB); debug: 50,631,347 bytes.
- APK report: no forbidden components/permissions; only INTERNET, ACCESS_NETWORK_STATE and signature-scoped AndroidX dynamic-receiver permission. Test artifact has `.releasesmoke` application id and debug certificate; it is not a production release.
- Visual audit of word-definition and word-practice screenshots: retained controls readable; offline choices absent.
- Optimized UI smoke, exact official permission comparison, upgrade QA and subsequent milestones remain pending. No ARM64 phone currently connected.

- Milestone 2 follow-up: optimized onboarding/main/restart smoke PASS on Pixel_10 emulator. Official v0.9.7 SHA-256 matched `37e8b9fae6de99a1996a177406fd6daa879dc49602b65a935985a4d7887bb4e3`; actual official APK has no storage/overlay permission. New artifact removes foreground/data-sync/notifications/boot/wake permissions.
- Milestone 3: primary-key reads, order-preserving writes, incremental StateFlow publication and non-destructive per-row error recovery implemented. Full debug verification and 32/32 managed-device tests PASS on pixel2Api36 (including 1,000-row scaling, 50 concurrent updates, and legacy-job service retirement fixture).
- Milestone 4: isolated extraction of WebView JavaScript snippets and snapshot parsing into `YouTubeWebScripts.kt`. Maintained strict origin checks (`https:` and `*.youtube.com`) and single-WebView playback invariants verified by `PlaybackArchitectureTest`.
- Full verification suite:
  - `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: PASS (85 tasks executed cleanly, 0 lint errors).
  - `.\gradlew.bat pixel2Api36DebugAndroidTest`: 32/32 PASS on API 36 managed device.
  - Release APK inspection (`tools/report_apk.py`): 29,696,121 bytes (28.32 MB) — PASSES 80 MB limit and 40 MB stretch target. Zero forbidden permissions or native components.

