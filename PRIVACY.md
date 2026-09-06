# Privacy overview

DualSub Replay is designed without an application account, analytics SDK, advertising SDK, or project-operated server.

## Information handled by the app

- The embedded YouTube website receives normal browsing and playback requests, subject to Google's and YouTube's policies.
- The app requests public caption data from YouTube to build the replayable subtitle timeline.
- Google ML Kit may download the language models you select. Translation then runs on the device.
- The app stores the last Browse URL, target language, subtitle text size, and landscape split ratio in local Android preferences.
- Saved words, meanings, subtitle context, video IDs, timestamp ranges, and review schedules are stored in a local SQLite database. They are not sent to an application server. Android's normal app backup may include this database and preferences.
- Optional offline clip downloads send video requests to YouTube and its media hosts using the bundled yt-dlp downloader. Downloads do not copy cookies from the browsing WebView. Clip files are stored privately and excluded from Android backups. Restored cards may require their clips to be downloaded again.
- Pronunciation sends the selected word and language to the device's configured Android text-to-speech engine. That engine may use an online voice according to its own settings and privacy policy.
- If you use the experimental Google/YouTube sign-in flow, authentication occurs inside YouTube's embedded web experience and WebView cookies persist locally. Google may reject embedded-WebView sign-in.

## What the project does not do

- It does not require an API key or DualSub Replay account.
- It does not send subtitle text or translation requests to a server operated by this project.
- It does not download media automatically during browsing; users explicitly request offline vocabulary clips.
- It does not intentionally collect analytics, advertising identifiers, or crash telemetry.

Deleting the app clears its local app data under Android's normal uninstall behavior. You can also clear the app's storage from Android Settings.

This overview describes the current open-source implementation. Review the source and release notes before installing if your privacy requirements are strict.
