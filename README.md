# DualSub Replay

An Android language-learning app for watching YouTube with original and translated subtitles together. Tap any subtitle paragraph to replay that moment.

## App preview

<p align="center">
  <img src="docs/images/dualsub-replay-preview.jpg" alt="DualSub Replay showing YouTube with original and translated subtitles in a replayable bottom panel" width="280">
</p>

## Download

### [Download the latest official APK](https://github.com/hoangkien1703/dual-sub-replay/releases/latest/download/DualSub-Replay.apk)

Requires **Android 8.0 or newer**. No account or API key is required.

### Installation

1. Tap the download link above and open `DualSub-Replay.apk` when it finishes.
2. If Android asks, allow your browser or file manager to **install unknown apps**.
3. Tap **Install**, then open DualSub Replay.

Android may show a standard warning because the app is downloaded directly from GitHub instead of Google Play.

> **Updating from a preview?** Uninstall the preview build once before installing the official app. Official releases use a new production signing key; future official versions will install as normal updates.

Want to test the newest development build? See the [preview release](https://github.com/hoangkien1703/dual-sub-replay/releases/tag/preview). Preview builds may be less stable and use a different signature.

## Features

- Browse and search the real mobile YouTube site inside the app.
- Open watch, Shorts, live, embed, and `youtu.be` links in the same persistent YouTube WebView.
- Play videos with YouTube's native mobile webpage player and controls.
- Retrieve manual or auto-generated public captions automatically.
- Parse YouTube's default timed-text XML, nested SRV3 XML, and JSON3 caption formats.
- Choose from the caption languages offered by each video and translate into any language supported by Google ML Kit.
- Download translation models on demand and remember the preferred target language.
- Merge short caption cues into readable paragraphs.
- Place the dual-subtitle timeline in a temporary bottom layer over the single YouTube page.
- Highlight the current paragraph and tap any paragraph to replay it.
- Swipe the panel header down or use its close button to reveal the complete YouTube page, including the same video,
  actions, comments, and recommendations; no second player or webpage is created.
- Reopen the subtitle timeline and remember the subtitle text size.
- Build, test, and publish installable APKs automatically with GitHub Actions.

## User flow

1. Open DualSub Replay; the YouTube Browse screen appears immediately.
2. Search normally and choose a captioned video.
3. The selected watch page stays in the same WebView while captions and translations load.
4. The bottom dual-subtitle layer tracks the native webpage video's playback time.
5. Tap any subtitle paragraph to seek to its start and resume playback.
6. Swipe the subtitle timeline down to like the video, read comments, or choose another video.
7. Press Back to navigate through normal YouTube browsing history.

Sharing a YouTube watch, Short, live, embed, or `youtu.be` URL to DualSub Replay navigates the same WebView directly to it.

## Important limitations

YouTube's official Data API does not allow ordinary viewers to download captions from arbitrary public videos. To provide automatic captions, this prototype uses YouTube's undocumented Innertube transcript endpoint. It can stop working when YouTube changes its internal API, and its use may be restricted by YouTube's terms. The extraction code is isolated in `YouTubeCaptionProvider` so it can be replaced without rewriting the app.

Video playback uses the native player in YouTube's mobile webpage. The app does not download video or audio, remove ads, or enable background playback. The subtitle layer can be hidden at any time to restore the unobstructed YouTube page.

Official APKs use a dedicated production signing key kept outside the repository and restored through encrypted GitHub Actions secrets. Preview APKs use a separate CI debug signature, so Android treats the preview and official release as different update lines.

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

Debug APKs are produced at `app/build/outputs/apk/debug/app-debug.apk`. An official release build requires the four `ANDROID_RELEASE_*` signing environment variables and `-PrequireReleaseSigning=true`; signing credentials must never be committed.

## Architecture

- `YouTubeUrlParser` normalizes shared links and extracts video IDs.
- `YouTubeCaptionProvider` discovers and downloads timed caption cues.
- `SubtitleMerger` converts small cues into replayable paragraphs.
- `OnDeviceTranslator` uses Google ML Kit to translate between the selected supported languages.
- `AppViewModel` tracks the active watch URL, caption loading, translation, and active cue.
- `SingleYouTubePage` owns the app's only WebView and keeps normal YouTube navigation and playback intact.
- A small JavaScript polling bridge reads the native page video's time and seeks that same video for replay.
- `DualSubApp` layers the hideable dual-subtitle timeline over the bottom of the single YouTube surface.

## Continuous integration

Every push and pull request uses the committed wrapper to run unit tests, lint, debug APK assembly, and Android-test APK assembly. A second job executes the offline fixture suite on the API 36 managed device. A push to `main` updates the rolling `preview` release only after both jobs pass. A version tag such as `v0.3.1` must match the app version and publishes a verified, production-signed APK as the latest official release.

## Privacy

No account or API key is required. YouTube receives normal player and transcript requests. ML Kit downloads only the language models needed for selected translations, then performs translation on the device. The app stores the last Browse URL, target language, and text-size setting in local app preferences.

## License

MIT. This project is independently implemented and is not affiliated with 1Letters, YouTube, or Google.
