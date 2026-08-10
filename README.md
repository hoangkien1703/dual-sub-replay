# DualSub Replay

DualSub Replay is an experimental Android language-learning app inspired by the useful parts of 1Letters: open a YouTube video, read the original and Vietnamese subtitles together, and tap any paragraph to hear it again.

## MVP features

- Open a video by sharing it from YouTube or pasting its URL.
- Browse the real mobile YouTube site inside the app and tap a video to select it.
- Play through YouTube's embedded IFrame player.
- Retrieve manual or auto-generated public captions automatically.
- Parse YouTube's default timed-text XML, nested SRV3 XML, and JSON3 caption formats.
- Prefer English or Japanese captions and translate them to Vietnamese on-device.
- Merge short caption cues into readable paragraphs.
- Highlight the current paragraph and keep it in view.
- Tap a paragraph or its play button to seek to its starting timestamp and continue playback.
- Remember the last URL and subtitle text size.
- Build an installable preview APK automatically with GitHub Actions.

## Important limitations

YouTube's official Data API does not allow ordinary viewers to download captions from arbitrary public videos. To provide automatic captions, this prototype uses YouTube's undocumented Innertube transcript endpoint. It can stop working when YouTube changes its internal API, and its use may be restricted by YouTube's terms. The extraction code is isolated in `YouTubeCaptionProvider` so it can be replaced without rewriting the app.

The app does not download video or audio, remove ads, enable background playback, or place controls over the YouTube player. Video playback remains inside the embedded YouTube player.

The preview APK uses a CI debug signature. A stable release-signing keystore must be configured through GitHub Secrets before production distribution; never commit a keystore or password.

## User flow

1. In the YouTube app, open a captioned English or Japanese video.
2. Tap **Share** and choose **DualSub Replay**.
3. Wait for captions and the first on-device translation model download.
4. Tap any subtitle card to replay that paragraph once and continue.

You can also paste a YouTube watch, short, live, embed, or `youtu.be` URL into the app.

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
- `AppViewModel` coordinates loading, translation, persistence, and active-cue tracking.
- `DualSubApp` renders the Compose UI and controls the embedded player.

## Privacy

No account or API key is required. YouTube receives normal player and transcript requests. ML Kit downloads translation models from Google, then performs translation on the device. The app stores only the last entered URL and text-size setting in local app preferences.

## License

MIT. This project is independently implemented and is not affiliated with 1Letters, YouTube, or Google.
