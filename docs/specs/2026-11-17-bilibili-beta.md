# Bilibili beta: China and International

## Status

Approved for implementation. Owner picks on 2026-11-17 (thread "Add Bilibili beta support"), from
25kiuo's request in Discussion #81 (comment 19671491) for video sources other than YouTube:

- A Beta section in Settings, off by default, with two switches: Bilibili (China) and Bilibili
  International. Turning one on opens that site at once the first time.
- Tabs → New tab offers YouTube (with its accounts when Different accounts is on), Bilibili, or
  Bilibili International, whichever are on.
- Sign-in on Bilibili should work (owner asked for research first; see Context).
- The Android TV (beta) section moves into Beta too (owner, same thread): Settings → Beta holds
  Bilibili, Bilibili International, then the TV layout switch. The old "Android TV (beta)" section is gone.
- A video file plus a subtitle file is a separate, later PR.

## Context / problem

- bilibili.com (China, 哔哩哔哩) and bilibili.tv (International: SEA, English and others) are
  different sites, accounts and APIs.
- Checked 2026-11-17 from a cloud phone browser (Chromium, Pixel 7 profile):
  - m.bilibili.com plays videos signed out (360p, `site=OVERSEAS`). Its player asks
    `api.bilibili.com/x/player/wbi/v2` for subtitles; signed out the list is empty, and
    `need_login_subtitle` is true for most videos (16 of 20 popular ones). The request needs a
    WBI signature and the site's `buvid3` cookie, which the page sets.
  - www.bilibili.tv plays signed out. Anime episodes list subtitles without sign-in at
    `api.bilibili.tv/intl/gateway/web/v2/subtitle?episode_id=…` (English, Thai, Vietnamese,
    Indonesian, Malay, Simplified and Traditional Chinese for the episode tried); user videos
    (`/video/<aid>`) mostly have none.
- Apps that read Bilibili subtitles with the user's own sign-in: PiliPala / PiliPlus (open source,
  Bilibili's own sign-in, CC subtitles), Immersive Translate and BiliSub (browser add-ons reading the
  page's session). We do the same: the user signs in on Bilibili's own page inside the app's web view,
  and the app asks for subtitles with that web view's cookies. The app never sees the password.

## Goals

1. Settings → Beta (new section) → Bilibili and Bilibili International switches, both off.
   Turning one on the first time opens its home page in a new tab.
2. Tabs → New tab: a chooser when any Bilibili switch is on (YouTube, YouTube account N when
   Different accounts is on, Bilibili, Bilibili International). With both off it works as today.
3. The web view keeps Bilibili pages (and their sign-in pages) inside the app while the matching
   switch is on; links to Bilibili's app (`bilibili://`, `intent://`) and app downloads are blocked.
4. A Bilibili video's subtitles load like a YouTube video's: the original line, the translation, tap a
   word, the Whole transcript, study modes. Bilibili China needs the user to be signed in for most
   videos; the panel says so.
5. Nothing changes for YouTube or for anyone who leaves Beta off.
6. Settings → Beta also holds the TV layout (beta) switch, moved from its own section.

## Non-goals

- No video file + subtitle file (next PR), no other sites.
- No Bilibili search, history or recommendations of our own; the site's own pages do that.
- YouTube-only features stay YouTube-only on Bilibili: the page-transcript fallback, live captions,
  ad and sponsor skipping, AI subtitles (Gemini reads YouTube links only), the line's sound for
  Practice cards.
