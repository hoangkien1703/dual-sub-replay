# Distribution and source notices

DualSub Replay copyright (c) 2026 Hoang Trung Kien. Original repository source retains the MIT permissions and notice in LICENSE. The application combining that source with the GPL-3.0 downloader is distributed as a whole under GNU GPL version 3, without warranty. You may copy, modify, and redistribute the combined application under those terms. The full GPL text is packaged at `app/src/main/assets/licenses/GPL-3.0.txt` and accessible in Settings.

The vocabulary/downloader integration was added in September 2026. The upstream download libraries are used without source modifications.

## Download components

- `io.github.junkfood02.youtubedl-android:library:0.18.1` and `:ffmpeg:0.18.1`: GPL-3.0 Android wrapper, by yausername, JunkFood02, xibr, and contributors. [Versioned source](https://github.com/yausername/youtubedl-android/tree/0.18.1), [source archive](https://github.com/yausername/youtubedl-android/archive/refs/tags/0.18.1.tar.gz), [license](https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE).
- The wrapper bundles yt-dlp, CPython, QuickJS, FFmpeg, and their native dependencies. Their upstream terms continue to apply. The wrapper's [Python build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md) and [FFmpeg build instructions](https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_FFMPEG.md) describe the native package build process. Native package recipes and patches are maintained in [Termux packages](https://github.com/termux/termux-packages). The bundled yt-dlp build is maintained in [ytdlp-lazy](https://github.com/xibr/ytdlp-lazy); upstream [yt-dlp source and third-party licenses](https://github.com/yt-dlp/yt-dlp) remain available.
- Media3 ExoPlayer/UI 1.5.1 and WorkManager 2.10.1: Android Open Source Project contributors, Apache-2.0. Sources and source JARs are available from Google's Maven repository and [AndroidX](https://android.googlesource.com/platform/frameworks/support/).

## Obtain and build this application's source

The complete application source and build scripts are available without charge at https://github.com/hoangkien1703/dual-sub-replay. For a PR preview, select the PR's exact commit; for a tagged release, select that tag. GitHub provides downloadable source archives for both commits and tags. Keep these source links beside redistributed APKs and retain upstream notices and access to the corresponding dependency sources.

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. Run `bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Dependencies are pinned in `app/build.gradle.kts` and resolved from Maven Central/Google Maven. Building a modified debug APK requires no production signing key. Install with Android's normal APK installation flow or `adb install`; uninstall a differently signed build first if Android reports a signature mismatch (uninstalling erases local data).

The release signing keys are not necessary to build or install modified versions. This application does not restrict installation of user-built variants.
