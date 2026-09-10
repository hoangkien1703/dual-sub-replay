# Caption formats and playback clock: verification record

Branch `codex/short-captions-clock`, based on `2a9d452` (September 8, 2026).
Addresses #61, #59, and #57. Physical-phone acceptance remains pending.

## Behavior

- More settings → Caption format: Short paired phrases (default), Whole sentence.
  Existing users who explicitly disabled splitting retain Whole sentence. Reset settings returns to Short paired phrases.
- Translations now belong to exactly one displayed unit. Whole translations are no longer repeated on every short fragment.
  Sentence joining stops at punctuation or long pauses and is bounded to 12 seconds / 240 characters.
- Source-word timestamps survive splitting. Missing source timing still requires estimates;
  a compound timing token is kept intact rather than split into invented timings.
- One native monotonic clock projects timestamped media samples at playback rate, for all layouts.
  It expires after 250 ms without a fresh sample, freezes on pause/buffering, and reanchors on seeks.
  The old fixed 75 ms word lead is removed. Playback time comes from the video element, not frame callbacks.
- Adaptive rejects stale or temporally inconsistent live-word mappings; explicit Live/Transcript modes remain available.
- Whole-sentence overlay text is no longer capped at two lines. The opted-in longer format can cover more of the video.

## Evidence and limits

Fresh local checks: **209 unit tests**, **40 Android device tests**, format/complexity gates,
lint, debug/test APK assembly, and the optimized benchmark APK all passed. The disposable `.qa`
Maestro first-launch/guide/Practice/settings/restart flow passed (1/1, 80 seconds), with no new QA-app crash.
Raw command logs and APKs are under ignored `build/qa/captions/`; the smoke summary is [retained here](maestro-summary.json).
The isolated Compose screenshots use fixed translation text for layout QA, not ML Kit quality evidence:
[short phrases](ui/short_phrases-large.png), [whole sentence](ui/whole_sentence-large.png).
Both use 1.35× app text size and render the same highlighted word in the card and overlay.

Deterministic clock tests require less than 150 ms error relative to an injected media clock across speeds and presentation restarts.
This is a clock/selection assertion, not a microphone measurement of audio-to-screen synchronization.
WebView fixtures freeze frame callbacks and exercise waiting/playing/seeking/seeked, replacement video elements,
and fullscreen-change events. Real Android custom fullscreen and rotation are separate live checks.

Debug-only `CaptionTiming` logcat entries contain media time, callback round trip, sample age, caption timestamp,
selected segment/word, annotated-text preparation timestamp, and fullscreen/orientation. They omit caption text and are capped
at 3,000 entries per process. Network transport delay, caption-source timing errors, and display rendering delay
must be distinguished; a caption timestamp is not an acoustic ground truth.

The quality baseline changes only rename the two existing settings callback signatures.
No new complexity exemption or formatting budget is added. Existing uncommitted Maestro work is excluded from this PR.

## OnePlus Ace 5 acceptance checklist

1. Install the PR preview alongside the production app. Record Android/OxygenOS/ColorOS version,
   Android System WebView version, APK build number, video URL, caption language/type, and a failing timestamp.
2. Play the same 30–60 second passage using Adaptive at 1× in portrait, landscape, and native fullscreen.
   Try the previous failing passage first; the emulator comparison uses `https://www.youtube.com/watch?v=vLijZb9BpkE` around 1:00.
3. Compare the highlighted word with the audible word; note early/late direction and approximate delay.
   Repeat with device speakers before comparing Bluetooth, which can introduce a separate audio-output delay.
4. Pause/resume, seek backward and forward, switch 0.5×/1×/1.5×/2×, and enter/exit fullscreen repeatedly.
   Check that highlighting does not keep advancing during a buffering pause.
5. Switch Short paired phrases ↔ Whole sentence while playing. Check the same position, original-word highlighting,
   translation pairing, paragraph replay, and word tapping. Restart the app to verify the chosen format persists.
6. Try larger text and both manual/auto-generated captions. Compare explicit Transcript and Live modes when Adaptive differs.
7. Report the video/timestamp, format, timing mode, orientation, and device details with any remaining lag.

Do not treat this PR as proof that every YouTube caption track or on-device translation is accurate.
ML Kit remains local and can mistranslate idioms and nuance; there is no cloud translator or API key.

## Translation evaluation

[Actual ML Kit outputs](translation-evaluation.json) were produced on the API 36 emulator in the optimized benchmark app.
The `before` field is the historical tiny-fragment input baseline, not a claim about what the previous production APK displayed.
Whole-sentence and short-mode inputs use the same model. Coherent short sentences retain improvements such as
Vietnamese “Tôi đã sống ở đây được năm năm rồi.” → “I have lived here for five years.”
The “give me a hand” idiom remains literal, and the past counterfactual still loses nuance.
Japanese output remains less precise than “decided not to go out.” No model-quality improvement is claimed.
The main #61 correction is that a translated sentence is no longer copied onto every short source fragment.

## Live playback boundary

The instrumented **baseline** APK ran real YouTube playback on the emulator, including native fullscreen via
WebView DevTools, rotation, play/pause, and a seek to 1:00. [Baseline round-trip statistics](baseline-summary.json)
include mixed paused/playing samples and host build load, so they are not a controlled performance benchmark.
They show occasional callbacks hundreds of milliseconds late; the new clock explicitly rejects late samples.

**The rebuilt APK's live-YouTube launch was rejected by automatic approval review with `blocked by policy`.**
A first combined command and a later explicit VIEW launch were rejected. The reason was not further specified.
No workaround was used to navigate to the video after that rejection. Therefore no live before/after latency improvement,
real network-stall recovery, or acoustic fullscreen accuracy is claimed. Those checks remain on the phone checklist.
Offline fixtures, both full Android suites, and the normal-app UI smoke are independent evidence that passed.

The final full device rerun passed all 40 tests in 187.873 seconds. The seven-sample optimized ML Kit run includes
a longer “box … too heavy” sentence: short mode produces two source/translation pairs, while whole mode retains
one sentence translation. Its idiom remains literal, documenting the on-device model limitation.
