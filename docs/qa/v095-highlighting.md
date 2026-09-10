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

Compare the same video/time range with v0.9.5, with matching source language and highlight setting. Check portrait, landscape split, fullscreen overlay, rotation during playback, pause/resume, backward/forward seeks, 1x/1.5x/2x, both caption formats, and Word Learning on/off. Repeat with auto captions and a manually authored track. Fixtures cannot establish subjective synchronization with live YouTube audio on the user's phone.
