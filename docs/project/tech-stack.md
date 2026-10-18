# Technical context

This is a source map and explanation of architectural boundaries. [AGENTS.md](../../AGENTS.md)
contains operational commands and contributor rules. Follow source/build files if a
version snapshot here drifts, and update relevant documentation with lasting changes.

## Stack and authoritative configuration

| Area | Current context | Source of truth |
| --- | --- | --- |
| Android application | `:app`, `com.kienhoang.dualsubreplay`; min SDK 26, compile/target SDK 36 | [App build](../../app/build.gradle.kts) |
| Language/build | Kotlin 2.3.21, AGP 9.3.2, JDK 17; committed Gradle 9.5.0 wrapper | [Root build](../../build.gradle.kts), [wrapper](../../gradle/wrapper/gradle-wrapper.properties), [CI](../../.github/workflows/android.yml) |
| UI | Jetpack Compose / Material 3; Compose BOM 2026.06.01 | [App dependencies](../../app/build.gradle.kts) |
| Translation/network | ML Kit Translate 17.0.3, OkHttp, Kotlin coroutines | [App dependencies](../../app/build.gradle.kts) |
| Japanese words | Kuromoji 0.9.0 (Apache-2.0, pure Java); its 13 MB IPADIC dictionary downloads on first Japanese use | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-09-29-word-audio-jump-back-japanese-words.md) |
| English words | Data files only, extracted at build time into `assets/learner-data/`: Apache OpenNLP's UD English part-of-speech model (Apache-2.0) and WordNet 3.1's word and irregular-form lists (WordNet licence), plus the committed CEFR-J word levels (`app/src/main/learner-data/`, free with citation); no library code | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-10-18-english-learner-help.md) |
| Chinese words | Data files only, extracted at build time into `assets/learner-data/`: jieba's word list (MIT) and HanLP's pinyin and traditional-to-simplified tables (Apache-2.0); no library code | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-10-18-chinese-learner-help.md) |
| Media controls | `androidx.media` 1.7.1 media session and notification (no player library) | [App dependencies](../../app/build.gradle.kts) |
| Tests | JUnit4 4.13.2; Compose/Android instrumentation, API 36 AOSP x86_64 managed Pixel 2 | [App test configuration](../../app/build.gradle.kts) |
| Performance | Separate test-only `:benchmark` module, Macrobenchmark and app Baseline Profile | [Benchmark build](../../benchmark/build.gradle.kts), [methodology](../quality-and-performance.md) |
| Website | Static English/Vietnamese landing page in `site/`, published to GitHub Pages from `main`; offline checks in `tools/tests/test_site.py` | [Pages workflow](../../.github/workflows/pages.yml), [discoverability](../promotion/discoverability.md) |

## Component boundaries

Paths below are relative to
[`app/src/main/java/com/kienhoang/dualsubreplay`](../../app/src/main/java/com/kienhoang/dualsubreplay).

- `ui/DualSubApp.kt` and `ui/LearningPlayerRoot.kt` compose the learning interface.
  `ui/AppViewModel.kt` coordinates selection, caption loading, translation, playback
  state, and preferences rather than owning another video player.
- `ui/YouTubeBrowserScreen.kt` owns `SingleYouTubePage`, the persistent WebView.
  `ui/YouTubeWebScripts.kt` reads/seeks the page's native video and captures eligible
  live caption data. Caption clock, snapshot gating, and presentation helpers keep
  playback observations separate from UI rendering.
- `playback/WebVideoPlaybackService.kt` is a `mediaPlayback` foreground service that owns the
  media session (earphone and headset buttons, lock screen) and the media notification for the
  page video. It is a remote control, not a player: `playback/WebVideoPlayback.kt`'s
  `WebVideoBridge` carries the state `SingleYouTubePage` polls once a second (also off screen) and
  sends play/pause/seek back as page scripts. A playing video is not paused when the app leaves
  the screen, and `WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT` always tells YouTube's page it is visible
  so it keeps playing without Premium too (owner decision 2026-10-13). See the
  [spec](../specs/2026-10-13-background-play-and-earphone-controls.md).
- `data/CaptionProvider.kt` is the provider interface. `YouTubeCaptionProvider.kt`
  discovers caption tracks from watch-page player metadata and undocumented
  Innertube player fallbacks, then fetches timed text. `CaptionDocumentParser.kt`
  handles formats. Do not spread YouTube extraction details into UI/translation code.
  `RecentCaptionTracks.kt` wraps the provider with a small, expiring disk cache of recent tracks,
  so a video reopened after Android closed the app does not download its captions again.
  `PageFirstCaptionProvider.kt` sits under that cache: the YouTube page in the WebView gets the
  chosen track through `PageTranscriptSource.kt` (`ui/PageTranscriptReader.kt` runs the page
  scripts) while `YouTubeCaptionProvider` runs alongside as the fallback; the first success wins.
  The page keeps a copy of the caption replies its own player downloads (they carry the page's
  cookies and proof-of-origin token), and keeps watching after live subtitles take over, so
  captions the player loads later still give the full transcript. See the
  [spec](../specs/2026-10-18-page-first-full-transcript.md).
