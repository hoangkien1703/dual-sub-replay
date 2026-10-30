# Android TV layout (beta)

## Status

Implemented (owner request 2026-10-29/30: "Full TV mode", with every recommendation from the
discussion, an Android TV setting marked "(beta)" that can never turn on on a phone). Merge waits
for the owner's check on the Android TV emulator and, if possible, a tester with a real TV.

## Context / problem

A Reddit user asked for the app on Android TV. The phone APK already installs on a TV (minSdk 26,
no ABI filter), but:

- it has no icon on the TV home screen (no `LEANBACK_LAUNCHER` entry or banner);
- a remote cannot use it: the app had no key handling, and picking words is touch only;
- browsing m.youtube.com with arrow keys does not work (it is a touch site);
- in landscape the player switches to the floating subtitle box, which a remote cannot reach.

The owner has no Android TV, so checks run on the Android TV emulator.

## Goals

- One APK for phones and TVs. On a TV the TV layout turns on by itself; Settings → Android TV
  (beta) → "TV layout (beta)" turns it off. On phones and tablets the switch is greyed out and
  off, with a note that it needs an Android TV, and nothing below changes for them.
- The app has an icon and a banner on the TV home screen; a touch screen is not required.
- **TV home**: a remote-friendly start page instead of the YouTube page: search YouTube (the
  app's own YouTube search, no key), results as a grid of big cards, the open tabs as "Continue
  watching", and buttons for the YouTube page, Saved words, Progress and Settings.
- **Remote on the video**: OK plays/pauses, ◀/▶ go to the previous/next line, ▲ replays the
  current line, ▼ moves to the subtitle panel, Back returns to TV home. Play/pause, rewind,
  fast-forward and previous/next media keys work everywhere in the TV layout.
- **Words without dragging**: OK on a subtitle line opens the line with a big "Replay line" button
  and each word as a big button; picking a word opens the usual word card.
- The video and the subtitle panel sit side by side; panel text is 1.25× larger and focused items
  get a clear ring.
- YouTube pages other than a video (home, channels, sign-in) move focus between links and buttons
  with the arrow keys; Back goes back in the page, then to TV home.

## Non-goals

- No Google Play / Leanback library, no separate TV APK, no voice search, no YouTube TV
  (youtube.com/tv) site.
- No change for touch devices: every new behavior is behind the TV layout.
- The AI assistant panel is not redesigned for remotes (it still works with the on-screen
  keyboard).

## User-visible behavior

- Phone: Settings → Android TV (beta) shows "TV layout (beta)", off and greyed out, and "Only on
  Android TV. Install this same app on an Android TV or Google TV to try it."
- TV, first start: language setup and guide as on phones, then TV home.
- TV home → search "japanese podcast" → pick a card → the video opens with the panel beside it and
  the video has focus. ▶ jumps to the next line, ▼ then OK on a line → Replay line / words → pick
  a word → word card. Back → TV home; Back on TV home with a video open returns to it, otherwise
  leaves the app as Home does.

## Technical constraints / invariants

- Still exactly one WebView (`SingleYouTubePage`): TV home covers it while shown and pauses the
  video; opening a result uses `acceptSharedText` like a shared link.
- Keys reach `MainActivity.dispatchKeyEvent` first; the TV layout's handler (`TvRemoteKeys`) acts
  only while the TV layout is on, and never on dialogs (they are their own windows).
- The page script for arrow-key focus re-verifies the executing origin (`https:` + `*.youtube.com`)
  like the other page scripts.
- User-visible text in `strings_tv.xml` in all 8 interface languages.

## Proposed approach / plan

1. `ui/TvLayout.kt`: TV detection (`UiModeManager` television mode, `FEATURE_LEANBACK`, Fire TV
   feature), the `tv_layout` preference (resettable), `LocalTvLayout`, the settings section, the
   focus ring.
2. Manifest: `LEANBACK_LAUNCHER`, `android:banner`, `touchscreen` and `leanback` not required.
3. `ui/TvRemote.kt`: pure key → action mapping (`tvRemoteAction`), line targets
   (`tvLineStartMs`), and the activity-level dispatcher.
4. `ui/TvHome.kt`: search (YouTube's web search request already used by the assistant), grid,
   continue-watching row, buttons.
5. `ui/TvLineWords.kt`: the line dialog with Replay line and word buttons.
6. `ui/TvPageScripts.kt`: arrow-key focus script for non-video YouTube pages; a mobile user agent
   only if the TV's WebView sends none.
7. Player: in the TV layout, no automatic landscape overlay; side-by-side panel; larger panel text;
   panel lines open the line dialog.
8. AI assistant: may read and change the TV layout setting (waits for a tap: it changes the
   interface).

## Acceptance criteria

- [ ] Phone (CI emulator): the TV switch is disabled and off; the player, panel and settings
      behave as before (existing UI tests pass).
- [ ] Unit tests cover TV detection rules, the stored setting, key mapping, line targets and the
      page script's origin check.
- [ ] Android TV emulator: the app shows on the TV home screen with its banner, TV home opens,
      search shows results, a result plays with the panel beside it, remote keys work as above,
      the line dialog and word card work with the D-pad.
- [ ] Turning "TV layout (beta)" off on the TV gives the normal app back.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. `TvLayoutTest` | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` | Cloud container, PR CI |
| Managed-device/emulator | PR CI phone emulator suites unchanged | PR CI |
| Android TV emulator | Install the debug APK, walk the scenario above with D-pad keys | Owner's laptop (Remote Control) |
| Physical TV | Reddit tester with the preview APK | Optional, after merge decision |
| Live YouTube | Search and playback on the TV emulator | Owner's laptop |

## Risks / edge cases

- Some TV boxes report television mode wrongly: the switch lets the user turn the layout off.
- YouTube can change its search reply; the grid then shows "No videos found" and the YouTube page
  button still works.
- Old TV WebViews may not play m.youtube.com well; nothing the app can fix.

## Release intent

`release:minor` recommended (a new device type), but the standing default for APK changes is
patch; the owner picks before merge.

## Implementation result

- Detection and setting: `ui/TvLayout.kt` (`isTelevisionDevice`, `readTvLayout`, `LocalTvLayout`,
  More settings → Android TV (beta)); assistant setting `tv_layout`; reset all settings covers it.
- Launcher: `LEANBACK_LAUNCHER`, `drawable-xhdpi/tv_banner.png`, touchscreen and leanback not required.
- Remote: `MainActivity.dispatchKeyEvent` → `TvRemoteKeys` (`ui/TvRemote.kt`), actions run in
  `ui/TvExperience.kt`. ▼ to the panel pauses the video and focus follows the playing line.
- TV home: `ui/TvHome.kt` over the one WebView (never replacing it), search through
  `data/YouTubeVideoSearch.kt`; its state survives watching a video.
- Line words: `ui/TvLineWords.kt`. Page focus, unmute and mobile user agent: `ui/TvPageScripts.kt`
  (`mediaPlaybackRequiresUserGesture` is off only in the TV layout).
- Remote fixes from the emulator walkthrough: the side menu button is hidden in the TV layout (the
  remote could open the drawer but not leave it), sliders and text fields let ▲/▼ leave
  (`tvArrowsLeave`), language pickers start on the list, stronger focus shading (`tvFocusShading`),
  and the guide's picture shrinks to fit a TV screen. Setup screens start with their main button
  focused and ringed; text size moves 5% a press on a TV; Continue watching lists only videos and
  "YouTube page" reuses a tab already on YouTube's pages.
- Known gap: with the side menu hidden, Check for updates and the interface-language button are not
  reachable in the TV layout; turning the TV layout off brings them back.

## Validation result

- Cloud container: `formatReport` + format ratchet, `complexityCheck`, `testDebugUnitTest`
  (incl. `TvLayoutTest`), `lintDebug` pass. PR CI green.
- Android TV emulator (owner's laptop, android-34 TV image, remote keys only, 2026-10-30): banner in
  the Apps row, TV home search and results kept after a video, ▼ pauses and focuses the playing line,
  OK opens the line words, word card, ◀/▶/▲ line jumps, media keys, sound on start, sliders and
  language pickers leave with ▲/▼, setting toggle. No crashes or ANRs (`logcat -b crash` empty).
- Not fixed: arrow focus on YouTube's own pages follows a nearest-element guess and can jump (for
  example ▶ from a list item to the filter chips); the video shows no focus marker while it holds
  the remote (it is the normal watching state). No test on a real TV yet.
- Screenshots: `docs/images/android-tv/` (debug build, so the launcher label says "Preview").
