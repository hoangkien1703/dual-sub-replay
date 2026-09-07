# DualSub Replay: issues #56–#59 and code quality

## Approved scope

Implement on a codex feature branch, preserving other local work. Do not add Mem0,
change release versions, merge, publish, or close issues before verification/review.

## #56 — Practice backup and Anki interoperability

- Add Import/Export through Android's system file picker.
- Versioned JSON preserves every SavedWord field, examples, YouTube references,
  timing and review schedule. Materials means saved learning content, not videos
  or transient browsing sessions.
- UTF-8 Anki TSV with escaped fields, column mapping (word/meaning defaults),
  language selection, and documented exclusions: scheduling, media, templates.
- Preview valid/invalid/duplicate counts. Keep existing duplicates by default;
  allow replacement. Backup identity uses ID; TSV uses word/languages/context.
- Bounded parsing, cancellation, version validation, idempotent repeat imports,
  and a single transaction for valid records.

## #57 — More natural on-device translations

- Keep ML Kit and downloadable models, with no API keys.
- Improve sentence segmentation using punctuation, caption boundaries and word
  timing; retain source timing and never invent translated word alignment.
- Natural mode translates coherent source units; raw mode preserves cue behavior.
- Bounded exact-text/language-pair cache; preserve playback-prioritized work.
- Reject all stale callbacks, including model-download status.
- Fixed bilingual before/after sample evaluation; document ML Kit limitations.

## #58 — Independent caption visibility

- Persist Always / Only when paused / Never independently for both tracks;
  default both to Always.
- Share visibility across portrait and overlays; use actual paused state,
  not buffering. Hide empty containers but retain controls.
- Keep capture and timing running when text is hidden.

## #59 — Fullscreen spoken-word highlighting

- Reproduce with identical timed fixtures; inspect timestamps, callbacks,
  lifecycle and fullscreen state propagation.
- Share current playback/karaoke state; reject stale samples and stale captures.
- Preserve all timing modes; no arbitrary offsets or faster polling.
- Verify seek, speed, pause, rotation, fullscreen and rolling/static captions.

## Quality and performance

- Before/after optimized startup, frames, memory, captions, scrolling and APK size.
- Extract focused presentation, translation and Practice transfer components.
- Add compatible formatting/complexity ratchets without unrelated churn.
- Repeatable startup/scroll benchmarks and app Baseline Profile, with benchmark
  dependencies outside the production app.
- Same-device comparisons; investigate repeatable >10% startup/memory regression.
- Preserve one WebView, origin checks, online-only video, vocabulary compatibility
  and onboarding/guide migration.

## Verification

- JSON/TSV round trips, Unicode/escaping/mapping/duplicates/malformed/cancelled
  imports, transaction rollback and upgrade persistence.
- Translation segmentation/cache/cancellation/failure/stale callbacks.
- All nine visibility combinations and fullscreen/portrait timing equivalence.
- Unit tests, lint, debug builds, managed-device tests, optimized release and
  APK size/component gates; screenshots and Anki text round trip.
- Physical-device performance/live YouTube timing remain pending without a device;
  emulator evidence cannot satisfy these gates.
- Deliver focused commits and local reviewable diff; record evidence below.

## Execution log

- Implemented on `codex/issues-56-59-quality`; preserved the initial local work
  and kept version 0.9.8 / code 29 unchanged. Mem0 was not added.
- #56: JSON/Anki TSV parsing, validation, mapping, preview, duplicate policy,
  cancellation and atomic import implemented. TSV replacement preserves existing
  review scheduling; JSON restores the backup's scheduling. Real Anki 26.8.1
  backend round trip passed. Android system-picker export produced a 500-word
  JSON file; re-import showed 500 valid / 0 invalid / 500 duplicates and added 0
  words under the default policy. [Usage](docs/practice-transfer.md).
- #57: coherent source translation units now survive display splitting; raw mode
  retains cue translation. Added bounded exact-text cache, playback priority and
  stale download/progress guards. Six real optimized-build ML Kit before/after
  samples completed. Context improves some output, but idioms and nuance remain
  imperfect. Release QA also found and fixed ML Kit registrar constructors being
  removed by R8. [Sample evaluation](docs/qa/issues-56-59/translation-evaluation.json).
- #58: independent persisted track modes, shared policy, actual media paused
  state, hidden-container controls and continued capture implemented. All nine
  combinations tested; a device test verifies live preference updates and
  persistence after ViewModel recreation.
- #59: current media time replaces stale cached frame-callback time for caption
  samples; callback tickets reject obsolete pages/lifecycle requests. Both
  presentations use the shared playback/highlight state and cached token spans.
  Frozen-frame and identical-presentation fixtures pass. No timing offsets or
  polling-frequency changes. Live perceptual acceptance remains pending.
- Extracted Practice transfer, caption presentation/card, and translation
  coordination components. Added formatting/complexity ratchets against original
  debt, a separate benchmark module, and a generated app-specific Baseline Profile.
  [Commands and methodology](docs/quality-and-performance.md).

### Verification completed locally

- 199 unit tests passed; Android Lint, formatting and complexity checks passed.
- Full API 36 connected-device suite: 36/36 passed. Additional persistence test:
  1/1 passed. Updated synchronized caption screenshot test: 1/1 passed.
  Earlier managed-device suite: 35/35 passed before the added cancellation test.
- Debug and AndroidTest APKs built; optimized release and benchmark APKs built.
- Three Macrobenchmark journeys passed (five iterations each); one generated
  Baseline Profile journey passed. Emulator measurements are harness evidence.
- Optimized local APK: **29,728,445 bytes**, +32,140 bytes (+0.108%) vs original.
  APK size/permission/component gates passed; compiled profile is packaged and
  the benchmark activity is absent from the production variant. Signature verified
  as Android Debug; package is isolated as `.qualityqa`, not a production release.
  SHA-256: `6fbc0a20dab1bac52821eb4668d5c9fa5ba92b53e744fc1322cdd5378275ab07`.
- Controlled same-emulator startup: 681 ms before, 740 ms first after, 686 ms
  repeated after (+0.73%). Startup PSS: 26,587 KiB before, 29,733 KiB repeated
  after (+11.83%, +3.1 MiB). The memory threshold is exceeded and remains open.
  Snapshots show most of the difference in Java/native heap; its ownership has
  not been isolated. The original uncontrolled measurements are retained too.
- [Evidence, screenshots and performance details](docs/qa/issues-56-59/README.md).

### Remaining acceptance gates

- No physical Android device is connected. Physical startup/frame/extended-playback
  memory profiling and live caption timing acceptance remain pending. Specifically
  investigate the reproducible startup PSS increase before release approval.
- Automatic approval review rejected launching the live YouTube video in the
  emulator with “blocked by policy,” without a more specific reason. Live YouTube
  QA, real fullscreen transitions/rotation and perceptual timing remain pending.
- No same-device prechange scrolling/extended-live-playback comparison is claimed;
  the new offline benchmarks establish repeatable journeys for that work.
- Merge, publishing and issue closure are not performed. Review the local commits
  and resolve the acceptance gates before release promotion.
