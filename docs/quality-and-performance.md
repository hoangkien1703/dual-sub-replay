# Quality and performance checks

Run local CI checks with the committed wrapper:

```powershell
.\gradlew.bat formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2
.\gradlew.bat pixel2Api36DebugAndroidTest --no-parallel --max-workers=2
```

Formatting uses standalone ktlint 1.7.1; complexity uses detekt CLI 1.23.8 without
a Kotlin compiler plugin dependency. Existing formatting debt is a per-file,
per-rule count budget in `config/quality/format-budget.json`, captured from the
prechange commit. New files have zero budget. Fixes may reduce counts; avoid
raising budgets to hide new violations. Complexity checks baseline existing
oversized methods and enforce cyclomatic complexity 25 / method length 100 on
new work. The two existing UI signatures changed with new callbacks, so their
baseline signatures were updated without accepting newly complex methods.

`formatNewCode -PformatFiles=path/to/file.kt,...` formats only selected files.
Baseline recording tasks are maintenance tools, not part of normal CI; use
`-PanalysisRoot=...` against a reviewed original checkout when regenerating.

## Benchmarks and profile

`:benchmark` contains Macrobenchmark and UiAutomator dependencies. Its target
is a separate, release-optimized, debug-signed `.benchmark` application. A
benchmark-only activity supplies 500 deterministic Practice words and subtitle
rows without network traffic or altering the user's production database. These
fixtures and the exported benchmark activity are absent from production APKs.

```powershell
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.kienhoang.dualsubreplay.benchmarktests.AppBenchmarks' --max-workers=2
```

Use a physical device for acceptance. Emulator runs require the explicit
`androidx.benchmark.suppressErrors=EMULATOR` instrumentation argument and only
establish that the benchmark harness works. Startup and scrolling each use five
iterations. Practice and subtitle scrolling report frame timings separately.

The app-specific `app/src/main/baseline-prof.txt` was generated on API 36 using
`AppBaselineProfile` (startup, Practice, and subtitles), filtered to the app's
package and excluding benchmark-only activity rules. Regenerate with an
unminified profile-generation variant:

```powershell
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-PprofileGeneration=true' '-Pandroid.testInstrumentationRunnerArguments.class=com.kienhoang.dualsubreplay.benchmarktests.AppBaselineProfile' --max-workers=2
```

Review the generated text profile in test additional outputs, remove rules for
benchmark-only classes, and replace the source profile. Build the normal
optimized variant afterwards. Runtime profile installation uses AndroidX
ProfileInstaller; benchmark/test libraries remain outside the app.

`tools/capture_performance.py` captures five cold launches, stable-screen PSS,
device fingerprint, APK hash/size, and gfxinfo. Supply `--compare` with the
earlier summary on the same device/package. It flags startup or PSS increases
over 10%; repeat under matched conditions before drawing conclusions. It does
not clear app data. Keep onboarding, screen, power, compilation state, and device
temperature comparable. This is separate from extended live-playback memory
and subtitle timing, which require a repeatable live-device session.

## Translation evaluation

The explicit benchmark-only `journey=translation` evaluates six fixed pairs
of fragmented/coherent inputs (English/Vietnamese, Vietnamese/English, and
Japanese/Vietnamese), including an idiom and punctuation. It downloads ML Kit
models and writes `translation-evaluation.json` to the app's external files.
Offline CI tests do not use this network-dependent journey.

Natural mode retains source sentence context across display splits, joins
adjacent incomplete units within timing/length bounds, and shares the result
over the original time span. Raw mode retains cue-level translation. No
translated word alignment is invented. Exact-text translations are cached by
language pair with 256-entry and 128,000-character limits.

Sentence context cannot guarantee fluent output. [ML Kit describes translation
as suitable for casual use](https://developers.google.com/ml-kit/language/translation)
and non-English pairs can involve an English intermediate translation. Human
bilingual review remains necessary for quality acceptance.

## Anki backend round trip

`PracticeTransferTest` emits `app/build/qa/anki-app-export.tsv`. Install Anki's
Python backend into an isolated QA directory, then run
`tools/verify_anki_roundtrip.py --runtime <directory> --input <fixture> --output <returned.tsv>`.
The script creates a temporary ten-field note type, imports with the real Anki
backend, and exports one note. The checked-in returned TSV fixture is parsed by
the app's unit tests. No user's Anki collection is touched.
