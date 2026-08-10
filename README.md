# DualSub Replay

DualSub Replay is an experimental Android language-learning app inspired by the useful parts of 1Letters: open a YouTube video, read the original and Vietnamese subtitles together, and tap any paragraph to hear it again.

## Features

- Open directly to the real mobile YouTube home page.
- Browse, search, open videos, read comments, and choose recommendations without leaving the app.
- Detect watch-page navigation and load captions automatically.
- Retrieve manual or auto-generated public captions automatically.
- Parse YouTube's default timed-text XML, nested SRV3 XML, and JSON3 caption formats.
- Prefer English or Japanese captions and translate them to Vietnamese on-device.
- Merge short caption cues into readable paragraphs.
- Show dual subtitles in a compact drawer over the lower part of the YouTube page.
- Anchor the drawer directly below the visible video and use all remaining screen height.
- Choose a full-width 16:9, 4:3, 3:4, or 1:1 player on portrait watch pages without cropping the video.
- Resize the subtitle area from 35–100% of the space below the player; it remains attached to the player and never overlaps it.
- Use explicit high-contrast subtitle colors for reliable dark-mode readability.
- Hide the drawer to restore the normal full-page YouTube experience, then reopen it from a floating CC button.
- Continue tracking playback while the subtitle drawer is hidden.
- Highlight the current paragraph and tap any paragraph to replay it in the webpage player.
- Remember the subtitle text size.
- Build an installable preview APK automatically with GitHub Actions.

## Important limitations

YouTube's official Data API does not allow ordinary viewers to download captions from arbitrary public videos. To provide automatic captions, this prototype uses YouTube's undocumented Innertube transcript endpoint. It can stop working when YouTube changes its internal API, and its use may be restricted by YouTube's terms. The extraction code is isolated in `YouTubeCaptionProvider` so it can be replaced without rewriting the app.

The app does not download video or audio, remove ads, or enable background playback. Video playback remains inside YouTube's mobile webpage; the app only reads playback time and seeks after a user taps a subtitle paragraph. Because this bridge depends on YouTube's webpage structure, it may require maintenance when that structure changes.

The preview APK uses a CI debug signature persisted in the private GitHub Actions cache so later preview updates keep the same signature. A stable release-signing keystore must be configured through GitHub Secrets before production distribution; never commit a keystore or password.

## User flow

1. Open DualSub Replay; the YouTube home page appears immediately.
2. Browse or search normally and choose a captioned English or Japanese video.
3. The dual-subtitle drawer opens automatically while captions and translations load.
4. Tap any subtitle paragraph to replay it once and continue.
5. Hide the drawer to browse comments or choose the next video, then use the floating CC button to reopen it.

Sharing a YouTube watch, short, live, embed, or `youtu.be` URL to DualSub Replay also navigates directly to that video.

## Development

Requirements:

- Android Studio compatible with Android Gradle Plugin 9.3
- JDK 17
- Android SDK 36
- Gradle 9.5 (the CI workflow generates the wrapper before building)

Build from Android Studio, or with Gradle installed:

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

- `YouTubeUrlParser` normalizes shared links and extracts video IDs.
- `YouTubeCaptionProvider` discovers and downloads timed caption cues.
- `SubtitleMerger` converts small cues into replayable paragraphs.
- `OnDeviceTranslator` uses Google ML Kit to translate to Vietnamese.
- `AppViewModel` follows browser navigation and coordinates loading, translation, persistence, and active-cue tracking.
- `YouTubeWebPage` hosts the real mobile YouTube site, measures the visible player, optimizes its portrait layout, and bridges playback timing and seek commands.
- `DualSubApp` renders the compact, hideable Compose subtitle drawer over the YouTube page.

## Privacy

No account or API key is required. YouTube receives normal player and transcript requests. ML Kit downloads translation models from Google, then performs translation on the device. The app stores only the last entered URL and text-size setting in local app preferences.

## License

MIT. This project is independently implemented and is not affiliated with 1Letters, YouTube, or Google.
