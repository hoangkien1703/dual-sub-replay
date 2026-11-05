# Bluetooth sound test build (temporary)

- Status: test build, not for merging as is
- Owner request: 2026-11-05 ("When I use headphones, sometimes the sound gets distorted and I can't hear anything for a moment, and sometimes it repeats.")

## Problem

On the owner's OnePlus Ace 5 (ColorOS 16 / Android 16) with Baseus Bluetooth earphones, the app's video sound sometimes distorts, goes silent for a moment or repeats a bit. It does not happen in the YouTube app, Spotify, or YouTube in Chrome with the same earphones, with the screen on or off. The owner noticed it after the screen-off stutter fix (PR #190, v1.8.15). The owner has YouTube Premium (no ads) and Skip silence is off.

Reading the code found no clear cause: the app never takes audio focus, never switches audio tracks or changes the video's sound (the loudness booster is only on a tapped word's own player), and only seeks or changes speed for the learner's own actions or the play modes. PR #190 only changed full screen (WebView's full-screen view is wrapped in `OffScreenPlaybackFrame`). The phone gives no logs (no USB debugging).

## Plan

A temporary Settings → App updates → Sound test (English only, `translatable="false"`, `ui/SoundTest.kt`) with cumulative stages, so the owner can hear which one makes the glitches stop:

1. Old full screen: the full-screen view is shown directly again, as before PR #190.
2. No media notification: the page video's state is not published, so the playback service and media session stop (nothing goes to the earphones over AVRCP).
3. Slow page reading: the playback snapshot is read once a second instead of every 100 ms.
4. No page scripts: the snapshot poll, transcript/translation readers, study, ad-skip and TV page scripts, live caption capture and caption track sync all stop.
5. Plain page: the WebView is made again without the document-start scripts and onPageFinished scripts.

## Acceptance criteria

- Normal (the default) behaves exactly as main.
- Each stage takes effect without restarting the app (stage 5 reloads the page; stage 1 takes effect the next time full screen opens).
- Unit tests, lint, format and complexity checks pass; CI makes a preview APK.

## Validation

- Owner test on the phone: pending.

## Follow-up

Once a stage is found, remove this switch and fix the cause in its own PR.
