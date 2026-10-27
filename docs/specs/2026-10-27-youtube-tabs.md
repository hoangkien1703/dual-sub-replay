# YouTube tabs in the menu

## Status

Implemented (owner request 2026-10-27). Merge waits for the owner's phone test.

## Context / problem

The app shows one YouTube page. To look at a second video and come back, a learner has to search
or press Back many times, and loses the place in the first video.

## Goals

- A "Tabs (N)" row in the menu, above Practice, where N is the number of open tabs. Tapping it
  unfolds the list of tabs under it (the ▾/▸ choice is kept).
- Each tab shows its video picture and title, the open tab is highlighted, and an X on the right
  closes it. "+ New tab" opens YouTube's home page in a new tab. Up to 10 tabs.
- With every tab closed, the main page says so and offers "Open new YouTube tab".
- The first time the app opens, YouTube's home page opens in a tab by itself, as before. Users
  updating from an older version keep the page they had open as their first tab.
- Long-pressing a video on the YouTube page offers "Open in new tab" (or "Open here").
- Only the open tab plays; switching tabs pauses the old one, and a video opens again where it
  was left. Tabs are remembered after the app closes.
- All tabs share one Google sign-in. Settings → YouTube tabs → "Use different Google accounts"
  (off by default, owner decision 2026-10-27) adds "New tab with another account": Account 2 and 3
  each keep their own Google sign-in. Account names show only on tabs that use Account 2 or 3.

## Non-goals

- No tab bar or extra buttons on the main screen (the owner prefers settings and the menu).
- Tabs do not keep their own Back history after a switch; the page opens at the tab's address.
- No more than 3 accounts, and no way to name them.

## User-visible behavior

- Menu: Tabs (2) ▾ → [thumbnail] "Learn Japanese with anime" ✕ (highlighted), [search icon]
  "japanese" ✕, + New tab (and + New tab with another account when the setting is on).
- Tapping a tab opens it and closes the menu. Closing the open tab opens the next one (or the one
  before it when it was the last); closing the last tab shows the empty page.
- Long-press a video → Open in new tab → "Opened in a new tab · Show". With 10 tabs open, the menu
  says "Up to 10 tabs can be open" and Open in new tab says so too.
- A shared link or a video the assistant opens loads in the open tab, or in a new tab when every
  tab was closed.

## Technical constraints / invariants

- Still exactly one WebView (`SingleYouTubePage`): a tab is a saved address (`BrowserTabs`,
  `ui/BrowserTabs.kt`, preference `browser_tabs`, JSON) loaded into that page with the existing
  navigation request. This keeps memory and battery use as before (each loaded YouTube page costs
  about 150-300 MB) and keeps background play, earphone controls, the subtitle panel and the ad
  and sponsor skipping tied to the one page that plays. This replaces the "keep the 2 most recent
  tabs loaded" idea from the discussion: a switch reloads the page (about 1-2 s) instead.
- The leaving tab's video position comes from `WebVideoBridge.playback` and is saved as the watch
  address's `t=` start (from 5 s on).
- For 5 s after a switch, a page report of the address being left is ignored, so a late report
  from the old page cannot overwrite the new tab's address.
- Accounts are androidx.webkit profiles (`WebViewFeature.MULTI_PROFILE`, `ProfileStore`,
  `WebViewCompat.setProfile`): Account 1 is WebView's default profile (the existing sign-in),
  Account 2 and 3 are `youtube-account-2` / `-3`. A profile must be set before the WebView loads
  anything, so the page is keyed by the account and a tab of another account makes a new WebView
  (the old one is destroyed first). Sign-in detection and cookie flushing use the tab's profile's
  `CookieManager`. On WebViews without profiles the setting is replaced by a hint to update
  Android System WebView, and every tab uses Account 1. Profile data lives under `app_webview/`,
  which backups and device transfers already leave out.
- Tab thumbnails come only from `https://i.ytimg.com/vi/<video ID>/mqdefault.jpg` (max 256 KB,
  memory cache of 24, no redirects).
- New text exists in all 8 interface languages (`strings_tabs.xml`). The assistant can turn
  `different_tab_accounts` on or off; the setting and the list's open state are reset by Reset all
  settings (tabs themselves are not settings and stay).

## Acceptance criteria

- [x] New users start with one YouTube home tab; the page open before updating becomes the first
  tab; saved tabs (with title and account) come back after a restart (`BrowserTabsTest`).
- [x] New tabs open after the others, at most 10; closing the open tab opens the next, else the
  previous; closing every tab is remembered (`BrowserTabsTest`).
- [x] "Tabs (N)" sits above Practice, unfolds into the list, X closes, + New tab opens one; a full
  list says so (`YouTubeTabsUiTest`).
- [x] Account tags and "New tab with another account" show only with the setting on
  (`YouTubeTabsUiTest`, `BrowserTabsTest.otherAccountsOnlyCountWhileTheSettingIsOn`).
- [x] The empty page explains and offers "Open new YouTube tab" (`YouTubeTabsUiTest`).
- [x] The long-press menu offers Open in new tab and Open here, only for YouTube video links
  (`YouTubeTabsUiTest`, `BrowserTabsTest`).
- [x] A video reopens at its saved time (`BrowserTabsTest.aVideoReopensWhereItWasLeft`).
- [ ] Phone: switching tabs, long-press on a real m.youtube.com video, and signing in to a second
  Google account in Account 2 while Account 1 stays signed in (owner, preview build).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. `BrowserTabsTest`, `SubtitleHighlightTest`, `AiAssistantPromptTest` | Cloud + PR CI |
| Static checks | `formatCheck complexityCheck lintDebug assembleDebug` | Cloud + PR CI |
| UI tests | `YouTubeTabsUiTest` | PR CI managed device |
| Manual | Open 3 tabs, switch, close all, reopen; long-press a video; turn on different accounts and sign in to another account in Account 2 | Owner, preview build |

## Results

- Cloud: unit tests, format, complexity, lint and debug build run locally before the PR.
- Unverified offline: whether m.youtube.com's video links answer a long press with a link (the
  WebView reports the link only if the page doesn't cancel the long press), and live Google
  sign-in in a second profile.
