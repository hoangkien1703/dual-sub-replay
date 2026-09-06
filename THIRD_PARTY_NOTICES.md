# Distribution and source notices

DualSub Replay copyright (c) 2026 Hoang Trung Kien. Original repository source is released under the [MIT License](LICENSE).

Offline video downloading (`yt-dlp`, `FFmpeg`), local media playback (`Media3`), and `WorkManager` were removed to simplify the app and drastically reduce APK size.

## Historical release notices (v0.9.7 and earlier)

For historical releases (v0.9.7 and earlier) that bundled the optional downloader:
- Combined applications were distributed under GNU GPL version 3.
- `io.github.junkfood02.youtubedl-android:library:0.18.1` and `:ffmpeg:0.18.1`: GPL-3.0 Android wrapper, by yausername, JunkFood02, xibr, and contributors. [Versioned source](https://github.com/yausername/youtubedl-android/tree/0.18.1), [source archive](https://github.com/yausername/youtubedl-android/archive/refs/tags/0.18.1.tar.gz), [license](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE).
- Upstream terms for bundled yt-dlp, CPython, QuickJS, FFmpeg continue to apply for those releases. [Python build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) and [FFmpeg build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) describe the build process.
- Media3 ExoPlayer/UI 1.5.1 and WorkManager 2.10.1: Android Open Source Project contributors, Apache-2.0. Sources from Google Maven and [AndroidX](https://android.googlesource.com/platform/frameworks/support/).

## Current dependencies

Current releases bundle only:
- AndroidX / Jetpack Compose libraries: Apache-2.0.
- Google ML Kit Translate: Google APIs Terms of Service / Apache-2.0.
- Square OkHttp: Apache-2.0.
- Kotlin / Coroutines: Apache-2.0.

## Obtain and build this application's source

The complete application source and build scripts are available without charge at https://github.com/hoangkien1703/dual-sub-replay. For a PR preview, select the PR's exact commit; for a tagged release, select that tag. GitHub provides downloadable source archives for both commits and tags. Keep these source links beside redistributed APKs and retain upstream notices and access to the corresponding dependency sources.

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. Run `bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Dependencies are pinned in `app/build.gradle.kts` and resolved from Maven Central/Google Maven. Building a modified debug APK requires no production signing key. Install with Android's normal APK installation flow or `adb install`; uninstall a differently signed build first if Android reports a signature mismatch (uninstalling erases local data).

The release signing keys are not necessary to build or install modified versions. This application does not restrict installation of user-built variants.
