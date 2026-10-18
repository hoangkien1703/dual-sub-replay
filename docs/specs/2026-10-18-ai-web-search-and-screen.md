# AI assistant: YouTube search, web search, and looking at the screen

## Status

Approved for implementation. In the project thread (2026-10-18) Kien asked for the AI assistant
to search the web, for example to suggest videos, and to take a screenshot of the app by itself
when it needs one. After the options were researched he picked "All three": the app searches
YouTube itself, the AI service's own web search as a setting, and Tavily search with a free key
the user adds, each together with the screenshot action. Merge and release stay with the owner.

## Context / problem

The assistant's `search_youtube` action ([actions spec](2026-10-08-ai-assistant-actions.md))
only opens YouTube's search page after the user taps Search; the model never sees the results,
so it cannot suggest videos. It also cannot look anything up on the web, and it only knows the
screen through the subtitle lines `look_at_video` reads.

Facts checked on 2026-10-18 (links in the thread):

- YouTube's web search (`/youtubei/v1/search`, client `WEB`) answers without a key or cookies
  and marks videos with captions (`CC`); the filter `EgQQASgB` keeps videos with subtitles.
- Gemini's free keys get no Google Search grounding (Gemini 3 and newer: paid keys only), and
  Google's OpenAI-compatible endpoint, which the app uses, documents no search for chat.
- Claude: `web_search` server tool, $10 per 1,000 searches plus tokens.
- OpenRouter: `openrouter:web_search` server tool, about $0.007 per search, also on free models.
- OpenAI: in Chat Completions only through the `gpt-5-search-api` model (`web_search_options`).
- Tavily: `POST https://api.tavily.com/search` with `Authorization: Bearer tvly-…`;
  1,000 free searches a month, no card.

## Goals

- **Find YouTube videos (free, every service).** A new action `find_youtube_videos` (query,
  optional `subtitles_only`) runs at once: the app asks YouTube's search and gives the model up
  to 10 videos as quoted data (video ID, title, channel, length, views, age, whether it has
  subtitles). The chat shows a chip "Searched YouTube for “…”". The model can then suggest
  videos with `open_youtube_video`, which now also accepts a video ID that the app's search
  found in this chat; each suggestion shows a card with the video's title and an Open button
  (still nothing happens until the tap). Suggestions don't count toward the 3 actions an answer
  may take; at most 5 suggestions show per answer. On by default (AI settings → Search and
  screen → Find YouTube videos).
- **Web search** (AI settings → Search and screen → Web search): Off (default), "Through
  <service>" for Claude, OpenRouter and OpenAI (says it costs extra on the user's bill), or
  "Tavily" (free key):
  - Claude: the `web_search` server tool (`web_search_20250305`, `max_uses` 3) goes with the
    request; `pause_turn` replies are continued; the queries show as chips.
  - OpenRouter: the `openrouter:web_search` server tool (5 results) goes with the request.
  - OpenAI: a `search_web` action; the app asks `gpt-5-search-api` with the same key and gives
    the answer to the chat model as quoted data.
  - Tavily: the same `search_web` action, answered by Tavily with the user's Tavily key.
  - Service search on a service without it (Gemini, OpenCode, Other) counts as off, and the
    setting says why.
  - Sources the service or Tavily returns show under the answer ("Sources", title and site);
    tapping one opens it in the browser. They are saved with the chat.
- **Tavily key**: Get a free key (opens app.tavily.com), Paste key or type it; saved encrypted
  with the other keys (`ai_assistant_keys`, left out of backups), shown by its last 4
  characters with Remove. Pasting a Tavily key selects Tavily search. A key Tavily rejects says
  so in the chat result, never stops the chat.
- **Look at the screen** (on by default, AI settings → Search and screen). A new action
  `look_at_screen` runs at once: the app hides the assistant panel for a moment, copies the
  app window (`PixelCopy`), scales it to at most 1280 px and sends it as a JPEG picture with
  the question. The chat shows "Looked at the screen". The picture is never written to chat
  history. At most one screenshot per answer.