- `data/SubtitleMerger.kt` prepares readable segments; `SubtitleStore.kt` keeps
  transcript text/timing on disk and exposes bounded playback windows. Preserve
  bounded loading and cancellation when changing long-video behavior.
- `translation/OnDeviceTranslator.kt` uses downloadable ML Kit models with memory
  and disk caches. `ui/PlaybackTranslation.kt` and `TranslationCoordinator.kt`
  support playback-prioritized work; obsolete loads must not update current state.
  It also lists, downloads and removes whole languages for the
  Settings → Translation → Languages on this device screen (`ui/LanguageDownloads.kt`).
- `translation/GoogleWebTranslator.kt` is the default engine (Settings →
  Translation → Google Translate, `translation/TranslationEngine.kt`). It calls Google Translate's
  unofficial web endpoints with its own caches, sending upcoming sentences in one batch request
  (`translatePlaybackWindow`'s `prefetch`) and retrying short outages; `AppViewModel.translateText` routes every
  translation through the chosen engine. `prefetchInBackground` translates minutes 2 to 5 one request
  at a time. A line Google leaves blank is asked again alone and, if still blank, translated on the
  device by itself. A failure moves that video to on-device translation
  (`DualSubUiState.onDeviceFallback`), shows `ui/OnlineTranslationNotice.kt`'s top-right icon, and
  checks Google again every 2 minutes; the next load tries Google first. See the
  [blank-lines spec](../specs/2026-10-09-google-blank-lines-and-five-minutes-ahead.md).
  See the [spec](../specs/2026-10-05-opt-in-google-translate.md).
- `assistant/` is the AI assistant.
  `AiChatClient.kt` speaks the OpenAI-compatible `/chat/completions` format over OkHttp (HTTPS
  only, no redirects, IPv4 first, 1 MiB response cap) for Gemini, OpenRouter, OpenAI, OpenCode Zen
  and Go, or a custom address (`AiProvider.kt`), and lists the service's models from `/models`.
  Claude uses Anthropic's own Messages API (`AiAnthropicMessages.kt`, `x-api-key` header) through
  the same client; an answer that asks for actions goes back unchanged, thinking included, with
  their results.
  `AiAttachmentReader.kt` turns chosen pictures (scaled JPEG), PDFs and text files into message
  parts that live only in memory. `AiKeyStore.kt` keeps one key per service, AES-GCM encrypted with an Android
  Keystore key, in the `ai_assistant_keys` preferences; `AiChatHistory.kt` keeps chats in
  `files/ai-chats/` for the chosen retention (7 days by default). Both are excluded from backups.
  `AiAssistantController.kt` holds the state and unlocks the key only for each request.
  The model reads `assets/ai/assistant-guide.md` plus `ui/AiAssistantSnapshot.kt`'s English
  summary of the current settings. `ui/AiAssistantUi.kt` puts the button at the top right (the
  translation icon returns when the assistant is off) and `ui/AiAssistantPanel.kt` is the
  right-side panel with the app's problems first. See the [spec](../specs/2026-10-08-ai-assistant-panel.md).
- `data/JapaneseGrammar.kt` finds Japanese grammar points from Kuromoji morphemes: a data
  catalogue of N5 to N1 patterns (`JapaneseGrammarCatalogue.kt`, matched by `GrammarPattern.kt`)
  plus hand-written rules for context-dependent points. Every catalogue point needs an example
  sentence in `JapaneseGrammarCatalogueTest`. `ui/GrammarExplanations.kt` words them
  (`strings_grammar.xml`, `strings_grammar_points.xml`) with a JLPT badge in the word card.
  Explanations are this project's own text, not copied from other grammar resources.
  While the assistant is on, the card's "Explain the grammar with AI" queues a question about the
  whole line (`AiAssistantController.askFromApp`). See the [N1 spec](../specs/2026-10-07-grammar-to-n1-and-auto-caption-language.md)
  and the [everyday points spec](../specs/2026-10-17-more-grammar-and-stop-ai-reply.md).
  Chinese (`ChineseGrammar.kt`, HSK 3.0 levels 1 to 6 and 7–9) works the same way: a catalogue
  (`ChineseGrammarCatalogue.kt`) plus a few rules, shared matching in `GrammarMatcher.kt`, text in
  `strings_grammar_zh.xml` and an example sentence per point in `ChineseGrammarCatalogueTest`.
  English (`EnglishGrammar.kt`, CEFR A1 to B2 from the CEFR-J Grammar Profile) is the same again:
  `EnglishGrammarCatalogue.kt`, a few rules, `strings_grammar_en.xml` and `EnglishGrammarCatalogueTest`.
  `LearnerGrammar.kt` picks the language's grammar for the word card. See the
  [Chinese spec](../specs/2026-10-18-chinese-learner-help.md) and the
  [English spec](../specs/2026-10-18-english-learner-help.md).
- `data/VocabularyRepository.kt` stores study cards/review data in local SQLite;
  `PracticeTransfer.kt` handles JSON/Anki TSV transfer. `data/ImmersionRepository.kt` keeps
  watched time per local day and language in a separate SQLite database; `ImmersionStats.kt`
  holds the testable tracking, batching and aggregation used by `ui/ProgressScreen.kt`. App preferences use Android
  SharedPreferences. `ui/WordPronouncer.kt` manages Android speech-engine fallback; Japanese words
  are spoken from their kana reading (`spokenText` in `ui/Pronunciation.kt`), not the kanji.
  `ui/PhraseSelection.kt` owns word/phrase selection in subtitle lines and its Copy /
  Translate / Pronounce bar; `ui/WordLearningSheet.kt` is the card Translate opens.
  `ui/PronunciationCache.kt` keeps the recorded speech of the last pronounced word only.
- `ui/AppLanguage.kt` holds the interface languages and applies the chosen one: Android 13+
  keeps it with `LocaleManager` (also shown in system settings through `res/xml/locales_config.xml`),
  older versions keep it in app preferences and apply it in `MainActivity.attachBaseContext` and
  for view model messages. `ui/AppLanguagePicker.kt` is the drawer button. Text lives in
  `res/values*/strings_<area>.xml`, one translated copy per offered language.
- `data/LanguageAwareTokenizer.kt` splits subtitle words. Japanese goes through
  `data/JapaneseMorphology.kt`, which loads Kuromoji lazily on a background thread and groups
  its morphemes into learner words; until it loads, the script-boundary heuristic is used.
  The APK excludes Kuromoji's dictionary files; `data/JapaneseDictionaryStore.kt` downloads the
  Maven artifact (pinned SHA-256) into app storage and reads the dictionary from it.
  Chinese (caption language `zh*`) goes through `data/ChineseMorphology.kt`: jieba's most
  probable split over its word list, traditional characters read through the simplified table,
  and tone-marked pinyin per word. It loads from `LearnerData` (APK assets) on a background
  thread; until then the script-boundary heuristic is used. English (caption language `en*`)
  goes through `data/EnglishMorphology.kt`: `EnglishPosTagger` runs OpenNLP's model without the
  library (same features, same tags), and `EnglishLexicon` gives dictionary forms (WordNet's
  morphy after the app's irregular-verb table) and CEFR-J word levels for the word card.

## Architecture invariants

- Keep exactly one online WebView/player. Replay and online vocabulary examples seek
  the same YouTube page video. Offline media downloading/local Media3 playback were
  removed; do not reintroduce another player as a shortcut.
- Preserve `classifyMainFrameUrl` navigation decisions and HTTPS YouTube origin checks
  inside executing JS, including asynchronous callbacks. Sign-in pages are a distinct
  navigation case, not permission to run playback scripts on arbitrary origins.
- Keep caption-provider host allowlists, response limits (currently 8 MiB), timeout
  budgets, and cancellation. Undocumented YouTube behavior belongs behind the provider
  boundary so it can change without rewriting the learning UI.
- No user-provided/developer-provisioned service key is required for translation. The app
  translates with Google Translate online by default (owner decision 2026-10-05) and must
  keep a Settings switch to on-device translation and fall back to it when Google fails. YouTube's internal client key handling is an extraction detail, not a
  new application API-key setup requirement.
- The AI assistant is the only feature that takes a user's own API key (owner decision
  2026-10-08). It sends nothing without a key and a question, never runs on a project server or
  shared key, stores keys only Keystore-encrypted and outside backups, and treats subtitle and
  error text as quoted data in its prompts. Turning it off restores the translation problem icon.
  It cannot change settings by itself; a later setting-change feature must ask for confirmation.
- Preserve guide migration: a missing `guide_completed` inherits prior onboarding
  completion for existing users; new users still see the guide. Preserve vocabulary,
  review scheduling, backup/import compatibility, and retained legacy clip files.
- Preserve release/signing continuity and preview package isolation. Normal feature
  branches do not change Gradle version constants or release reservations.

## Validation and delivery boundaries

Plain JUnit4 tests exercise small `internal` functions without Robolectric or mocking
libraries. Android framework calls return defaults in local unit tests, so these
cannot establish actual Android/WebView behavior. Compose instrumentation and WebView
fixtures run offline; they do not establish live YouTube or physical-device behavior.
Choose evidence appropriate to the change in its [spec](../specs/README.md).

[Android CI](../../.github/workflows/android.yml) runs formatting/complexity checks,
unit tests, lint, APK builds, optimized-build checks, and managed-device tests.
[PR previews](../../.github/workflows/pr-preview-auto.yml) publish successful trusted
same-repository PR artifacts and clean up closed-PR previews.

After an owner merge, [release-on-main.yml](../../.github/workflows/release-on-main.yml)
and [automatic_release.py](../../tools/automatic_release.py) verify final-head PR CI,
reserve monotonic versions, and build from the exact merge via a release-only commit.
Production signature/package/version checks, APK/checksum publication, serialized
reservations and retries are existing safeguards. A skip label publishes nothing. See
[release operation and recovery](../releasing.md#automatic-official-releases)
and [PR preparation](../../AGENTS.md#preparing-prs-for-owner-merge); specs do not control automation.
