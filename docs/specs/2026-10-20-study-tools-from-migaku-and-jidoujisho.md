# Study tools from the Migaku and jidoujisho study

## Status

Implemented (PR open for the owner's phone test). On 2026-10-20 the owner read the study of Migaku and
jidoujisho (report in the project's shared files, `migaku-jidoujisho-study/recommendations.md`)
and asked for all six recommended picks in one PR,
"to see whether they work", each with a setting so users can turn it on or off. That authorizes this
scope, not merge or release.

## Context / problem

- Migaku (closed source, studied for behaviour only) and jidoujisho (GPL-3.0, ideas only) offer
  study tools our app lacks: play modes that pause or repeat around each line, a known-word list
  with a comprehension score, skipping parts without speech, sending cards to Anki, a blur box for
  subtitles burned into the video, and a sturdier way to load the full caption track.
- YouTube often answers caption downloads without a proof-of-origin (PO) token with an empty reply.
  Migaku's page mints its own token in the page (the same flow as LuanRT's MIT-licensed BgUtils)
  and adds `c=WEB&pot=` to the track address. Our page currently relies on capturing the player's own
  download or YouTube's transcript panel.

## Goals

1. **Play modes** (Settings → Study tools → Play mode): Normal (default), Pause after each line,
   Read first (pause at the start of each line so it can be read before it is heard), Listen first
   (subtitles hidden while playing, shown while paused, pause after each line), Repeat each line
   (2, 3 or 5 times), and Pause on lines with one new word (needs known words).
2. **Skip silence** (Off by default, 2×, 4×, Jump): parts longer than 1.5 s with no subtitle line
   play faster or are jumped over, then the user's speed comes back just before the next line.
3. **Known words** (on by default): the word card has "I know this word"; known words are kept per
   language on the phone. The subtitle panel shows how much of the video's words are known
   ("Known words 78%") with the count of new words; tapping it offers "Mark all new words in this
   video as known" after a confirmation. "Mark new words in subtitles" (off by default) tints words
   that are neither known nor saved.
4. **Send to AnkiDroid** (on by default; the buttons show only when AnkiDroid is installed): the word
   card's "Send to AnkiDroid" adds a note (word, meaning, reading, sentence, translation, a YouTube
   link at the line's time) to a "DualSub Replay" deck with its own "DualSub Replay" note type;
   Saved words → Export gets "Send all to AnkiDroid". Words already sent are not sent twice.
5. **Blur box** (off by default): a blurred box on the video that can be dragged and resized to
   cover subtitles burned into the picture; its place is kept.
6. **Page-token captions** (beta, off by default): when the page's plain request for the track gives
   nothing, the page mints its own PO token (BotGuard challenge from YouTube's `att/get`, then
   `/api/jnn/v1/GenerateIT`, bound to the video id) and asks for the track again with
   `c=WEB&pot=`, a new request every 4 s, at most 5. The problem detail says how it went.
- Every new setting can be read and changed by the AI assistant; page-token captions wait for a tap.

## Non-goals

- No Migaku code, assets, data, keys or servers; no jidoujisho code. BgUtils is credited for the
  token flow (MIT); its public constants are YouTube's own web values.
- No word frequency lists, no import of known words, no audio or pictures on Anki cards (v1).
- No PO token for YouTube's translated tracks (`tlang`), the app's own downloads or video streams.
- No change to the single WebView, the caption providers' order, or translation.

## User-visible behavior

- A new "Study tools" section under More settings holds every toggle above, with short descriptions.
  Reset all settings turns them back to their defaults; known words and sent-card records stay.
- Play modes and skip silence act only on a full transcript (not live subtitles), not while a saved
  word's clip plays, and never while the video is paused. A seek by the user starts again from where
  it lands; tapping a line in the transcript plays it without pausing first.
- Known words are counted by dictionary form when the analyzer gives one (Japanese, English), else
  by the word itself, lower-cased; words saved to the vocabulary count as "learning", not new.
- AnkiDroid asks for its permission the first time; a refusal or failure shows a short message on
  the card (no crash). Without AnkiDroid installed no Anki button shows.
- The blur box shows on the YouTube page's player (inline and fullscreen) only while the setting is on.
- Page-token captions have three modes (owner request after the first phone test, 2026-10-20):
  Automatic (default) asks with a token only after a plain request came back empty and, once a token
  worked, asks with it first on later videos until a plain request works again; On always asks with
  a token first (as Migaku does); Off never makes one. A failure falls back to everything the page
  already does.
- Known words is Automatic by default (owner decision, 2026-10-20, replacing a short-lived "off with
  a one-time offer"), or Manual or Off. Automatic counts each different word of a line that plays,
  once per line per video (replays, seeks back and Repeat each line add nothing; Kien picked this
  on a decision card), and a word becomes known after 10 such lines (Settings: 5, 10 or 20). Saved
  words are never counted. "I know this word" still marks at once, and marking either way restarts
  that word's count. The word card shows "Seen 4 of 10 times".
- AnkiDroid often accepts cards only while it runs in the background (owner's phone test). The
  setting says so, and a refused card shows the reason with an Open AnkiDroid button.

## Technical constraints / invariants

- One WebView ([tech-stack invariants](../project/tech-stack.md#architecture-invariants)): the play
  modes, skip silence and blur box act on the page video through scripts; no second player.
- Every new page script re-checks `https:` and a `*.youtube.com` host. `webPageTranscriptScript`
  keeps exactly one `fetch(`; the token script is separate and only runs when the setting is on.
  The token never leaves the page; the app never sees or re-sends it.
- The BotGuard interpreter may only load from `https://www.google.com/js/th/`.
- Strings in all 8 interface languages; colors from the theme; settings in `dual_sub_preferences`
  and in `RESETTABLE_SETTING_KEYS`.
- AnkiDroid is reached through its public content provider (`com.ichi2.anki.flashcards`) with its
  `READ_WRITE_DATABASE` permission; its LGPL helper library is not added.

## Proposed approach / plan

1. `ui/StudySettings.kt`: preference keys, `StudyPlayMode`, `SkipSilence`, `StudySettings`,
   `readStudySettings`, `rememberStudySettings()` (listens to preferences), and the
   "Study tools" settings section (`MoreSettingsSection.STUDY`).
2. `ui/StudyPlayback.kt`: a pure `StudyPlaybackDriver` fed by the 33 ms playback tick (wrapped in
   `DualSubApp`) that returns pause, play-from, seek-paused, gap-speed and normal-speed actions;
   `YouTubeWebController` runs them. Listen first maps `ALWAYS` caption visibility to `PAUSED`
   (`studyCaptionVisibility`) in the view model.
3. `data/KnownWords.kt`: `KnownWordsRepository` (`files/known-words/<language>.txt`),
   `knownWordKey`, and `videoComprehension` (score, new words, lines with one new word);
   `ui/StudyTools.kt` provides `LocalStudyTools` from `LearningPlayerRoot` with the word card toggle,
   the subtitle panel chip, and new-word marks in `rememberAnnotatedSubtitleText`.
4. `data/AnkiDroidNotes.kt`: note fields and HTML (pure, tested) and the content-provider calls;
   word card button, export dialog option, manifest permission and `<queries>`.
5. `ui/StudyWebScripts.kt`: blur box script and gap-speed scripts; `StudyPageScripts` installs the
   box from `SingleYouTubePage`.
6. `WEB_PAGE_TOKEN_SCRIPT` installs `window.__dualSubPageTokenV1`; `PageCaptionTarget.pageToken`
   (from `PageTranscriptSource.pageTokenEnabled`) makes the transcript job try the token path after
   the plain requests; replies carry a `token` state shown in the problem detail.
7. AI assistant settings, the assistant guide, strings in 8 languages, AGENTS.md, tech-stack.md and
   THIRD_PARTY_NOTICES.md.

## Acceptance criteria

- [x] Driver: Pause after each line pauses once at a line's end and not again after resume; Read
  first pauses at the next line's start; Repeat plays a line N times then moves on; Pause on one new
  word pauses only after listed lines; a user seek resets without pausing at once; nothing happens
  while paused, in live fallback, or with Normal and skip silence off (`StudyPlaybackDriverTest`).
- [x] Skip silence: speeds up only in gaps over 1.5 s, restores before the next line, Jump plays
  from just before the next line, restores on pause and seek (`StudyPlaybackDriverTest`).
- [x] Listen first turns `ALWAYS` into `PAUSED` and leaves `NEVER` alone (`StudySettingsTest`).
- [x] Known words: keys use the dictionary form, lower case; the score counts word occurrences only;
  saved words count as learning; lines with exactly one new word are listed (`KnownWordsTest`).
- [x] Anki note fields escape HTML and link the line's time; duplicates are skipped by id
  (`AnkiDroidNotesTest`).
- [x] Blur box and gap-speed scripts check the origin; the transcript script keeps one `fetch(` and
  carries `pageToken`; the token script only loads the interpreter from `www.google.com/js/th/` and
  posts only to the page's own `att/get` and `GenerateIT` (`StudyWebScriptsTest`,
  `PageTranscriptScriptsTest`).
- [x] Every new setting is in `RESETTABLE_SETTING_KEYS`, the AI setting list, the assistant guide and
  all 8 languages (existing AI and language tests).
- [x] CI parity commands pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. the new tests above | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container / CI |
| Managed-device/emulator | `managed-device-tests` CI job (existing UI tests still pass) | CI |
| Physical-device/manual | Each play mode, skip silence, known words, Anki (with AnkiDroid installed), blur box on a phone | Owner, preview build |
| Live YouTube | Page-token captions on a video where the page otherwise shows "Current captions only" | Owner's phone; never run from the cloud |
| Documentation/process | AGENTS.md, tech-stack.md, THIRD_PARTY_NOTICES.md updated | PR review |

## Risks / edge cases

- Pausing through scripts lands 50–150 ms late; the driver pauses 100 ms before a line's end.
- YouTube may change the BotGuard flow or reject tokens minted this way; the setting is off by
  default and labelled beta, and failure falls back to the current page steps.
- Trusted Types or the page's script policy may block the interpreter; the page's nonce is copied
  and a Trusted Types policy is used when the page allows one.
- `backdrop-filter` over the video might not blur on some WebView versions; the box is also dimmed.
- AnkiDroid's provider differs across versions; a failure only shows a message.

## Release intent

`release:patch` (the default in AGENTS.md; nothing names another). Minor could fit a set of new
features, but the owner has not asked for it, so the PR keeps patch and mentions minor as an option.

## Implementation result

Built as planned, in one PR. What landed and where it differs from the plan:

- Settings: `ui/StudySettings.kt` (keys, `StudyPlayMode`, `SkipSilence`, `StudySettings`,
  `rememberStudySettings()`) and `ui/StudyToolsSettings.kt` (the "Study tools" section of More
  settings, before Overlay). The repeat count shows only for Repeat each line, and Mark new words
  only while Known words is on. The section also shows how many words are known, with Clear known
  words behind a confirmation.
- Play modes and skip silence: `ui/StudyPlayback.kt` (`StudyPlaybackDriver`, run by
  `StudyPlaybackRunner` on the page's playback tick from `DualSubApp`). Read first stops 20 ms into
  the next line so that line is on screen. The driver's own seeks (repeat, read first, jump) are told
  apart from the user's by where they land. Skip silence keeps the user's speed on the page video
  and brings it back from the app before the next line, or from the page by itself if the app stops
  asking (`webGapSpeedScript`). Listen first changes caption visibility in `CaptionVisibility.kt`
  (shown only while paused) and also hides YouTube's own captions while it is on.
- Known words: `data/KnownWords.kt` and `ui/StudyTools.kt` (`LocalStudyTools` from
  `LearningPlayerRoot`). The subtitle panel header shows a "Known %" chip that opens the details and
  a two-step "Mark all new words as known". New-word marks tint the original lines in the panel and
  the overlay card.
- AnkiDroid: `data/AnkiDroidNotes.kt`, the word card chip and "Send all to AnkiDroid" in Saved words
  → Export. The permission is asked the first time. AnkiDroid's card template is set when the note
  type is created; if AnkiDroid refuses that, its default layout stays.
- Blur box: `webBlurBoxScript` in `ui/StudyWebScripts.kt`, put back every 3 s while on (YouTube
  rebuilds its player), removed when turned off. Its place is kept in the page's `localStorage`.
- Page token: `ui/PageTokenScript.kt` (`WEB_PAGE_TOKEN_SCRIPT`), run by `PageTranscriptReader`
  only for targets with `pageToken`. After a failed mint the job stops asking BotGuard again for that
  attempt. The problem detail reads, for example, "page token refused-403".
- AI assistant: eight new `AiSetting`s (`study_play_mode`, `study_repeat_count`, `skip_silence`,
  `known_words`, `mark_new_words`, `send_to_ankidroid`, `blur_box`, `page_token_captions`, which
  waits for a tap) and the guide line. Strings in `strings_study.xml` in all 8 languages.
- Tests: `StudyPlaybackTest` (driver, Listen first visibility and stored settings, in place of the
  planned `StudyPlaybackDriverTest`/`StudySettingsTest`), `KnownWordsTest`, `AnkiDroidNotesTest`,
  `StudyWebScriptsTest`, additions to `PageTranscriptScriptsTest`, `PageTranscriptSourceTest`,
  `AiActionsTest`, `AiAssistantActionsTest` and `SubtitleHighlightTest`. `StudyToolsUiTest` (device)
  checks the settings section, the score chip and Mark all as known, and saves screenshots in the
  dark and light themes.
- Docs: AGENTS.md, tech-stack.md, PRIVACY.md and THIRD_PARTY_NOTICES.md.

## Validation result

- Passed in the cloud container (2026-10-20): `formatCheck`, `complexityCheck`, `testDebugUnitTest`
  (847 tests, 0 failures, including every new test above), `lintDebug`, `assembleDebug`,
  `assembleDebugAndroidTest`, and `python3 -m unittest discover -s tools/tests` (34 tests).
- Managed-device tests (including `StudyToolsUiTest`): run only in PR CI, as the container has no
  emulator; see the PR's latest Android CI run.
- Not verified here: every play mode, skip silence, known words, AnkiDroid and the blur box on a
  phone, and page-token captions against live YouTube. Live minting was deliberately not run from
  the cloud; it is for the owner's phone test with the preview build.

## Follow-up after the owner's first phone test (2026-10-20)

Kien tested preview build 461 and asked for four changes, all in PR #137:

- Known words off by default, with a one-time offer on the first "I know this word"
  (`shouldOfferKnownWords`, `known_words_offered`). The word card keeps known words while the tool is
  off, so the offer can count the first word.
- Page-token captions as Automatic / On / Off (`PageTokenMode` in `data/PageTranscriptSource.kt`).
  The page reports how it got the lines (`via`: address, token, player or panel); Automatic keeps
  `page_token_needed` from that (`learnedTokenFirst`). A stored on/off from build 461 reads as On or
  Automatic. Choosing a mode in Settings, or through the assistant, forgets what Automatic learned.
- AnkiDroid: the setting description and the refusal message say to keep AnkiDroid open in the
  background, and a refused card offers Open AnkiDroid (its launch intent).
- The "Known %" chip no longer blinks: the last count stays while lines are counted again (it used
  to drop to nothing and come back), and a new number counts to its value over 600 ms.
- Tests: `PageTranscriptSourceTest` (modes and Automatic's memory), `PageTranscriptScriptsTest`
  (token-first order and `via`), `AiActionsTest`, `AiAssistantActionsTest`, `StudyPlaybackTest`
  (defaults and the offer), `StudyToolsUiTest` (modes in Settings and the offer dialog on a device).

### Automatic known words (2026-10-20, after preview build 462)

Kien asked for an automatic mode by default (10% per encounter) and asked to discuss first. Agreed
plan, built in PR #137:

- `KnownWordsMode` (Automatic default, Manual, Off) and `knownWordsAfter` (5, 10 or 20) in
  `ui/StudySettings.kt`; the one-time offer dialog from build 462 is gone.
- `countEncounters`, `LineEncounters`, `parseSeenCounts` and `KnownWordsRepository.encounter` in
  `data/KnownWords.kt`. Counts live in `files/known-words/<language>.seen`, written 30 s after the
  first change, on pause and when the screen goes; words reaching the count are written as known at
  once. `CountPlayedLines` in `ui/StudyTools.kt` counts a line when it is the current line while the
  video plays, reusing the per-line word keys the Known % score already makes.
- Performance: no extra tokenizing; each new line costs a map update of about ten entries, and a
  file write at most every 30 s.
- The assistant gets `known_words` (auto, manual, off) and `known_words_after`.
- Tests: `KnownWordsTest` (counting, once per line per video, the counts file, the repository),
  `StudyPlaybackTest`, `AiActionsTest`, `AiAssistantActionsTest`, `StudyToolsUiTest` (modes and the
  word card's "Seen 4 of 10 times").
