# DualSub Replay

DualSub Replay is an experimental Android language-learning app inspired by the useful parts of 1Letters: find a YouTube video, read the original and Vietnamese subtitles together, and tap any paragraph to hear it again.

## Features

- Browse and search the real mobile YouTube site inside the app.
- Open watch, Shorts, live, embed, and `youtu.be` links in a dedicated Learning screen.
- Play videos through YouTube's official IFrame API in a full-width 16:9 Android-owned container.
- Preserve YouTube's native controls, fullscreen behavior, aspect ratio, and letterboxing for vertical video.
- Retrieve manual or auto-generated public captions automatically.
- Parse YouTube's default timed-text XML, nested SRV3 XML, and JSON3 caption formats.
- Prefer English or Japanese captions and translate them to Vietnamese on-device.
- Merge short caption cues into readable paragraphs.
- Place the dual-subtitle timeline below the player so it never obscures YouTube controls.
- Highlight the current paragraph and tap any paragraph to replay it.
- Hide the subtitle timeline to reveal the scrollable YouTube watch page, including video
  actions, comments, and recommendations; selecting another video keeps the learning flow.
- Reopen the subtitle timeline and remember the subtitle text size.
- Build and test an installable preview APK automatically with GitHub Actions.

## User flow

1. Open DualSub Replay; the YouTube Browse screen appears immediately.
2. Search normally and choose a captioned English or Japanese video.
3. The app intercepts the video URL and opens the dedicated Learning screen.
4. The full-width player cues the video while captions and translations load underneath it.
5. Tap any subtitle paragraph to seek to its start and resume playback.
6. Hide the subtitle timeline to like the video, read comments, or choose another video.
7. Press Back to leave fullscreen first, then return from Learning to Browse.

Sharing a YouTube watch, Short, live, embed, or `youtu.be` URL to DualSub Replay opens it directly in Learning.

## Important limitations

YouTube's official Data API does not allow ordinary viewers to download captions from arbitrary public videos. To provide automatic captions, this prototype uses YouTube's undocumented Innertube transcript endpoint. It can stop working when YouTube changes its internal API, and its use may be restricted by YouTube's terms. The extraction code is isolated in `YouTubeCaptionProvider` so it can be replaced without rewriting the app.

Video playback uses YouTube's IFrame Player API through `android-youtube-player`. The app does not download video or audio, remove ads, obscure the player controls, or enable background playback. Some videos cannot be embedded because of owner, region, age, or account restrictions; the Learning screen offers to open those videos in YouTube.

The preview APK uses a CI debug signature persisted in the private GitHub Actions cache so later preview updates keep the same signature. A stable release-signing keystore must be configured through GitHub Secrets before production distribution; never commit a keystore or password.

## Development

Requirements:

- Android Studio compatible with Android Gradle Plugin 9.3
- JDK 17
- Android SDK 36
- An API 36 AOSP x86_64 system image for managed-device tests

The Gradle 9.5 wrapper is committed, so a separate Gradle installation is not required.

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat pixel2Api36DebugAndroidTest
```

On Linux or macOS:

```bash
bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
bash ./gradlew pixel2Api36DebugAndroidTest
```

The managed-device suite uses a Pixel 2 profile with an API 36 AOSP image. Its WebView fixtures are designed to run without calls to the live YouTube site. On headless CI hosts, also pass `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`.

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

- `YouTubeUrlParser` normalizes shared links and extracts video IDs.
- `YouTubeCaptionProvider` discovers and downloads timed caption cues.
- `SubtitleMerger` converts small cues into replayable paragraphs.
- `OnDeviceTranslator` uses Google ML Kit to translate to Vietnamese.
- `AppViewModel` owns Browse/Learning navigation, caption loading, translation, and active-cue tracking.
- `YouTubeBrowsePage` hosts the Browse experience and intercepts video navigation without injecting playback scripts.
- `EmbeddedYouTubePlayer` owns the IFrame player lifecycle and reports app-owned playback events.
- `DualSubApp` renders either Browse or the Learning player with the subtitle timeline below it.

## Continuous integration

Every push and pull request uses the committed wrapper to run unit tests, lint, debug APK assembly, and Android-test APK assembly. A second job executes the offline fixture suite on the API 36 managed device. A push to `main` publishes `DualSub-Replay-v0.2.8-preview.apk` to the rolling `preview` release only after both jobs pass.

## Privacy

No account or API key is required. YouTube receives normal player and transcript requests. ML Kit downloads translation models from Google, then performs translation on the device. The app stores the last Browse URL and text-size setting in local app preferences.

## License

MIT. This project is independently implemented and is not affiliated with 1Letters, YouTube, or Google.
