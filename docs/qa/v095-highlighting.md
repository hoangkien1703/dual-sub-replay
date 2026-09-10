# v0.9.5 highlighting restoration

The user reports v0.9.5 works in portrait and landscape, while the preview-16 port in PR #63 fails in landscape. Restore the v0.9.5 automatic timing path as the sole implementation.

## Restored behavior

- `KaraokeTiming.kt`: rolling caption reconciliation, contextual transcript mapping, two coherent updates before trusting live progress, and timestamp fallback when live text is missing, mismatched, or stale. No timing mode selector is reintroduced.
- `WordTiming.kt`: v0.9.5's 75 ms visual lead between words, never before the first word starts.
- `AppViewModel`: calculate the word from playback snapshots again, independently of orientation and page-engine messages. Reset tracking on seeks, navigation, language/format changes, and highlight toggles.
- Remove the preview-16 JavaScript asset, WebMessage bridge, and their retired tests. Both portrait cards and landscape/fullscreen overlays still consume the same state.

## Preserved current features

The existing single WebView, validated playback clock, fullscreen lifecycle handling, caption formats, translation/recovery, CJK tokenization, word learning, vocabulary, replay, colors, and visibility settings remain. Live-only recovery uses the same rolling text reconciliation with the existing translation identity checks. Caption format changes remap the active word immediately.

## Verification

- Retain v0.9.5's timing/reconciliation unit cases and the current CJK and word-span coverage.
- Add a ViewModel-to-Compose regression: with no live caption signal, playback changes update actual underline spans on both subtitle surfaces; switch portrait/landscape presentation and formats, seek backward, and toggle highlighting.
- Existing offline WebView tests cover frozen frame callbacks, playback snapshots, pause/buffering/seeks, and lifecycle recovery.
- Full Android validation runs in GitHub Actions because this local environment has no usable Android SDK/dependency setup. CI status and any limitations are reported on the PR.

## Phone acceptance

On 2026-09-10, after receiving PR #63 build 271, the user reported that the app works well and approved merging and publishing v1.0.0. This records acceptance of that build; it does not imply every scenario below was individually measured.

Compare the same video/time range with v0.9.5, with matching source language and highlight setting. Check portrait, landscape split, fullscreen overlay, rotation during playback, pause/resume, backward/forward seeks, 1x/1.5x/2x, both caption formats, and Word Learning on/off. Repeat with auto captions and a manually authored track. Fixtures cannot establish subjective synchronization with live YouTube audio on the user's phone.

## Landscape live capture correction

Build 270 restored the timing algorithm but left a later visibility guard in place: `subtitlePanelVisible && ...`. Automatic landscape/fullscreen overlays hide the transcript panel, so that guard removed the live-caption observer while subtitles remained visible in the overlay. The app consequently switched to transcript timestamps. v0.9.5 did not have that guard.

`shouldCaptureCaptionsForPresentation` now considers either a visible transcript panel or the effective overlay mode. Hidden transcript-only mode still disables capture, and the highlighting/manual-caption rules remain. Live translation recovery continues to capture even with highlighting off.

The regression suite now includes the complete `LearningPlayerRoot` and its persistent `SingleYouTubePage`, with all page requests intercepted by an offline fixture. It switches the Compose orientation configuration into landscape, exercises the production native-fullscreen callbacks, returns to portrait, and verifies that live words continue while the panel is hidden. Transcript timestamps are deliberately too late to produce any expected word, so timestamp fallback cannot make the test pass. It also verifies that the same page survives each transition. This tests the real presentation/capture wiring; it does not measure phone audio latency or simulate physical device rotation.

The optional request interceptor is unset in production. It lets instrumentation use the real WebView client, capture scripts, polling, and ViewModel without reaching YouTube.