- Search results, web results and screenshots count as other people's text: after one, setting
  and memory changes in that answer wait for a tap (the existing `otherText` rule).
- With full access, `change_ai_setting` can also change `ai_youtube_search`, `ai_web_search`
  and `ai_screen`.

## Non-goals

- No project server or shared key; Tavily and the AI keys are the user's own.
- No Google Search for Gemini keys (free keys can't use it; Tavily covers Gemini users).
- No automatic Tavily key from chat (`add_api_key` stays for AI service keys).
- No search inside the YouTube page; the WebView keeps its own navigation.

## User-visible behavior

Before: "Suggest a video for N3 listening" gives a search button only. After: the assistant
searches, names a few videos with why they fit, and shows an Open card for each.

Before: questions about news, slang or recent facts are answered from the model's memory.
After, with web search on: the model searches, answers with sources under the reply.

Before: "What's on my screen?" reads subtitle lines only. After: the assistant takes a
screenshot (the panel blinks out for a moment) and answers about what it sees.

Failures: a failed search returns "Not done: …" to the model, which explains it; a model that
can't read pictures gets "Not done: this model can't read pictures" and the answer goes on.

## Technical constraints / invariants

- One WebView only ([AGENTS.md](../../AGENTS.md)); YouTube search is an app request to a fixed
  host (`www.youtube.com`), with no cookies, a 4 MiB reply cap and no redirects.
- The assistant acts only through the fixed tool list in `assistant/AiActions.kt`; only offered
  tools run; the new ones are offered only while their setting is on.
- User-visible text lives in `strings_ai.xml` in all 8 languages; diagnostic text for the model
  stays English.
- Keys stay excluded from backups (`BackupRulesTest`).

## Proposed approach / plan

1. `data/YouTubeVideoSearch.kt`: request body, parser of `videoRenderer` items, OkHttp client.
2. `assistant/AiWebSearch.kt`: `AiWebSearchMode`, `AiWebSource`, Tavily request/parser, OpenAI
   search-model request/parser, URL-citation parsing, the `search_web` tool.
3. `AiChatRequest.webSearch`: Anthropic and OpenRouter server tools in the request bodies;
   Anthropic `pause_turn`, server-tool queries and citations; OpenRouter annotations.
4. `AiAnswerRun`: run `find_youtube_videos`, `search_web` and `look_at_screen`; allow found IDs
   in `open_youtube_video`; continue `pause_turn`; drop a screenshot a model can't read.
5. Settings: `youtubeSearch`, `webSearch`, `screenLook` in `AiAssistantSettings`; Tavily key in
   `AiKeyStore` storage; Search and screen section in the AI settings; full-access settings.
6. UI: screenshot capture in `ui/AiScreenCapture.kt`, panel hidden while capturing; sources
   under answers; chip kinds `screen` and `web`.
7. Strings in 8 languages, guide text, unit tests, a Compose test with screenshots.

## Acceptance criteria

- [x] With YouTube search on, the tool list has `find_youtube_videos`; parsing a captured
      YouTube reply gives the videos with title, channel, length, views, age and CC flag.
- [x] `open_youtube_video` accepts an ID found by the app's search in this chat and refuses an
      invented one; suggestions don't use up the 3 actions, at most 5 show.
- [x] Claude requests carry `web_search_20250305` only when web search is set to the service's own search;
      OpenRouter requests carry `openrouter:web_search` only for OpenRouter; other services none.
- [x] Claude `pause_turn` is continued; server-tool queries become chips; citations and
      OpenRouter/OpenAI `url_citation` annotations become sources, saved with the chat.
- [x] `search_web` is offered for OpenAI (service mode) and with a Tavily key (Tavily mode) only;
      Tavily replies parse to text plus sources.
- [x] A Tavily key is saved encrypted under `ai_assistant_keys` and removable.
- [x] `look_at_screen` is offered only while its setting is on; its picture goes in the request
      but not into saved chats; a model that refuses pictures gets the screenshot dropped.
- [x] After search results or a screenshot, setting changes in that answer wait for a tap.
- [x] Every new string exists in all 8 languages (`AppLanguageTest`, lint).
- [x] CI parity checks pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: new parser, request-body, answer-run and history tests | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container / PR CI |
| Managed-device/emulator | AI settings Search and screen section test with `saveUiEvidence` | PR CI (no KVM here) |
| Physical-device/manual | Kien: ask for video suggestions, ask a web question with Tavily, ask "what's on screen" | Kien's phone, preview APK |
| Live YouTube | One live search request from the container (done while researching) | Container network |

## Risks / edge cases

- YouTube can change its search reply; the parser skips anything it can't read and the model
  gets "no videos found".
- On some phones the video area of a `PixelCopy` copy may be black; to check on the preview.
- Claude web search can be turned off by an organization admin (400); the app then asks again
  without web search for that answer.

## Release intent

`release:patch` (the default; no owner instruction for another type). A minor bump would also
fit a new feature; that stays the owner's call.

## Implementation result

Built as planned, in one PR:

- `data/YouTubeVideoSearch.kt` posts to `https://www.youtube.com/youtubei/v1/search` with the web
  client (English texts, the phone's country, filter `EgQQASgB` for videos with subtitles or
  `EgIQAQ==` for videos only), reads `videoRenderer` items at any depth, and keeps at most 10.
- `assistant/AiWebSearch.kt` holds the web search mode (Off / the service's own / Tavily), the
  route per service (`IN_REQUEST` for Claude and OpenRouter, `SEARCH_MODEL` for OpenAI through
  `gpt-5-search-api`, `TAVILY`), sources, Tavily and OpenAI search parsing, the three new tools
  and `aiOfferedTools`.
- `AiAnswerRun` runs `find_youtube_videos`, `search_web` and `look_at_screen` as reading
  actions: each makes later changes in the answer wait for a tap. `open_youtube_video` accepts a
  video ID the search found in this answer or earlier in the chat (its IDs are saved in the
  chip's note); at most 5 suggestions per answer, outside the 3-action limit, and each still waits
  for the Open tap. Claude's `pause_turn` is continued, and the request limit per answer went from
  4 to 6 so a search plus suggestions fits.
- Deviation: the Tavily key lives in its own small `AiSearchKeyStore` (same `ai_assistant_keys`
  preferences and Keystore cipher) instead of inside `AiKeyStore`, since it is not an AI service
  key. A Tavily key pasted or typed in the API keys section is recognised (`tvly-`) and saved
  there too.
- The screenshot hides the panel and its dim background for two frames, copies the window with
  `PixelCopy`, scales it to at most 1280 px and sends it as JPEG in a user message right after the
  tool results; it is never written to chat history.
- Full access can also switch the three new settings. The assistant guide and AGENTS.md describe
  the new tools.

## Validation result

Cloud container, 2026-10-18:

| Check | Result |
| --- | --- |
| `formatCheck`, `complexityCheck` | Passed (two `when` blocks in `AiActions.kt` split to stay under the complexity limit). |
| `testDebugUnitTest` | Passed, 776 tests, including the new `YouTubeVideoSearchTest`, `AiWebSearchTest`, answer-run and history tests. |
| `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` | Passed; no new lint warnings in the new code. |
| Compose test `searchAndScreenSettingsSaveATavilyKeyAndSwitchTheSearches` (with screenshots, also Light theme) | Not run here (no emulator); runs in PR CI `managed-device-tests`. |
| Live YouTube search | One request from the container returned 20 videos, all with CC for the subtitles filter (while researching). |
| Physical phone: video suggestions, Tavily web question, "what's on screen" (including whether the video area shows in the screenshot) | Not verified yet; for Kien on the preview build. |
| Live Claude, OpenRouter, OpenAI web search | Not verified (needs paid keys); request and reply shapes follow the services' documentation and are unit tested. |
