# Skip ads and sponsors

## Status

Implemented (owner request 2026-10-25 in the "Skip YouTube ads in the app" project thread: "do what
you recommend, have setting for it", after the owner picked "Skip ads + skip sponsors");
waiting for the owner's phone test. Offline tests cannot load live YouTube, so whether YouTube's
ads behave as described below is unverified until then.

## Context / problem

The app plays YouTube's own page player, so its video ads play too. Ads can't be skipped by the
app's subtitles and replay, which simply wait until an ad is gone (`.ad-showing` on the player).
Many videos also contain sponsor parts that the creator recorded into the video itself.

How other tools do it (checked 2026-10-25):

- uBlock Origin, Brave and AdGuard remove ad entries from YouTube's player replies and block ad
  addresses. That is the strongest way, but YouTube fights it with "ad blockers are not allowed"
  warnings, slow loading and frequent changes, and it does not work on ads YouTube stitches into
  the video stream itself (server-side ad insertion, tested since 2024).
- Add-ons such as "YouTube AdBlock" keep YouTube's ads but mute them, play them fast and press
  YouTube's own Skip button. This also works on stitched ads. Their authors note that very fast
  playback can make YouTube show its ad-blocker banner, and recommend about 4x.
- NewPipe and ReVanced play streams without YouTube's player or patch the YouTube app; the owner
  rejected both approaches before (see the native-mode spec).
- SponsorBlock is a community database of sponsor parts. Its API answers with the segments of
  every video whose SHA-256 hashed ID starts with the same 4 characters, so the server does not
  learn which video is watched. The database is licensed CC BY-NC-SA 4.0.

## Goals

- Settings → **Ads & sponsors** with two switches, both on by default:
  - **Skip ads**: while YouTube shows a video ad, the page video is muted and plays at 4x, and
    YouTube's own Skip button is pressed as soon as it appears. Overlay banner ads on the player
    are closed. After the ad, the video's sound and speed come back as they were.
  - **Skip sponsors**: sponsor parts listed in SponsorBlock are jumped over, and a short
    "Skipped sponsor" note shows on the player.
  - **Always skip sponsors** (owner request 2026-10-25 after testing build 503, off by default,
    shown under Skip sponsors): a seek, a tapped line or a replay that lands in a sponsor part
    jumps to its end too, so sponsor parts never play.
- The AI assistant can read and change the switches (`skip_ads`, `skip_sponsors`, `always_skip_sponsors`), at once with
  Undo, like the other settings.

## Non-goals

- Blocking ad requests or editing YouTube's replies (option "Block ads", not picked). The page
  scripts still never change a request or a reply.
- Hiding ads in YouTube's feed or search results.
- Skipping other SponsorBlock categories (intro, outro, self-promotion, reminders to subscribe):
  for language learners they are still speech. Only `sponsor` is asked for.
- Sending segments or votes to SponsorBlock.

## User-visible behavior

- An ad still starts (YouTube decides that), but it is silent and fast, and a skippable ad is
  skipped about 1 to 2 seconds after it starts. An unskippable 15-second ad takes about 4 seconds.
- A sponsor part is jumped over when the video plays into it. Jumping into it on purpose
  (dragging the bar, tapping a line in it, replaying a saved word there) plays it, and it is not
  skipped again in that video. The player's own seeks into a part (small seeks, its return after
  an ad, undoing a skip within 3 s) are skipped like playing into it.
- With a switch off, nothing changes on the page; turning Skip ads off during an ad brings the
  sound and speed back at once.

## Technical constraints / invariants

- One page script, `webAdSkipScript` in `ui/AdSkipScripts.kt`, installed once per page and given
  its settings and the open video's segments by `webAdSkipConfigScript`. It checks the page's
  origin (`https:` + `*.youtube.com`) like every page script, never calls `fetch(`, and only
  touches the page video's `muted`, `playbackRate` and `currentTime`, YouTube's Skip and
  banner-close buttons, and its own note element.
- It runs on a timer every 1 s, every 0.25 s while an ad shows, plus the video's `timeupdate` and
  `seeking` events for sponsors, so it adds little CPU.
