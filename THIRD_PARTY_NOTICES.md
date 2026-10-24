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

- English learner data (data files only, no library code), copied at build time from Maven Central into the APK's `assets/learner-data/`:
  - Apache OpenNLP's English part-of-speech model (`opennlp-en-ud-ewt-pos-1.3-2.5.4.bin` in `org.apache.opennlp:opennlp-models-pos-en:1.3.0`): Apache-2.0, copyright The Apache Software Foundation. It was trained on the Universal Dependencies English Web Treebank (CC BY-SA 4.0); the Apache OpenNLP project releases the trained model under Apache-2.0.
  - WordNet 3.1's single-word lists and irregular-form lists (`index.*` and `*.exc` in `net.sf.extjwnl:extjwnl-data-wn31:1.2`), reduced to words and their parts of speech: WordNet License, WordNet 3.0 Copyright 2006 by Princeton University. All rights reserved. The licence text is on the app's licence screen.
- English word levels (`app/src/main/learner-data/en-levels.txt`, packaged in the same folder) come from the CEFR-J Wordlist Version 1.5, compiled by Yukio Tono, Tokyo University of Foreign Studies ([cefr-j.org](http://www.cefr-j.org/download.html), via [Open Language Profiles](https://github.com/openlanguageprofiles/olp-en-cefrj)), reduced to word, part of speech and level. The CEFR-J vocabulary and grammar profiles may be used for research and commercial purposes with no charge, provided the dataset is cited. The CEFR levels of the English grammar points follow the CEFR-J Grammar Profile Version 20180315 (only the levels are used; the profile is not part of the app).

- Chinese learner data (data files only, no library code), copied at build time from Maven Central into the APK's `assets/learner-data/`:
  - jieba's Chinese word list (`dict.txt` in `com.huaban:jieba-analysis:1.0.2`, an Apache-2.0 jar): MIT, copyright (c) 2013 Sun Junyi. The app keeps the words seen at least 4 times, sorted.
  - HanLP's pinyin and traditional-to-simplified tables (`pinyin.txt`, `t2s.txt` in `com.hankcs:hanlp:portable-1.8.6`): Apache-2.0, copyright hankcs.

The HSK levels of the Chinese grammar points are taken from the grammar syllabus and word list of the *Chinese Proficiency Grading Standards for International Chinese Language Education* (GF0025-2021), as published in [ivankra/hsk30](https://github.com/ivankra/hsk30) (MIT). Only the level numbers are used; the lists are not part of the app.

Study tools (2026-10-20) include no third-party code or data. Load captions with a page token (beta) follows the proof-of-origin token flow that LuanRT's [BgUtils](https://github.com/LuanRT/BgUtils) (MIT) documents, written for this app; it uses YouTube's own public web values that the web player sends. Send to AnkiDroid talks to AnkiDroid's public content provider directly, without the AnkiDroid API library. Send to AnkiConnect (2026-10-23) speaks AnkiConnect's public JSON protocol to the phone's own address; no code of AnkiConnect or AnkiConnect Android (GPL-3.0) is used. The play modes, known words and blur box are ideas seen in Migaku and jidoujisho (GPL-3.0); none of their code, assets, data, keys or endpoints are used.

The app's licence screen (Settings → Licenses) shows the MIT licence, the libraries and data above with the Apache-2.0 text, jieba's MIT notice and the WordNet License, and the ML Kit translation terms.

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

## Obtain and build this application's source

The complete application source and build scripts are available without charge at https://github.com/hoangkien1703/dual-sub-replay. For a PR preview, select the PR's exact commit; for a tagged release, select that tag. GitHub provides downloadable source archives for both commits and tags. Keep these source links beside redistributed APKs and retain upstream notices and access to the corresponding dependency sources.

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. Run `bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`. Dependencies are pinned in `app/build.gradle.kts` and resolved from Maven Central/Google Maven. Building a modified debug APK requires no production signing key. Install with Android's normal APK installation flow or `adb install`; uninstall a differently signed build first if Android reports a signature mismatch (uninstalling erases local data).

The release signing keys are not necessary to build or install modified versions. This application does not restrict installation of user-built variants.
