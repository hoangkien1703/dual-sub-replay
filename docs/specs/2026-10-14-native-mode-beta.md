# Native mode (beta)

## Status

Implemented. On 2026-10-14 the owner asked, in the project thread "Native YouTube app idea", to
"recreate the native YouTube app" with all of the app's features: "try to do all of it, and
anything a user need from YouTube, and create 1 PR. I want it to be an option, and show in left
bar, write that it is still in beta so users can try it and switch back if they don't like it".
The owner named it "Native mode (beta)". That authorizes this scope in one PR, not merge or
release.

## Context / problem

- The app shows YouTube's mobile web page in its one WebView. The page works, but it looks and
  feels like a website, and the owner wants the look of the YouTube Android app.
- The real YouTube app cannot host our features (see the
  [lighter caption watcher spec](2026-10-13-lighter-caption-watcher.md)). Playing streams in our
  own player would mean extracting stream URLs, which skips YouTube's player and ads; that was
  declined. So the video keeps playing in YouTube's own player on the page.
- YouTube's public web endpoints (the same undocumented Innertube family the caption provider
  already uses) return search results, watch details, related videos, comments, channels and
  playlists without signing in. A channel's public RSS feed lists its newest uploads.

## Goals

- An opt-in **Native mode** with a **Beta** badge in the left bar, remembered across launches,
  with a beta notice and a **Switch back** button on its home screen.
- Native screens for Home, Search, Watch, Channel, Playlist and Library, styled with
  `DualSubTheme`.
- Watch screen: YouTube's player on top (16:9 in portrait), then title, views, date, channel
  with Subscribe, Watch later, Share and Classic page, and tabs for Subtitles (our timeline),
  Up next, Comments (read-only, with replies) and About.
- History, Watch later, subscriptions and recent searches kept on the phone, since native mode
  has no YouTube account. Home shows Continue watching, new uploads from subscribed channels,
  and videos related to the last watched one.
- Turning native mode off returns to the classic page exactly as before.

## Non-goals

- Signing in, liking, commenting, YouTube's own subscriptions, history or recommendations.
  These stay on the classic page ("Classic page" on the watch screen).
- Shorts, live chat, downloads, our own video player or stream extraction.
- A second WebView or player, or changing how subtitles are captured and translated.

## User-visible behavior

- **Off (default):** nothing changes.
- **Turning it on** (left bar → Native mode): Home appears. If a video was playing, its watch
  screen opens. The choice is remembered.
- **Home:** beta notice with Switch back, Continue watching, From your subscriptions, Because
  you watched … (or Recommended for you), or a "Search for something to watch" empty state.
- **Search:** a search bar with recent searches; results mix videos, channels (with Subscribe)
  and playlists, and load more as you scroll.
- **Watch:** the player sits at the top. Our subtitle panel and floating subtitles button do not
  show in portrait; the Subtitles tab (selected first) shows the same timeline, with tap to
  replay and word learning. Autoplay or a tap on YouTube's end screen moves the watch screen to
  the new video. In landscape the player fills the screen as before, with the side panel if
  that setting is on.
- **Other screens** cover the page; a mini player at the bottom keeps the playing video with
  play/pause and stop, and opens its watch screen when tapped.
- **Back** walks back through native screens; on Home it leaves the app as before.
- **Failures:** a list that fails to load shows "Couldn't load this from YouTube." with Try
  again; a failed next page shows Try again at the end of the list.

## Technical constraints / invariants