- Segments are asked only from `https://sponsor.ajay.app/api/skipSegments/<4 hex>` with
  `categories=["sponsor"]` and `actionTypes=["skip"]`, a 10 s timeout and a 1 MiB reply cap, by
  `data/SponsorSegments.kt`. Only the segments of the open video ID are kept; a segment whose
  recorded video length differs from the page video's by more than 3 s is ignored (another cut
  of the video). The last 20 videos' segments stay in memory; nothing is written to storage.
  Failures skip nothing and show nothing.
- The SponsorBlock credit (CC BY-NC-SA 4.0) is in `THIRD_PARTY_NOTICES.md` and on the licence
  screen; `PRIVACY.md` names the request.
- Strings in `strings_ads.xml` in every interface language.

## Plan

1. `ui/AdSkipScripts.kt`: settings, page script, config script, and `AdSkipPageScripts` beside
   `StudyPageScripts` in `SingleYouTubePage`.
2. `data/SponsorSegments.kt`: request URL, reply parser, client with cache.
3. Settings section, AI settings, Reset all settings keys, strings, credits, privacy.
4. Unit tests for the scripts, URL, parser and AI settings.

## Validation

- Unit tests: script origin checks and limits, segment URL and parser (other videos, bad
  numbers, non-skip actions), AI settings parse and read back, strings in every language.
- Phone (owner): a video with a pre-roll ad (skippable and not), a video with a known sponsor
  part (for example one listed on sponsor.ajay.app), both switches off and on, and that no
  ad-blocker warning shows after a day of normal use.

## Implementation notes

- As planned. The settings live in `ui/AdSkipSettings.kt`; `AdSkipPageScripts` gives the page its
  settings every 2 s (the page may reload or change video) and asks SponsorBlock once per video.
- The Skip button selectors are broad (`[class*="ytp-ad-skip-button"]`) because YouTube renames
  them often; a shown `<button>` is pressed before any other match.
- Passed locally: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug`.
- Passed in desktop Chromium (Playwright) on a fake `m.youtube.com` page with the real script:
  during `.ad-showing` the video was muted at 4x, the hidden Skip button was not pressed and the
  shown one was, afterwards the video was unmuted at its old 1.5x; a sponsor part from 10 to 40 s
  was jumped to 40 s once with the note, was not skipped again, and a seek into another part kept it.
- Unverified: live YouTube ads and Skip buttons on a phone, SponsorBlock replies on a phone, and
  whether YouTube shows an ad-blocker warning.

### Owner test of build 503 (2026-10-25)

- Ads did play fast, but YouTube did not take the script's press of Skip; the owner had to tap
  it. YouTube ignores a press a page script makes (`isTrusted` is false). Since then the page also
  reports where the shown Skip button is (`WEB_SKIP_TARGET_SCRIPT`), and the app sends a real
  touch to the view showing the page (the full-screen view while full screen), at most every
  1.5 s, after the button has shown for 0.3 s and only when `elementFromPoint` there is the
  button, so a tap never lands on the ad video, which opens the advertiser. A button counts as
  shown only when it and every box around it are displayed, visible, not see-through and take
  touches. The Skip selectors also match any `skip` button inside the player during an ad.
- The owner asked for Always skip sponsors (above).

### Owner test of build 505 (2026-10-25)

- Always skip sponsors worked, but Skip sponsors alone did not skip. With Always off, any seek that
  landed in a part kept it, so a seek of the player's own (not the user's) could keep a part before
  it was reached. Now a seek keeps a part only when it is a jump of at least 1.5 s from the last
  position the video played at, with no ad since and more than 3 s after the app's own skip; any
  other seek into a part is skipped. The app's own skip no longer keeps the part either, and the
  page's 1 s timer checks for sponsor parts too, in case a `timeupdate` is missed.
- Two ads in a row: the second Skip was not pressed. Each new Skip button now starts its own 0.3 s
  wait (the page script and the app's check both notice a new button), and the app tries five points
  of the button, so a box partly covering its middle does not stop the tap.
- Settings → Ads & sponsors shows two check lines for phone tests: what the last ad's Skip check
  found (for example `Skip tapped: button.ytp-skip-ad-button (2 taps)` or `... covered by div.x`),
  and how many sponsor parts SponsorBlock listed for the last video.
- Checked in desktop Chromium on a fake page: a covered middle was tapped beside it, the second ad's
  new button was tapped after 0.3 s (both real clicks), a user's jump into a part kept it, and
  playing into a part, undoing the skip and returning into a part after an ad were all skipped.
