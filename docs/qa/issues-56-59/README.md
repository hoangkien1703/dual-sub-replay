# Issues 56–59 verification record

Local implementation on `codex/issues-56-59-quality`, September 7, 2026.
No production release, issue closure, or physical-device acceptance is implied.

## Translation

[The recorded six-sample evaluation](translation-evaluation.json) ran through
ML Kit on the API 36 emulator in the optimized benchmark APK, with actual model
downloads. All six samples returned before/after output. “Before” translates
each display fragment separately; “after” supplies the coherent source sentence
to the same model. This isolates input segmentation, not a model upgrade.

- Vietnamese → English: “I lived here” / “It's five years ago.” became
  “I have lived here for five years.”
- English → Vietnamese: joined clauses are more coherent, but “give me a hand”
  still produces literal, unnatural Vietnamese. The past counterfactual also
  loses nuance in both versions.
- Japanese → Vietnamese: the causal clauses join correctly; the model still
  expresses “stopped going out,” which is less precise than “decided not to go out.”

These examples support retaining context, but do not establish consistently
natural translation. No translated-word timings are fabricated.

Optimized QA first exposed missing no-argument constructors for ML Kit's two
manifest-discovered component registrars (`NoSuchMethodException`, then a null
translator factory). Targeted constructor keep rules fixed the failure. The
successful evaluation above is from the rebuilt optimized APK.

## Interoperability and timing

The real Anki 26.8.1 backend imported the app's Unicode/quoted/multiline TSV into
a disposable ten-field note type and exported it. The returned fixture is checked
by `PracticeTransferTest`. Anki flattened an embedded newline to a space on its
plain-text export; JSON remains the lossless backup format.

The fullscreen regression fixture deliberately freezes video-frame callbacks,
advances the media clock from 1s to 12s, and changes a caption. The snapshot must
stamp the new caption at 12s. Presentation tests feed identical timed state to
portrait and landscape components, then switch pause/resume without reopening
either. Existing offline replay, seek, speed, rolling/static caption and lifecycle
tests remain part of the full device suite. This establishes deterministic
behavior; real YouTube fullscreen transition/rotation and perceptual highlighting
still require physical-device QA.

## Evidence locations

Checks passed: 199 unit tests; lint/format/complexity; 36 full-suite API 36 device
tests plus one added preference-persistence test; debug/test/optimized builds;
three Macrobenchmark journeys and one Baseline Profile generation journey.
The earlier managed-device run passed 35 tests. The final screenshot test also
passed separately using synchronized Compose capture.

The actual Android file-picker flow exported 500 records and re-imported the
backup. Preview showed 500 valid, 0 invalid, 500 duplicate rows; the default import
reported 0 added words. [Practice](ui/practice-transfer.png),
[import preview](ui/import-preview.png), and
[matching portrait/landscape highlighting](ui/caption-pause-both-presentations.png)
were visually reviewed.

### Performance investigation

The [Macrobenchmark summary](macrobenchmark-summary.json) records median startup
TTID 995 ms; Practice frame CPU P50/P95 21.4/39.0 ms and subtitle scrolling
21.1/43.4 ms. Frame overrun is reported separately. These are software-rendered
emulator results, not evidence of smooth physical-device playback or improvement
over an earlier scrolling implementation.

The first quick comparison had unmatched cached compilation states and flagged
startup +66% / PSS +19%. Both [original readings](uncontrolled-before.json) and
[after readings](uncontrolled-after.json) are preserved. The controlled repeat
installs each optimized APK, explicitly broadcasts
`androidx.profileinstaller.action.INSTALL_PROFILE` to ProfileInstallReceiver
(result 1), then runs `adb shell cmd package compile -f -m speed-profile` before
the same five-launch capture. Both builds remain on the same untouched onboarding
screen, package, API 36 emulator and signing key. No app data is cleared.

| Metric | Before | After | Repeated after |
|---|---:|---:|---:|
| Cold-start median (ms) | 681 | 740 | 686 |
| Startup PSS median (KiB) | 26,587 | 29,744 | 29,733 |
| APK bytes | 29,696,305 | 29,728,445 | unchanged |

Source summaries: [before](performance-before.json), [after](performance-after.json),
[repeat](performance-after-repeat.json). The repeated startup delta is +0.73%;
the repeatable PSS delta is **+11.83% (+3.1 MiB)**, above the 10% investigation
threshold. [Memory snapshots before](memory-before.txt) and
[after](memory-after.txt) show most of the additional PSS in Java/native heap
(roughly +1.7 MiB/+0.6 MiB in representative snapshots), plus code/stack/system
pages. ML Kit now initializes successfully where the original optimized binary
failed, but this comparison does not isolate it as the cause. No scrolling or
translation cache is populated on this screen. Treat the increase as an open
performance finding; these short samples cannot establish leak freedom.

Physical-device heap ownership, extended-playback memory and timing acceptance
remain pending. Automatic approval review blocked the emulator live-YouTube
launch with “blocked by policy”; live transitions, rotation and perceptual timing
were therefore not accepted. No performance improvement is claimed from emulator
data alone.

Raw local logs, APKs, generated profile, Anki runtime, and performance captures
are under `build/qa/issues-56-59/` (ignored build output). Gradle HTML/XML results
are under `app/build/reports/` and `app/build/outputs/androidTest-results/`.
Compact results and selected screenshots are retained beside this file.