- One WebView and the polling bridge stay
  ([AGENTS.md](../../AGENTS.md#architecture-invariants-enforced-by-tests)). Native mode adds a
  style to the same page so only its player shows (`webNativePlayerOnlyScript`); the script
  re-checks the HTTPS `*.youtube.com` origin, and `PlaybackArchitectureTest` asserts it. Videos
  open by navigating that page (`AppViewModel.openNativeVideo`).
- YouTube's undocumented responses stay behind a replaceable boundary:
  `data/NativeYouTubeSource.kt` (requests, 15 s timeout, the existing 8 MiB response cap) and
  `data/NativeYouTubeParser.kt` (tree walkers that skip unknown renderers). Screens only see
  `data/NativeYouTubeModels.kt`.
- Thumbnails and avatars load only over HTTPS from `i.ytimg.com`, `i[0-9].ytimg.com`,
  `yt3.ggpht.com` and `yt3.googleusercontent.com`, at most 2 MiB each, also after redirects.
- All new text is in `strings_native.xml` in every language folder; colors come from
  `MaterialTheme.colorScheme`.
- The local library (`native_mode_library` preferences) holds only public video and channel
  ids and titles, so it stays in normal backups.

## Proposed approach / plan

1. Models, parser and source for search, watch-next, comments, browse (channel Videos tab,
   playlists) and the channel RSS feed. Validate the parser against saved real responses.
2. `NativeLibrary` for history (200), Watch later (500), subscriptions (300) and searches (12).
3. `NativeModeViewModel`: screen stack (watch screens replace each other, at most 30 screens,
   Home kept at the bottom), cached loads, paging, failed-page retry, home feed.
4. Compose screens (`NativeModeScreens.kt`, `NativeWatchScreen.kt`, `NativeModeComponents.kt`,
   `NativeImages.kt`).
5. Wire into `DualSubApp`: left-bar switch, 16:9 page in portrait with the player-only style,
   native shell over the page, back handling, following the page video, and hiding the
   floating subtitle overlay while a native screen covers the page.
6. Strings in all 8 languages, unit and Compose tests, docs.

## Acceptance criteria

- [x] The left bar shows "Native mode" with a Beta badge and a switch; switching it on and off
  works and is remembered (`NativeModeUiTest.leftBarShowsNativeModeAsBetaAndSwitchesIt`).
- [x] Home shows the beta notice and Switch back calls back to the classic page
  (`NativeModeUiTest.searchWatchCommentsAndLibraryWorkBesideTheBetaNotice`).
- [x] Search shows videos and channels; opening a video shows the watch screen with Subtitles,
  Up next and Comments; back returns to the results with the mini player; Library lists the
  watched video (same test, with a fake source).
- [x] Parser reads search, lockup and classic renderers, skips Shorts and ads, reads watch
  details, comments with their entity payloads, playlists, channel pages without a channel
  row, continuations and the RSS feed (`NativeYouTubeParserTest`).
- [x] Library persists and de-duplicates (`NativeLibraryTest`); stack, paging and image host
  rules hold (`NativeModeStateTest`).
- [x] Native-mode page scripts re-check the YouTube origin (`PlaybackArchitectureTest`).
- [x] Every language has every new string (`AppLanguageTest`, lint `MissingTranslation`).
- [ ] With native mode off, the app behaves exactly as before (existing unit and managed-device
  suites in CI).
- [ ] On a phone: search, play, subtitles tab, comments, channel, playlist, background play,
  and switching back all work on live YouTube (owner, preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`, including `NativeYouTubeParserTest`, `NativeLibraryTest`, `NativeModeStateTest`, `PlaybackArchitectureTest`, `AppLanguageTest` | Cloud container / PR CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container / PR CI |
| Managed-device/emulator | `NativeModeUiTest` (fake source, screenshots via `saveUiEvidence`) and all existing suites | PR CI `managed-device-tests` |
| Parser against real data | Saved logged-out responses for search (20 items + token), watch (title, channel, 20 related, comments token), comments (count, 20 comments with pinned/creator/likes/replies), channel (30 videos + token), channel playlists tab (6), playlist (9), RSS (15) | Cloud container, one-off |
| Physical-device/manual | Owner tries the preview APK | Owner's phone |
| Live YouTube | Layout of the player-only page and live responses cannot be checked from the cloud (Google bot check on the page) | Owner's phone |

## Risks / edge cases

- YouTube changes its web responses often. The parser skips what it does not know, so a change
  shows fewer items or an error with Try again, never a crash; the classic page stays one tap
  away.
- The player-only style depends on the mobile page's player element names. If YouTube renames
  them, the 16:9 strip shows the top of the page instead of only the player.
- Logged out, YouTube has no personal home feed, so Home is built from local subscriptions and
  history; a brand-new user sees the search prompt.
- Watching a video while native mode covers the page still navigates the one page, so the
  page's own back history is not used in native mode.
- Live videos have no subtitles timeline in some cases; the Subtitles tab then shows the same
  live fallback as the classic panel.

## Release intent

Patch (the default for feature PRs unless the owner chooses otherwise); minor was suggested to
the owner because this adds a whole new mode.

## Results

- Implemented as planned. Deviations: the floating subtitle panel, button and overlay stay hidden
  only while native screens cover the page or the portrait watch screen shows its Subtitles tab;
  back first lets the left bar and an open assistant panel close.
- Cloud container, 2026-10-14: `formatCheck` ratchet passed, `complexityCheck` passed (only
  `SingleYouTubePage`'s baseline signature text changed for its new parameter),
  `testDebugUnitTest` passed (all 87 classes, including the new parser, library and state tests),
  `lintDebug` passed, `assembleDebug` and `assembleDebugAndroidTest` passed.
- Parser checked against saved real logged-out responses before the unit fixtures were written.
- Not yet verified: `NativeModeUiTest` and the existing suites on the managed device (PR CI), and
  everything on live YouTube (owner's phone with the preview APK).