- No danmaku (bullet comments).
- A saved Bilibili word replays its line on the Bilibili page, but its card gets no line sound or picture
  and its Anki link is the plain Bilibili page (the app's own link opens YouTube pages only).

## User-visible behavior

- Settings → Beta: "Bilibili" ("Watch bilibili.com videos with dual subtitles. Most subtitles need
  you to sign in to Bilibili.") and "Bilibili International" ("Watch bilibili.tv videos with dual
  subtitles."). The AI assistant can turn them on and off (`bilibili`, `bilibili_international`);
  they are in Settings search, shared settings and Reset.
- New tab with a Bilibili switch on shows a small menu. Picking Bilibili opens m.bilibili.com;
  Bilibili International opens www.bilibili.tv.
- A Bilibili video page: the subtitle panel shows the site's subtitles. The track is picked like
  YouTube's (the chosen original language, else the learning language, else Chinese for Bilibili
  China and the first listed for International); the gear lists the other tracks.
- No subtitles: Bilibili China signed out says "Sign in to Bilibili to load its subtitles" with the
  usual no-captions card; otherwise "This video has no subtitles".

## Design

- `data/BilibiliVideos.kt`: `VideoSite` (YOUTUBE, BILIBILI, BILIBILI_TV) and video keys that are
  file-name safe like YouTube ids: `bili-<BVid>` (+ `-p<N>` for a later page),
  `bilitv-ep<id>` for an episode, `bilitv-av<id>` for a user video. `VideoKeys.fromUrl(url)` reads
  YouTube watch URLs (unchanged ids) and Bilibili video URLs; `watchUrl(key, seconds)` builds the
  page URL back for saved words, Anki links and tabs.
- `data/BilibiliCaptionProvider.kt` (OkHttp, the web view's cookies):
  - China: `x/web-interface/view` → `cid` of the page; `x/web-interface/nav` → WBI keys (kept an
    hour); signed `x/player/wbi/v2` → subtitle list; each `subtitle_url` is Bilibili's JSON
    (`body: [{from, to, content}]`).
  - International: `intl/gateway/web/v2/subtitle` with `episode_id` or `aid` → list of `.ass` or
    `.json` files; ASS dialogue lines are read with their timing, styling codes removed.
  - Language codes map to the app's (`ai-zh` and `zh-CN`/`zh-Hans` → `zh`, `zh-Hant` → `zh-TW`).
- `SiteCaptionProvider` sends Bilibili keys to the Bilibili provider and YouTube ids to the existing
  page-first provider; `RecentCaptionTracks` caches both.
- Browser: `classifyMainFrameUrl` gains `BILIBILI_WEB` for bilibili.com, m.bilibili.com,
  passport.bilibili.com, b23.tv, bilibili.tv and passport.bilibili.tv while their switch is on.
  The playback snapshot accepts those hosts (the `<video>` element is all it reads). YouTube's
  caption-hiding, ad and page-transcript scripts stay host-gated to YouTube.
- `ui/BetaSettings.kt`: preferences `beta_bilibili`, `beta_bilibili_tv` (off), first-open flags,
  the section (with `TvLayoutSettingsSection` last), and `BetaSites`, which the web view's rules read.
  The New tab menu is in `YouTubeTabsUi.kt`; with Beta off the old rows stay as they were.
- No subtitles on a Bilibili video: `BilibiliNoSubtitlesCard` (sign-in text when Bilibili asked for
  it, Try again) instead of the YouTube no-captions card with AI subtitles.

## Acceptance criteria / validation

- `BilibiliVideosTest`: keys from www/m bilibili.com (`?p=2`), b23.tv is not a video, bilibili.tv
  `/en/video/<aid>`, `/en/play/<season>/<ep>`; YouTube ids unchanged; `watchUrl` round trips.
- `BilibiliCaptionProviderTest` (MockWebServer fixtures): WBI signature matches a known example;
  China subtitle JSON → cues; signed out → the sign-in error; International ASS and JSON → cues;
  language mapping and track pick.
- Navigation (in `BetaSitesTest`): Bilibili hosts stay inside only when their switch is on;
  `bilibili://` and `intent://` are blocked.
- `BetaSitesTest` (unit): the same rules for `classifyMainFrameUrl`, tab names and restored tabs.
- `BetaSettingsUiTest` (+ screenshots `settings-beta`, `new-tab-chooser`, `bilibili-sign-in-card`).
- Device check on a real phone with a signed-in Bilibili account: owner (not possible from cloud).

## Results

See the PR.
