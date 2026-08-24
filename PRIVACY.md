# Privacy overview

DualSub Replay is designed without an application account, analytics SDK, advertising SDK, or project-operated server.

## Information handled by the app

- The embedded YouTube website receives normal browsing and playback requests, subject to Google's and YouTube's policies.
- The app requests public caption data from YouTube to build the replayable subtitle timeline.
- Google ML Kit may download the language models you select. Translation then runs on the device.
- The app stores the last Browse URL, target language, subtitle text size, and landscape split ratio in local Android preferences.
- If you use the experimental Google/YouTube sign-in flow, authentication occurs inside YouTube's embedded web experience and WebView cookies persist locally. Google may reject embedded-WebView sign-in.

## What the project does not do

- It does not require an API key or DualSub Replay account.
- It does not send subtitle text or translation requests to a server operated by this project.
- It does not download YouTube video or audio.
- It does not intentionally collect analytics, advertising identifiers, or crash telemetry.

Deleting the app clears its local app data under Android's normal uninstall behavior. You can also clear the app's storage from Android Settings.

This overview describes the current open-source implementation. Review the source and release notes before installing if your privacy requirements are strict.
