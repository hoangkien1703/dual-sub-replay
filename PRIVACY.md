# Privacy overview

DualSub Replay is designed without an application account, analytics SDK, advertising SDK, or project-operated server.

## Information handled by the app

- The embedded YouTube website receives normal browsing and playback requests, subject to Google's and YouTube's policies.
- The app requests public caption data from YouTube to build the replayable subtitle timeline. It keeps the caption tracks of up to six recently watched videos in its private cache for a day, so reopening one does not download it again; Android may clear that cache at any time.
- Google ML Kit may download the language models you select. Translation then runs on the device.
- In the GitHub build, Settings → Translation → Google Translate (online) is on by default: the subtitle text and words being translated are sent to Google Translate (`translate.googleapis.com`) with the source and target language, and Google's privacy policy applies to them. This uses Google's free web endpoint, not an official API, so Google may limit or stop it. Translations are cached in the app's private cache. Turning the switch off sends nothing more and translates on the device. The F-Droid build does not have this switch and never sends subtitle text to Google.
- The F-Droid build does not include ML Kit. It downloads Mozilla's Firefox Translations models from Mozilla's servers the first time you translate a language, then translates on the device. Only model files are downloaded; subtitle text is not sent.
- The first time Japanese subtitles load, the app downloads the Japanese word dictionary (the unchanged 13 MB Kuromoji IPADIC file) from Maven Central (`repo1.maven.org`) or, if that fails, Google's mirror of it (`maven-central.storage-download.googleapis.com`). The request asks only for that file; no subtitle text is sent. The file is checked against a pinned SHA-256 checksum, kept in the app's private storage, and used to split Japanese text into words on the device.
- The app stores the last Browse URL, target language, subtitle text size, and landscape split ratio in local Android preferences.
- Saved words, meanings, subtitle context, video IDs, timestamp ranges, and review schedules are stored in a local SQLite database. They are not sent to an application server. Android's normal app backup may include this database and preferences.
- Progress stores, per day and language, how long videos played in the app and how many videos were watched, in a separate local SQLite database, plus your daily goal in local preferences. Video IDs and titles are not stored for Progress, and nothing is sent to an application server. Android's normal app backup may include this database.
- Saved words replay their moment in the same embedded YouTube page. The app no longer downloads video clips; the offline clip downloader of earlier versions has been removed.
- Pronunciation sends the selected word and language to the device's preferred Android text-to-speech engine and, if needed, other installed speech engines. It prefers installed offline voices but may try a supported online voice when local speech fails or is unavailable. Online voices process the word according to the speech engine provider's settings and privacy policy. The spoken word is recorded to a temporary file in the app's cache so it can replay instantly. It is deleted when you select or pronounce another word or close the app, and any file left behind is removed the next time a word is spoken.
- The Japanese dictionary and the F-Droid build's translation models are excluded from Android backups and device transfers, because they can be downloaded again. The embedded browser's data, including its sign-in cookies, is excluded too. Saved words, Progress, and preferences stay in backups.
- If you use the experimental Google/YouTube sign-in flow, authentication occurs inside YouTube's embedded web experience and WebView cookies persist locally. They are excluded from Android backups and device transfers, so after a restore or a move to a new phone you sign in again. Google may reject embedded-WebView sign-in.
- The GitHub build turns on WebView Safe Browsing, so on devices that use Google's WebView, the pages you open are checked against Google's list of unsafe sites. The F-Droid build turns Safe Browsing off.

## What the project does not do

- It does not require an API key or DualSub Replay account.
- It does not send subtitle text or translation requests to a server operated by this project. Subtitle text goes to Google only while Google Translate (online) is on, which is the GitHub build's default.
- It does not download video or audio from YouTube.
- It does not intentionally collect analytics, advertising identifiers, or crash telemetry.

Deleting the app clears its local app data under Android's normal uninstall behavior. You can also clear the app's storage from Android Settings.

This overview describes the current open-source implementation. Review the source and release notes before installing if your privacy requirements are strict.
