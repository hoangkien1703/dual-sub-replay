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
- Kuromoji 0.9.0 (`com.atilika.kuromoji:kuromoji-ipadic`, code only): Apache-2.0, copyright 2010-2015 Atilika Inc. and contributors. Its license and notice files are kept in the APK under `META-INF/`.

The F-Droid build (`-Pdistribution=fdroid`) leaves out Google ML Kit Translate and contains only free software. Instead it bundles a native translation engine built from the git submodules in `app/src/fdroid/cpp`:
- Bergamot translator and Marian (mozilla/translations `inference/`): MPL-2.0 and MIT.
- Bundled with Marian: SentencePiece and ruy (Apache-2.0), cpuinfo, pathie-cpp and simd_utils (BSD-2-Clause), intgemm, yaml-cpp and faiss (MIT), zlib (zlib).
- ssplit-cpp sentence splitter: Apache-2.0. PCRE2 10.44: BSD-3-Clause with the PCRE2 exception.

It downloads Mozilla's Firefox Translations models, which are distributed under MPL-2.0.

## Japanese dictionary (downloaded on first use)

The IPADIC dictionary that Kuromoji uses is not bundled in the APK. The first time Japanese subtitles load, the app downloads the same `kuromoji-ipadic-0.9.0.jar` from Maven Central, checks its size and SHA-256, and stores it in the app's private storage (see [PRIVACY.md](PRIVACY.md)). The jar contains data from `mecab-ipadic-2.7.0-20070801` ([source archive](http://atilika.com/releases/mecab-ipadic/mecab-ipadic-2.7.0-20070801.tar.gz)), distributed under this notice:

    Nara Institute of Science and Technology (NAIST),
    the copyright holders, disclaims all warranties with regard to this
    software, including all implied warranties of merchantability and
    fitness, in no event shall NAIST be liable for
    any special, indirect or consequential damages or any damages
    whatsoever resulting from loss of use, data or profits, whether in an
    action of contract, negligence or other tortuous action, arising out
    of or in connection with the use or performance of this software.

    A large portion of the dictionary entries
    originate from ICOT Free Software.  The following conditions for ICOT
    Free Software applies to the current dictionary as well.

    Each User may also freely distribute the Program, whether in its
    original form or modified, to any third party or parties, PROVIDED
    that the provisions of Section 3 ("NO WARRANTY") will ALWAYS appear
    on, or be attached to, the Program, which is distributed substantially
    in the same form as set out herein and that such intended
    distribution, if actually made, will neither violate or otherwise
    contravene any of the laws and regulations of the countries having
    jurisdiction over the User or the intended distribution itself.

    NO WARRANTY

    The program was produced on an experimental basis in the course of the
    research and development conducted during the project and is provided
    to users as so produced on an experimental basis.  Accordingly, the
    program is provided without any warranty whatsoever, whether express,
    implied, statutory or otherwise.  The term "warranty" used herein
    includes, but is not limited to, any warranty of the quality,
    performance, merchantability and fitness for a particular purpose of
    the program and the nonexistence of any infringement or violation of
    any right of any third party.

    Each user of the program will agree and understand, and be deemed to
    have agreed and understood, that there is no warranty whatsoever for
    the program and, accordingly, the entire risk arising from or
    otherwise connected with the program is assumed by the user.

    Therefore, neither ICOT, the copyright holder, or any other
    organization that participated in or was otherwise related to the
    development of the program and their respective officials, directors,
    officers and other employees shall be held liable for any and all
    damages, including, without limitation, general, special, incidental
    and consequential damages, arising out of or otherwise in connection
    with the use or inability to use the program or any product, material
    or result produced or otherwise obtained by using the program,
    regardless of whether they have been advised of, or otherwise had
    knowledge of, the possibility of such damages at any time during the
    project or thereafter.  Each user will be deemed to have agreed to the
    foregoing by his or her commencement of use of the program.  The term
    "use" as used herein includes, but is not limited to, the use,
    modification, copying and distribution of the program and the
    production of secondary products from the program.

    In the case where the program, whether in its original form or
    modified, was distributed or delivered to or received by a user from
    any person, organization or entity other than ICOT, unless it makes or
    grants independently of ICOT any specific warranty to the user in
    writing, such person, organization or entity, will also be exempted
    from and not be held liable to the user for any such damages as noted
    above as far as the program is concerned.

The F-Droid build downloads the same dictionary.

## Obtain and build this application's source

The complete application source and build scripts are available without charge at https://github.com/hoangkien1703/dual-sub-replay. For a PR preview, select the PR's exact commit; for a tagged release, select that tag. GitHub provides downloadable source archives for both commits and tags. Keep these source links beside redistributed APKs and retain upstream notices and access to the corresponding dependency sources.

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. Run `bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Dependencies are pinned in `app/build.gradle.kts` and resolved from Maven Central/Google Maven. Building a modified debug APK requires no production signing key. Install with Android's normal APK installation flow or `adb install`; uninstall a differently signed build first if Android reports a signature mismatch (uninstalling erases local data).

The release signing keys are not necessary to build or install modified versions. This application does not restrict installation of user-built variants.
