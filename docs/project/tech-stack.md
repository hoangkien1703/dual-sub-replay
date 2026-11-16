# Technical context

This is a source map and explanation of architectural boundaries. [AGENTS.md](../../AGENTS.md)
contains operational commands and contributor rules. Follow source/build files if a
version snapshot here drifts, and update relevant documentation with lasting changes.

## Stack and authoritative configuration

| Area | Current context | Source of truth |
| --- | --- | --- |
| Android application | `:app`, `com.kienhoang.dualsubreplay`; min SDK 26, compile SDK 37, target SDK 36 | [App build](../../app/build.gradle.kts) |
| Language/build | Kotlin and AGP (versions in the root build, raised monthly by Dependabot), JDK 17; committed Gradle wrapper | [Root build](../../build.gradle.kts), [wrapper](../../gradle/wrapper/gradle-wrapper.properties), [CI](../../.github/workflows/android.yml) |
| UI | Jetpack Compose / Material 3; Compose BOM (version in the app build) | [App dependencies](../../app/build.gradle.kts) |
| Translation/network | ML Kit Translate 17.0.3, OkHttp, Kotlin coroutines | [App dependencies](../../app/build.gradle.kts) |
| Japanese words | Kuromoji 0.9.0 (Apache-2.0, pure Java); its 13 MB IPADIC dictionary downloads on first Japanese use | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-09-29-word-audio-jump-back-japanese-words.md) |
| English words | Data files only, extracted at build time into `assets/learner-data/`: Apache OpenNLP's UD English part-of-speech model (Apache-2.0) and WordNet 3.1's word and irregular-form lists (WordNet licence), plus the committed CEFR-J word levels (`app/src/main/learner-data/`, free with citation); no library code | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-10-18-english-learner-help.md) |
| Spanish words | Data files only: Apache OpenNLP's UD Spanish GSD part-of-speech model (Apache-2.0, extracted at build time) and verb and word lists made from Wiktionary and FrequencyWords by `tools/spanish/build_spanish_data.py` (CC BY-SA 4.0, committed in `app/src/main/learner-data/`); no library code | [App dependencies](../../app/build.gradle.kts), [spec](../specs/2026-11-13-spanish-learner-help.md) |
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
  captions the player loads later still give the full transcript. When the player never downloads
  the track, the page opens YouTube's own transcript panel and `data/TranscriptPanelParser.kt`
  reads its lines (line timing only). See the
  [spec](../specs/2026-10-18-page-first-full-transcript.md). With Settings → Captions & translation →
  Load captions with a page token (beta, owner request 2026-10-20; Automatic by default, On, Off), a page whose plain
  track request comes back empty mints its own proof-of-origin token the way YouTube's web player
  does (`ui/PageTokenScript.kt`: BotGuard's challenge from the page's own `att/get`, its interpreter
  only from `https://www.google.com/js/th/`, `GenerateIT` on the page's own origin) and asks again
  with `pot`. After a token worked, Automatic asks with it first on later videos until a plain
  request works again; On always asks with it first. The token stays in the page; only a short state code reaches the problem detail.
- Study tools (owner request 2026-10-20, [spec](../specs/2026-10-20-study-tools-from-migaku-and-jidoujisho.md)),
  each with its own switch (sections listed under [Study tools and Anki](#study-tools-and-anki); `ui/StudySettings.kt`,
  `ui/StudyToolsSettings.kt`): play modes and skip silence (`ui/StudyPlayback.kt`) decide on the
  page's 33 ms playback ticks and only send pause, seek and speed scripts (`ui/StudyWebScripts.kt`)
  to the one page video, with the full transcript only; known words (`data/KnownWords.kt`,
  `files/known-words/<language>.txt`, kept through Reset all settings) give the subtitle panel's
  comprehension score, new-word marks and the one-new-word play mode; the split Send to button
  (owner design 2026-10-23, [spec](../specs/2026-10-23-send-to-button-and-ankiconnect.md)) sends
  to the app last picked on its ▾ list or in Settings: AnkiDroid by default
  (`data/AnkiDroidNotes.kt`, AnkiDroid's public content provider with its runtime permission,
  without the AnkiDroid library) or AnkiConnect (`data/AnkiConnectNotes.kt`, version 6 JSON to
  `http://127.0.0.1:8765` only, the one cleartext address `network_security_config.xml` allows);
  the blur box is a page element over the
  player (`webBlurBoxScript`) that hides subtitles burned into the picture.
- App updates (owner request 2026-10-20, [spec](../specs/2026-10-20-check-for-updates.md)):
  `update/AppUpdater.kt` asks GitHub's releases API only when the drawer's Check for updates is
  tapped, or once a day with Settings → App updates → Update automatically (off by default; Wi-Fi
  downloads, installed when the app leaves the screen and no video plays). Official builds follow
  `releases/latest`, preview builds newer builds of the same PR on `preview`
  (`data/AppUpdates.kt`). Each APK must match GitHub's SHA-256 digest, be this package and be
  newer before `PackageInstaller` gets it (`REQUEST_INSTALL_PACKAGES`); Android checks the signature.
  Since 2026-10-30 ([spec](../specs/2026-10-30-scale-readiness.md)) Settings → App updates → Tell
  me about new versions (on by default) makes the same daily check read the latest release's
  `update-info.json` from GitHub's download hosts (not the API's 60-an-hour limit), falling back to
  the API, and shows a banner (at most once a week whatever the version, and not in the week after the app was
  installed or updated; Skip this version) or, below the
  newest `security:critical` release, a Security update dialog (daily for the fix's first 3 days,
  then weekly; a newer fix starts again), never over a
  playing video (`data/UpdateReminders.kt`, `ui/AppNotices.kt`).
  Since 2026-11-08 ([spec](../specs/2026-11-08-smaller-app-updates.md)) an update from one of the
  last 15 releases (or the previous preview build) downloads a ~3 MB patch and rebuilds the APK
  from the installed one (`tools/apk_patch.py`, `data/UpdatePatches.kt`), falling back to the
  whole APK; the release APK carries ARM libraries only (about 25 MB).
- Remote switches (owner decision 2026-10-30, switches only): `update/RemoteSwitchFetcher.kt` reads
  `remote-config/switches.json` from `raw.githubusercontent.com` with the daily check (64 KiB cap,
  saved 30 days). `data/RemoteSwitches.kt` can only reorder or drop built-in Google Translate
  addresses, slow background translation (3–60 s), turn Skip ads, Skip sponsors or page-token
  captions off, and show one of the notices in `strings_notices.xml`; it never carries code,
  scripts, selectors or addresses. See [remote-config/README.md](../../remote-config/README.md).
- Problem log (owner decision 2026-10-30, no server): `data/ProblemLog.kt` keeps the last 50 failed
  steps (Google statuses and addresses, caption step names, update problems; never video IDs,
  titles or text) in the cache folder. Menu → Report a problem shows them and opens a prefilled
  GitHub bug report the person sends themselves. Background translation pauses double while Google
  refuses (3 s to 60 s) and failed background steps rest 2 to 16 minutes (`PlaybackTranslation.kt`).
- `data/SubtitleMerger.kt` prepares readable segments; `SubtitleStore.kt` keeps
  transcript text/timing on disk and exposes bounded playback windows. Preserve
  bounded loading and cancellation when changing long-video behavior.
- `translation/OnDeviceTranslator.kt` uses downloadable ML Kit models with memory
  and disk caches. `ui/PlaybackTranslation.kt` and `TranslationCoordinator.kt`
  support playback-prioritized work; obsolete loads must not update current state.
  It also lists, downloads and removes whole languages for the
  Settings → Captions & translation → Languages on this device screen (`ui/LanguageDownloads.kt`).
- `translation/GoogleWebTranslator.kt` is the default engine (Settings →
  Captions & translation → Google Translate, `translation/TranslationEngine.kt`). It calls Google Translate's
  unofficial web endpoints with its own caches, sending upcoming sentences in one batch request
  (`translatePlaybackWindow`'s `prefetch`) and retrying short outages; `AppViewModel.translateText` routes every
  translation through the chosen engine. `prefetchInBackground` translates minutes 2 to 5 one request
  at a time while the video plays; the window itself (the next minute) is translated also while paused. A line Google leaves blank is asked again alone and, if still blank, translated on the
  device by itself. A failure moves that video off Google (`DualSubUiState.onDeviceFallback`): the
  page fetches YouTube's own translation of the caption track (`data/PageTranslationSource.kt`,
  `webPageTranslationScript`, matched to rows by `ui/YouTubeTranslation.kt`; see the
  [YouTube translation spec](../specs/2026-10-19-youtube-translation-fallback.md)) and rows it does
  not cover translate on the device. It shows `ui/OnlineTranslationNotice.kt`'s top-right icon, and
  checks Google again every 2 minutes; the next load tries Google first (on mobile data, right after a
  failure there, it starts without Google and checks it at once). A row waits for Google only
  `translation/GoogleWait.kt`'s `googleWaitMs` (Settings → Captions & translation → Wait for Google): `awaitWithin`
  then moves the video off Google while the request runs on in the load's supervisor scope, and its
  answer brings the video back to Google (see the
  [quick switch spec](../specs/2026-10-19-quick-switch-when-google-is-slow.md)). See the
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
  Spanish (`SpanishGrammar.kt`, CEFR A1 to C1 placed from the PCIC) has more rules: every verb's
  tense and mood, commands, ser / estar, clitic pronouns and se, and si / cuando / aunque clauses;
  `SpanishGrammarCatalogue.kt`, `strings_grammar_es.xml` and `SpanishGrammarCatalogueTest`.
  `LearnerGrammar.kt` picks the language's grammar for the word card. See the
  [Chinese spec](../specs/2026-10-18-chinese-learner-help.md) and the
  [English spec](../specs/2026-10-18-english-learner-help.md) and the
  [Spanish spec](../specs/2026-11-13-spanish-learner-help.md).
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
  goes through `data/EnglishMorphology.kt`: `OpenNlpPosTagger` runs OpenNLP's model without the
  library (same features, same tags), and `EnglishLexicon` gives dictionary forms (WordNet's
  morphy after the app's irregular-verb table) and CEFR-J word levels for the word card.
  Spanish (caption language `es*`) goes through `data/SpanishMorphology.kt`: the same tagger with
  OpenNLP's Spanish model, then del / al and attached pronouns are split off, and `SpanishVerbs`
  (`SpanishConjugation.kt`) reads each verb's dictionary form, tense and person from regular
  endings plus the irregular forms in `es-verbs.txt`; `SpanishWords` gives noun and adjective
  dictionary forms (canciones → canción). `ui/SpanishVerbForm.kt` shows the form on the word card.

## Architecture invariants

Tests enforce most of these. [AGENTS.md](../../AGENTS.md#architecture-rules) keeps one line per
area with the rules that must never break; this section holds the details. Specs in
[docs/specs](../specs/README.md) are dated records of each change, so an older spec can be
superseded by a later one: this section and the code describe the app as it is now. Update the
matching part here in the same PR when a change moves one of these rules.

### Player and YouTube page

- Exactly one WebView exists (`SingleYouTubePage` in `ui/YouTubeBrowserScreen.kt`). YouTube tabs (owner request 2026-10-27, [spec](../specs/2026-10-27-youtube-tabs.md)) are saved addresses (`ui/BrowserTabs.kt`) loaded one at a time into that page, so only the open tab plays; with every tab closed the page leaves composition and an empty page offers a new tab. A tab of another Google account (Settings → YouTube tabs → Use different Google accounts, off by default) replaces the WebView, because a WebView profile must be set before the WebView loads anything (`ui/YouTubeTabAccounts.kt`); use the tab's `tabCookieManager`, never `CookieManager.getInstance()`, in the page. Online replay seeks the native YouTube page video via a JS polling bridge. Replay and online vocabulary examples seek the same page video. Offline video downloading and local Media3 playback are removed; never add a second online player/WebView.
- Screen-off playback and earphone controls (owner decision 2026-10-13): `playback/WebVideoPlaybackService.kt` is a `mediaPlayback` foreground service owning a media session and notification that only send play/pause/seek scripts to the page video; it is not a second player. The YouTube page is always told it is visible (`WEB_KEEP_PLAYING_WHEN_HIDDEN_SCRIPT`), so videos keep playing with the screen off for every account. See the [spec](../specs/2026-10-13-background-play-and-earphone-controls.md). While a loop, Repeat, a play mode other than Normal or Skip silence steers a playing video (`StudyOffScreen.steering`), the page poll and the playback request keep running off screen (about once a second), so they keep working with Home or the screen off ([spec](../specs/2026-11-13-card-sound-places-and-background-loop.md)).
- Main-frame navigation goes through `classifyMainFrameUrl` → `YOUTUBE_WEB` (embed) / `GOOGLE_SIGN_IN` (embed during sign-in flow) / `OPEN_EXTERNAL` (browser) / `BLOCK`. JS snapshot/replay scripts must keep re-verifying the executing origin (`https:` + `*.youtube.com`); including asynchronous callbacks, and `PlaybackArchitectureTest` asserts the literal script text. Sign-in pages are a distinct navigation case, not permission to run playback scripts on arbitrary origins.

### Captions

- Caption discovery uses watch-page metadata and undocumented Innertube player fallbacks, then timed-text downloads, isolated behind `data/CaptionProvider.kt` / `YouTubeCaptionProvider.kt` (host allowlist, 8 MiB response cap). Keep that replaceable boundary, its timeout budgets and cancellation, so undocumented YouTube behaviour can change without rewriting the learning UI. The YouTube page fetches the track first (owner decision 2026-10-18, [spec](../specs/2026-10-18-page-first-full-transcript.md)): `PageFirstCaptionProvider` races the page (`PageTranscriptSource`, scripts in `YouTubeWebScripts.kt`) against that download; the first success wins. The page keeps a copy of the caption replies its own player downloads (`WEB_CAPTION_CAPTURE_SCRIPT`, a document-start script that only reads `/api/timedtext` and `/youtubei/v1/get_transcript` replies on YouTube hosts and never changes a request), so the app never re-sends the page's token; captions the player loads later still replace live subtitles. An empty track list in the player's details is no dead end: the player's captions menu and its downloads count too. As the last resort (owner decision 2026-10-18) the page opens YouTube's own transcript panel and takes its lines. With Settings → Captions & translation → Load captions with a page token (beta, owner request 2026-10-20: Automatic by default, On, Off), a page whose plain request comes back empty mints its own proof-of-origin token as YouTube's web player does (`ui/PageTokenScript.kt`; BotGuard's interpreter only from `https://www.google.com/js/th/`) and asks again with `pot`; once that worked, Automatic asks with the token first on later videos (`page_token_needed`) until a plain request works again, and On always asks with it first; the token never leaves the page and `webPageTranscriptScript` keeps its one `fetch(`.

### Translation

- Translation uses Google Translate's unofficial web endpoint by default (`translation/GoogleWebTranslator.kt`, owner decision 2026-10-05); Settings → Captions & translation switches to on-device ML Kit (`translation/OnDeviceTranslator.kt`). No user-provided/developer-provisioned service key is ever required; YouTube's internal client key handling is an extraction detail, not a key the user sets up. Settings → Captions & translation → Translate subtitles with is one choice, listed first in the section (owner request 2026-10-28, `SubtitleTranslator`): Google (default), AI translation (best quality: Google's lines while the AI is loading), AI only, or on this device only (no "beta" label, owner decision); only the chosen translator's settings show below it. The AI choices ([spec](../specs/2026-10-28-ai-subtitle-translation.md)) let the AI assistant's service and keys translate, with the AI assistant's model unless another is chosen (`translation/AiSubtitleTranslator.kt`: windows of sentences split into their rows plus earlier sentences as context, one translation per row in JSON); with Google while waiting, Google's lines show first and each sentence switches to the AI's as soon as the streamed reply contains it, while AI only waits for the AI (owner request 2026-10-28); Google keeps whatever the AI leaves out or fails on, and the assistant cannot change this setting. Settings → Captions & translation → AI thinking for translation (owner request 2026-11-02, [spec](../specs/2026-11-02-ai-translation-thinking.md)) is Off by default, or Low or Medium (no High: long windows would time out), shown only for Gemini and OpenAI; each level keeps its own translation cache, and the assistant may change it after the user's tap. When Google still fails after its retries (1 s, 3 s, 7 s; none for a block), that video uses YouTube's own translated captions, as LingoTube does (owner decision 2026-10-19, [spec](../specs/2026-10-19-youtube-translation-fallback.md): the page fetches the track with `tlang`, `PageTranslationSource`), and quietly translates on-device what YouTube does not cover; a top-right icon explains why (naming the Google address and status), and Google is checked again every 2 minutes and on every load. A row never waits long for Google (owner decision 2026-10-19, [spec](../specs/2026-10-19-quick-switch-when-google-is-slow.md)): after Settings → Captions & translation → Wait for Google (Automatic: 2 s on mobile data, 4 s on Wi-Fi; or 2, 4, 8 s) the video switches the same way while Google's request runs on, and goes back to Google, replacing the shown lines, once it answers; an unreachable address hands over to the next, and the address that last worked is tried first, kept across restarts in `google_addresses` preferences for translation and voice alike (owner request 2026-10-19, [spec](../specs/2026-10-19-remember-working-google-address.md)); after a failure on mobile data the next load on mobile data starts without Google and checks it as soon as a row plays. A line Google leaves blank is asked again alone at each address and, if still blank, only that line translates on-device; a reply with every line blank counts as a refusal. While a video plays, minutes 2 to 5 are translated in the background, one request every 3 s (owner decision 2026-10-09, [spec](../specs/2026-10-09-google-blank-lines-and-five-minutes-ahead.md)); the current minute is translated also while paused (owner request 2026-10-27, [spec](../specs/2026-10-27-translate-while-paused-and-fullscreen-menu.md)). See the [Google Translate spec](../specs/2026-10-05-opt-in-google-translate.md). The "Translate to" list (`TranslationLanguages`) holds ML Kit's 59 languages plus online-only ones (`onDevice = false`, owner pick 2026-11-02, [spec](../specs/2026-11-02-azerbaijani-and-online-only-languages.md)): those never move to the device; after a Google failure their rows take YouTube's translation, else ask Google again, a line Google leaves blank stays blank, and "On this device only" shows `status_translation_needs_online` instead of translating.

### AI features

- The AI assistant (`assistant/`, `ui/AiAssistant*.kt`, owner decision 2026-10-08), the opt-in Translate with AI, AI subtitles for videos without captions (owner pick 2026-11-08, [spec](../specs/2026-11-08-ai-subtitles-for-videos-without-captions.md): a tap on the no-captions card sends only the video's YouTube address to Gemini with the assistant's Gemini keys, in 5-minute parts, kept in `cacheDir/ai-subtitles/`; no on-device speech model and no audio capture) and Settings → Word learning → Meaning in this sentence (AI) (owner picks 2026-11-10, [spec](../specs/2026-11-10-anki-card-picture-and-front.md): off by default even with a key; each tapped word sends only that word and its sentence, and the card shows the answer above the dictionary meaning and saves "in sentence (dictionary)"; `ui/WordMeaningInContext.kt`) are the only features that use the user's own API key, for Gemini, OpenRouter, OpenAI, Claude (Anthropic's own Messages API), OpenCode Zen/Go, or an OpenAI-compatible HTTPS address. It is on by default, sends nothing until a key is added and a question asked, and Settings → AI assistant turns it off completely (the translation problem icon then returns). Keys are AES-GCM encrypted with an Android Keystore key in `ai_assistant_keys` preferences; keys, `files/ai-chats/` and `files/ai-memory/` (memories and the user's instructions) stay excluded from backups (`BackupRulesTest`). Chats are kept 7 days by default (Off / 7 / 30 days / Forever). Subtitle and error text, and attached text files, go to the model as quoted data; attached pictures and files stay in memory and are never written to chat history. Never add a project server or shared key. The assistant acts only through the fixed tool list in `assistant/AiActions.kt` (owner decision 2026-10-08): small setting changes, playback, speed and Save to vocabulary run at once with an Undo chip; YouTube search, opening a link the user typed, translation changes, and the settings that reload subtitles, download models or change the interface language (`AiSetting.asks`) wait for the user's tap, and so do setting changes after subtitles, files or error text were read. It reads and may change every setting on the settings page except the ones below ([settings spec](../specs/2026-10-16-translation-position-and-ai-settings.md)). Memory changes only through `save_memory` and `forget_memory`, offered only while memory is on for the chat; they run at once with Undo and Manage, except after other text, and never come from subtitles, files or error text (owner decision 2026-10-08, [memory spec](../specs/2026-10-08-ai-assistant-memory.md)). Without full access it can never touch keys, the AI settings, saved words, dragged positions, or Reset all settings. Settings → AI assistant → Permissions → Full access (off by default, owner request 2026-10-18) offers the `AI_FULL_ACCESS_TOOLS`: its own AI settings, adding a key the user typed, removing a key, finding, editing and deleting saved words, and resetting settings or subtitle positions; most run at once with Undo, removing a key and resetting always wait for a tap, and after subtitles, files, error text or saved words were read every change waits for a tap. Only tools offered with the question run. It never touches language downloads, the assistant switch or full access itself. Each service keeps up to 10 keys (`AiKeyStore`, the first under the old one-key names); a rejected, out-of-credit or rate-limited key hands the same request to the next key ([redesign spec](../specs/2026-10-18-ai-settings-redesign-keys-and-full-access.md)). Search and screen (owner decision 2026-10-18, [spec](../specs/2026-10-18-ai-web-search-and-screen.md)): `find_youtube_videos` searches YouTube's own web search (`data/YouTubeVideoSearch.kt`, free, no key, on by default) and only videos it found or links the user typed can be opened, each still waiting for the Open tap; web search (off by default) is the service's own (Claude `web_search`, OpenRouter's server tool, OpenAI's search model; paid to that service) or Tavily with the user's free key (`AiSearchKeyStore`, sealed like the AI keys in `ai_assistant_keys`); `look_at_screen` (on by default) copies the app window with the panel hidden and sends the picture once, never saved. Search results and screenshots are quoted data and count as other text, so later changes wait for a tap. See the [AI assistant spec](../specs/2026-10-08-ai-assistant-panel.md), the [actions spec](../specs/2026-10-08-ai-assistant-actions.md), and the [Claude and Gemini default spec](../specs/2026-10-11-gemini-flash-lite-default-and-claude.md).
- Meaning in this sentence asks for a whole line at once (lion737, #81, owner pick 2026-11-16, [spec](../specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md)): after the tapped word's own answer, one numbered request (`ui/LineMeanings.kt`) fills the cache for the line's other words, and a tap waits for a line request already running instead of asking again. Prepare meanings ahead (`prepare_meanings_ahead`, off by default even with Meaning in this sentence on) asks for each line as it starts playing; only one line request runs at a time and only the newest line waits, so playing never queues requests behind `AiKeyPacer` (`AiRequestBudgetTest`).

### Interface languages

- User-visible text lives in `app/src/main/res/values/strings_<area>.xml` (English, the default) with the same file in every translated `values-*` folder; never add a UI string literal in Kotlin. The offered interface languages are `APP_LANGUAGES` in `ui/AppLanguage.kt` and `res/xml/locales_config.xml`; with no saved choice the app follows the device. `AppLanguageTest` and lint `MissingTranslation` fail when a language lacks a string or a placeholder differs. Diagnostic exception text in `data/` and `translation/` stays English. See the [interface languages spec](../specs/2026-10-02-app-interface-languages.md).

### Learner languages

- Chinese learner help (owner decision 2026-10-18, [spec](../specs/2026-10-18-chinese-learner-help.md)) bundles data files only: jieba's word list and HanLP's pinyin and traditional-to-simplified tables, extracted at build time into `assets/learner-data/` (`LearnerLanguageData` in `app/build.gradle.kts`). No jieba or HanLP code and no download. Words are spoken with Google Translate's online voice (`translation/GoogleWebSpeech.kt`) while Google translates subtitles, in every language (owner decision, [spec](../specs/2026-10-19-google-voice-every-language.md)): three Google addresses, then the phone's voices; after no address answers, the phone speaks at once for 2 minutes. Chinese grammar points (`data/ChineseGrammarCatalogue.kt` plus rules in `ChineseGrammar.kt`) share `data/GrammarMatcher.kt` with Japanese and carry HSK 3.0 levels (1–6 and 7–9) from the GF0025-2021 grammar syllabus, or the HSK 3.0 word list for points it does not list; every point needs its own example sentence in `ChineseGrammarCatalogueTest` and its text in all 9 interface languages.
- English learner help (owner request 2026-10-18, [spec](../specs/2026-10-18-english-learner-help.md)) also bundles data files only: OpenNLP's English part-of-speech model and WordNet 3.1's word and irregular-form lists, extracted at build time into `assets/learner-data/`, plus the CEFR-J word levels committed in `app/src/main/learner-data/` (free for commercial use with citation; keep the citation in `THIRD_PARTY_NOTICES.md` and the licence screen). No OpenNLP or WordNet code. English grammar points carry CEFR levels from the CEFR-J Grammar Profile (the rule is in the spec); every point needs its own example sentence in `EnglishGrammarCatalogueTest` and its text in all 9 interface languages.
- English IPA (25kiuo, #81, owner pick 2026-11-16, [spec](../specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md)): `en-ipa.txt` in `app/src/main/learner-data/`, made by `tools/english/build_english_ipa.py` from ipa-dict's American English (MIT, from cmudict-ipa and the CMU Pronouncing Dictionary) for the words of FrequencyWords' English top 50,000 (CC BY-SA 4.0: keep the credits in `THIRD_PARTY_NOTICES.md` and the licence screen; regenerate, never hand-edit). `wordReading()` in `data/EnglishIpa.kt` gives the word card and the card's Reading field the analyzer's own reading (kana, pinyin) first, else an English single word's IPA; other languages get none.
- Spanish learner help (owner request 2026-11-13, [spec](../specs/2026-11-13-spanish-learner-help.md)) bundles data files only: OpenNLP's Spanish part-of-speech model (extracted at build time) and `es-verbs.txt` / `es-words.txt` in `app/src/main/learner-data/`, generated from Wiktionary (Wiktextract) and FrequencyWords by `tools/spanish/build_spanish_data.py` (CC BY-SA 4.0: keep the credit in `THIRD_PARTY_NOTICES.md` and the licence screen; regenerate, never hand-edit; the plural rules in `tools/spanish/spanish_rules.py` and `SpanishLexicon.kt` must stay the same). Spanish grammar levels are placed by hand from the Instituto Cervantes PCIC and textbook order (see the spec); every point needs its own example sentence in `SpanishGrammarCatalogueTest` and its text in all 9 interface languages (`strings_grammar_es.xml`).
- German learner help (contributor request, mrAlishah, PR #233, [spec](../specs/2026-11-14-german-grammar.md)) bundles data files only: OpenNLP's German part-of-speech model (extracted at build time) and `de-verbs.txt` / `de-words.txt` in `app/src/main/learner-data/`, generated by `tools/german/build_german_data.py` from Wiktionary (Wiktextract) and FrequencyWords (CC BY-SA 4.0: keep the credit in `THIRD_PARTY_NOTICES.md` and the licence screen; regenerate, never hand-edit; the weak-verb rules in the script and `GermanConjugation.kt` must stay the same). German grammar never calls an AI; every point needs its example in `GermanGrammarTest` / `GermanRulePointsTest`, its level in `GERMAN_RULE_LEVELS` and its text in all 9 interface languages (`strings_grammar_de.xml`). Points made of words that are not next to each other use `GrammarMatch.evidence`.
- Kanji details on the word card (owner request 2026-10-24, [spec](../specs/2026-10-24-word-meaning-and-kanji-details.md)) bundle data files only in `app/src/main/learner-data/`: `ja-kanji.txt` and `ja-kanji-strokes.txt` are made by `tools/kanji/build_kanji_data.py` from KANJIDIC2 and KanjiVG and stay CC BY-SA (keep the credits in `THIRD_PARTY_NOTICES.md` and the licence screen; Hán Việt corrections go in the script's `HAN_VIET_FIXES`). `ja-radicals.txt` and the memory stories `ja-kanji-stories-en.txt` / `-vi.txt` are the project's own (MIT): never copy WaniKani, Koohii or Kanji alive mnemonics. Every jōyō kanji needs both stories (`JapaneseKanjiTest`); a Vietnamese story names a Hán Việt reading in capitals.
- Chinese character details (owner request 2026-10-24, [spec](../specs/2026-10-24-chinese-character-details.md)) share the "Character details" setting with kanji. `zh-hanzi.txt`, `zh-hanzi-strokes.txt` and `zh-hanviet.txt` are made by `tools/hanzi/build_hanzi_data.py` from Make Me a Hanzi (strokes: Arphic Public License, keep the change notice in the file; facts: LGPL-3+), HSK 3.0 (ivankra/hsk30, MIT), Unihan and KANJIDIC2; Hán Việt corrections go in the script's `HAN_VIET_FIXES`. The stories `zh-hanzi-stories-en.txt` / `-vi.txt` are the project's own (MIT): never copy Remembering the Hanzi, Mandarin Blueprint, Outlier or Skritter mnemonics. Every HSK character needs both stories, a story uses only the character and its own parts as Chinese characters, and a Vietnamese story names the Hán Việt reading in capitals when there is one (`ChineseCharactersTest`).

### Study tools and Anki

- Study tools (owner request 2026-10-20, [spec](../specs/2026-10-20-study-tools-from-migaku-and-jidoujisho.md)), ideas from Migaku and jidoujisho with no code, assets, data, keys or endpoints of theirs: play modes (pause after each line, read first, listen first, dictation, repeat each line, pause on lines with one new word) and skip silence only send pause, seek and speed scripts to the one page video and need the full transcript.
- Dictation (owner pick 2026-11-09, [spec](../specs/2026-11-09-dictation-play-mode.md)) pauses like Pause after each line, hides each transcript row until Check or Show reveals it, and types answers in a box under the transcript (`ui/Dictation.kt`, checked by `data/DictationCheck.kt`); answers and reveals live only in memory, and outside the panel (full screen, overlay, TV, live subtitles) it acts like Listen first.
- Known words live in `files/known-words/` (not preferences, so Reset all settings keeps them) and give the subtitle panel's "Known %" score; Known words is off by default (owner decision 2026-10-20), and the word card's "I know this word" (in its More menu) shows only while it is on (owner pick 2026-11-17): with Automatic a word becomes known after it played in 10 lines (5/10/20), each line counted once per video, saved words never, with counts in `files/known-words/<language>.seen` written at most every 30 s and on pause, and "I know this word" marks at once and restarts a count.
- The word card's bar (owner design 2026-11-17, [spec](../specs/2026-11-17-word-card-action-bar.md), `ui/WordCardActions.kt`) sits under the scrolling part, always in view: a wide Save to Practice, then Ask AI (only while the assistant is on) and Anki (only while Send to shows), and More always at the far right ("I know this word" while Known words is on, and Word card settings, which opens Settings → Word learning); a hidden button leaves its space empty. The deck chip shows only when named decks exist. Settings → Flashcards & Anki holds Video example for saved words (`saved_word_video_example`, on, was the card's Online example box) and Also send saved words (`send_saved_words`, off: Save to Practice sends the card too and the Anki button hides until a send fails). Features that need setup never show on the card before they are set up.
- One Send to button (owner design 2026-10-23, [spec](../specs/2026-10-23-send-to-button-and-ankiconnect.md)): the word card's Anki button sends to the app picked in Settings (`send_to_app`), and Practice → Export's split Send all button sends to the app last picked on its ▾ list or in Settings: AnkiDroid by default, through its public content provider and runtime permission, never its library (on many phones AnkiDroid answers only while it runs in the background, so a refused card offers Open AnkiDroid), or AnkiConnect at `http://127.0.0.1:8765` only (AnkiConnect Android, Yomitan, asbplayer; `network_security_config.xml` allows cleartext to that address and nothing else; AnkiConnect Android cannot make decks or note types, so cards fall back to Default and Basic there).
- Cards carry sound (owner pick 2026-11-09, [spec](../specs/2026-11-09-anki-word-and-sentence-audio.md)): Flashcards & Anki has a Front / Back / Off pick each for Word sound, Sentence sound and Picture (owner pick 2026-11-12, [spec](../specs/2026-11-13-card-sound-places-and-background-loop.md); defaults: word sound where Card front puts it, sentence sound on the back, picture off; the back template starts with `{{FrontSide}}`, so never offer "front and back"): the word in the word card's voice and the line's real sound, which a page script (`ui/LineAudioScripts.kt`) records from the page video's `captureStream()` while it plays (the last 90 s, in memory; it only listens, never downloads, and asks for no permission; a ScriptProcessor with 16384-sample blocks, each timed by the audio clock (samples received against the context's time), because 2048-sample blocks lost sound on a busy phone page and made clips choppy (2026-11-17); AudioWorklet can't be used, YouTube's CSP blocks a blob: worklet module) and only while Send to can be used; clips are `.m4a` in `files/line-audio/` (left out of cloud backups), new cards use the "DualSub Replay audio" note type (AnkiDroid can't add fields to the old one), media reach AnkiDroid through the `${applicationId}.cardaudio` provider (`data/CardMediaProvider.kt`, which reports `.m4a` as `audio/mp4` because AnkiDroid names its copy after the reported type and Android's table says `audio/mpeg`, which made `.mp3`) with a one-copy read grant, a silent clip never goes on a card, and a card sent before its line ended gets its clip once the line has played (`anki_audio_pending`, `ui/LineAudioCapture.kt`).
- Cards also carry a picture and a chosen front (owner pick 2026-11-10, [spec](../specs/2026-11-10-anki-card-picture-and-front.md)): with Picture on Front or Back a still the page draws from its own video into a canvas while the word's line shows (`webLinePictureScript`, `files/line-pictures/`, left out of cloud backups), else the video's thumbnail from `i.ytimg.com`, in the Source field above the link (no new note type); Card front (Word and sentence by default, Word, Sentence, Listening) is the note type's card template, set again only when the pick changes (`ankidroid_card_front` / `ankiconnect_card_front`), and on AnkiConnect Android's Basic cards it picks the Front and Back fields; never add a separate button per app.
- AnkiDroid deck: the provider's single-note insert ignores a `deckId` and uses the note type's default deck, which drifts (last deck used in AnkiDroid's Add screen, or Default after the deck was deleted), so `AnkiDroidNotes` moves each new card into its deck through `notes/<id>/cards/0` right after adding it (2026-11-16). The deck is split like Practice (`ankiDeckName`): `DualSub Replay::<Practice deck name>`, else `DualSub Replay::<language in English>`; AnkiConnect keeps its one chosen deck. The line's sound in the Sentence audio field starts with a small "Sentence from the video" label (`labeledSentenceSound`; a card that waits for its clip gets the label with it). The Source link is two links with no text between them (a picture-front template hides only `a` and `br`): "DualSub Replay 1:23", an `intent://www.youtube.com/watch?...#Intent;scheme=https;package=<applicationId>;end` link, because Android hands youtube.com links to the verified YouTube app; then a plain "YouTube" link for desktop Anki. Released AnkiDroid opens intent links freely; its unreleased main branch asks for "allow cards to open other apps" first. Shared and opened links keep their `t=` start (`YouTubeUrlParser.extractStartSeconds`).
- Practice has what a card sent to Anki has (owner request 2026-11-16, `ui/PracticeSounds.kt`): speaker buttons for the word, the line (the kept clip from `LineAudioStore`, else the line read aloud), the meaning and the translation, and the line's picture where Flashcards & Anki → Picture puts it. No setting: buttons play only when tapped.
- Read aloud on the back and Reading (owner pick 2026-11-16, [spec](../specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md)): three chips (Meaning in this sentence, Dictionary meaning, Sentence translation; only the meaning on by default, owner pick after the first preview) record those texts in the meaning's language with the word card's voice (`WordAudioRecorder.recordText`; Google's voice, else the phone's; a part with no voice is left out and never stops a card) and append them to the Meaning and Translation fields, since AnkiDroid can't add fields. Reading (Front / Back / Off, Back by default) moves `{{Reading}}` with its own template-key suffix; on a front without the word it stays on the back. Play first also shows on a Listening front with the word sound on the front (`+wordfirst`; never picked keeps the line first). The word card fetches Google's voice for its line's other words ahead (`WordPronouncer.prepare`), and `PronunciationCache` keeps the last 60 recordings.
- The replay that records a card's sentence sound (a card sent before its line ended) plays muted: `captureStream()` keeps a muted video's sound (emulator check 2026-11-16, [spec](../specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md)). Every way the clip stops gives the video's own mute state back (`window.__dualSubClipUnmute`); the learner's own clip replay keeps its sound.
- Sleep timer (25kiuo, #81, [spec](../specs/2026-11-16-instant-word-card-read-aloud-ipa-sleep-timer.md)): a row in Settings → Playback (Off, 15, 30, 45, 60 min, End of video) that only sends Pause to the page video through `WebVideoBridge` (`playback/SleepTimer.kt`); End of video also pauses when another video starts. The timer is not saved. The gear popup shows the row too only when Show in quick settings is on (`sleep_timer_in_quick_settings`, off by default).
- The blur box is a page element over the player. Every tool has its own switch: play modes, skip silence and the blur box under Settings → Study tools, Known words and Character details under Word learning, Send to and card audio and pictures under Flashcards & Anki; the assistant can change them (`page_token_captions` waits for a tap).

### App updates, remote switches and problem reports

- App updates (owner request 2026-10-20, [spec](../specs/2026-10-20-check-for-updates.md)): the drawer's Check for updates asks GitHub's releases API only when tapped; Settings → App updates → Update automatically (off by default) checks once a day on opening, downloads on Wi-Fi and installs when the app leaves the screen, never while a video plays. Downloads come only from GitHub hosts, must match GitHub's SHA-256 digest and be a newer version of this package before `update/AppUpdater.kt` hands them to `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`, allowed in `tools/report_apk.py` for this only). Preview builds update to newer builds of the same PR from the `preview` release; local builds only open GitHub. Interrupted updates (owner pick 2026-11-08, [spec](../specs/2026-11-08-retry-interrupted-updates.md)): the download lives in `cacheDir/app-update/` with `update.json` describing it, so it survives the app being closed, a broken one carries on with an HTTP `Range` request, an install Android never answered shows Install again when the app returns, and the APK is deleted once the new version opens. Smaller updates (owner pick 2026-11-08, [spec](../specs/2026-11-08-smaller-app-updates.md)): each release publishes patches from its last 15 releases (`tools/apk_patch.py`, listed in `update-info.json`'s `patches`), and each preview build one from the build it replaces; the app rebuilds the new APK from its installed one (`data/UpdatePatches.kt`), which must still match the release's SHA-256, and downloads the whole APK when anything about the patch fails. The release APK carries ARM libraries only (`abiFilters` in `app/build.gradle.kts`; debug and benchmark builds keep every ABI), so a device that can't run ARM apps gets `UpdateChannel.UnsupportedDevice`. Never add a project server or a store. Scale readiness (owner request 2026-10-30, [spec](../specs/2026-10-30-scale-readiness.md)): Settings → App updates → Tell me about new versions (on by default) makes the daily check read the latest release's `update-info.json` (download hosts, not the rate-limited API) and show a banner for a new version (at most once a week whatever the version, so daily releases never bring a daily banner, and not in the week after the app was installed or updated; Skip this version) or, below the newest `security:critical` release, a Security update dialog (no skip; after Not now daily for that fix's first 3 days, then weekly, and a newer fix starts again), never over a playing video; turning the setting off sends nothing and says that security updates go unannounced too; the release workflow writes that file and publishing requires it. The same check reads `remote-config/switches.json` (remote switches, switches only: reorder or drop built-in Google addresses, slow background translation, turn Skip ads, Skip sponsors or page-token captions off, show a built-in notice); never let it carry code, scripts, selectors or addresses. Menu → Report a problem shows the local problem log (`data/ProblemLog.kt`: step names, statuses, Google addresses; never video IDs, titles or text) and opens an email to `PROBLEM_REPORT_EMAIL` (dualsubreplay.help@gmail.com, the project's report inbox) or a prefilled GitHub issue in an in-app browser tab, which the person sends; nothing is uploaded by itself.

### Ads and sponsors

- Ads & sponsors (owner request 2026-10-25, [spec](../specs/2026-10-25-skip-ads-and-sponsors.md)): Settings → Ads & sponsors → Skip ads and Skip sponsors, both on by default. YouTube's ads still load; `WEB_AD_SKIP_SCRIPT` (`ui/AdSkipScripts.kt`) only mutes the page video and plays it at 4x while the player shows an ad, presses YouTube's own Skip and banner-close buttons (YouTube ignores a script's press of Skip, so the app also taps the shown Skip button with a real touch, only where `elementFromPoint` is that button, never the ad video), and gives the sound and speed back afterwards. Never block ad requests or edit YouTube's replies (the owner picked skipping over blocking). Sponsor parts come only from SponsorBlock (`data/SponsorSegments.kt`: `sponsor.ajay.app/api/skipSegments/<4 hex of the SHA-256 of the video ID>`, `sponsor` category only, in memory only; data CC BY-NC-SA 4.0, credited in `THIRD_PARTY_NOTICES.md` and the licence screen) and are jumped over once when the video plays into them; a seek into one keeps it unless Always skip sponsors (off by default, owner request 2026-10-25) is on.

### Settings backup and sharing

- Backup & sharing (owner pick 2026-11-10, [spec](../specs/2026-11-11-backup-and-setup-sharing.md)): every key in `dual_sub_preferences` and `ai_assistant` has a scope and type in `SETTING_SPECS` (`data/SettingsTransfer.kt`): SHARE (look and study, travels in shared setup links), BACKUP (personal, Anki apps, every AI choice) or NEVER (the AI custom address, full access, sign-ins, phone state). Add each new setting there, read it through a `*_PREFERENCE` constant, and keep anything that uses an API key out of SHARE; `BackupSharingTest` fails otherwise. Values from a backup file or link are type- and range-checked; unknown keys are skipped.

### First launch

- First-launch flow is `LanguageSetupScreen` → `GuideScreen` → main experience. Preserve the guide migration behavior: if `guide_completed` is absent, users who already completed onboarding are treated as guide-complete, while brand-new users see the guide. Do not simplify this to `getBoolean("guide_completed", false)` or existing users will see the guide after upgrading. Preserve vocabulary, review scheduling, backup/import compatibility, and retained legacy clip files.

### Releases

- Preserve release/signing continuity and preview package isolation. Normal feature branches do
  not change Gradle version constants or release reservations. See [releasing](../releasing.md).

## Validation and delivery boundaries

Plain JUnit4 tests exercise small `internal` functions without Robolectric or mocking
libraries. Android framework calls return defaults in local unit tests, so these
cannot establish actual Android/WebView behavior. Compose instrumentation and WebView
fixtures run offline; they do not establish live YouTube or physical-device behavior.
Choose evidence appropriate to the change in its [spec](../specs/README.md).

[Android CI](../../.github/workflows/android.yml) runs formatting/complexity checks,
unit tests, lint, APK builds, optimized-build checks, and managed-device tests.
[PR previews](../../.github/workflows/pr-preview-auto.yml) publish the APK of a PR whose build job
passed (device tests may still be running), including fork PRs, and clean up closed-PR previews.

After an owner merge, [release-on-main.yml](../../.github/workflows/release-on-main.yml)
and [automatic_release.py](../../tools/automatic_release.py) verify final-head PR CI,
reserve monotonic versions, and build from the exact merge via a release-only commit.
Production signature/package/version checks, APK/checksum publication, serialized
reservations and retries are existing safeguards. A skip label publishes nothing. See
[release operation and recovery](../releasing.md#automatic-official-releases)
and [PR preparation](../../AGENTS.md#preparing-prs-for-owner-merge); specs do not control automation.
