# AI assistant panel

## Status

Approved for implementation. The owner agreed to the design on 2026-10-08 in the project thread
"AI assistant panel design", accepted every recommendation in it, and changed chat history to
"keep 7 days by default, with longer or off in the AI settings". This spec covers the first PR
(the panel, chat, settings and problems). Later PRs (word help, setting changes, pictures,
on-device Gemini Nano, polish) get their own specs and link back here.

## Context / problem

Learners ask the same things again and again: where a setting is, why translation switched to
the device, what a sentence means. Today the app answers none of these in place. Translation
problems show in a small top-right popup (`ui/OnlineTranslationNotice.kt`), caption failures in the
transcript panel, and settings are spread over five "More settings" sections.

The owner wants an AI assistant behind a top-right button: a chat panel that slides in from the
right, answers questions about the app and the language being learned, shows the app's problems
with an "Ask AI about this" option, and works with the user's own key for Google Gemini, OpenAI,
OpenRouter or another OpenAI-compatible service. It is on by default and can be turned off
completely in More settings.

## Goals

- A top-right AI button in the GitHub build that opens a right-side chat panel.
- Bring-your-own-key setup for Gemini, OpenRouter, OpenAI, OpenCode Zen, OpenCode Go and a custom
  OpenAI-compatible address, with a service picker, each service's key link, a model field and
  "Test connection". Chat opens only after the key has answered once.
- API keys encrypted with the Android Keystore and kept out of backups.
- The app's current problems (translation fallback, translation stopped, caption load failure)
  shown at the top of the panel with their existing action and "Ask AI about this".
- Chat history kept on the phone for 7 days by default; Off, 30 days and Forever in settings,
  plus "Delete chat history now". Never backed up.
- A switch that turns the whole feature off, on by default.

## Non-goals

- The AI changing settings (a later PR, with a confirmation card and Undo).
- "Ask AI" in the word card and phrase bar, "Save to my words", answer cache (word-help PR).
- App screenshots taken by the assistant, on-device Gemini Nano, streaming replies, the button in
  fullscreen video.
- Any project-operated server or shared key.
- The F-Droid build: it has no AI assistant (`BuildConfig.AI_ASSISTANT` is false).

## User-visible behavior

- **Top bar (GitHub build, AI on):** a sparkle button at the top right replaces the translation
  problem icon. When translation fell back to the device, stopped, or the captions failed to
  load, the button shows a dot (amber for the fallback, red otherwise).
- **AI off, or F-Droid build:** the top bar is exactly as before: the translation problem icon and
  its popup. Nothing is ever sent to an AI service.
- **Panel:** slides in from the right over a dim background (about 88% of the width in portrait,
  420 dp at most), closes with the close button, a tap outside, or Back. The video keeps playing.
  Top to bottom: header (History, New chat, AI settings, Close); problem cards; chat; suggestion
  chips when the chat is empty; input box with Send, and under it the + button (Photo or File), the
  model and the thinking level, as in other chat apps. The whole panel uses the app's dark teal
  theme.
- **No key yet:** the panel shows a setup card: a service picker (Google Gemini, OpenRouter,
  OpenAI, OpenCode Zen, OpenCode Go, each with one line on what it costs), that service's three
  steps, "Get a free key" (Gemini, OpenRouter) or "Get a key" opening the service's key page, and
  Paste key.
- **Key check:** a saved key opens chat only after it answers one tiny request through the chosen
  service, address and model. Until then the panel shows "Checking your key…", why the check
  failed with Try again and the key buttons, or a "Check key" button (a key saved by an earlier
  build, or a new model or address). A key rejected later goes back to this card.
- **AI settings:** the gear in the panel header opens the AI settings inside the panel, with Back
  to return; More settings → AI assistant shows the same controls: switch, service (plus Other
  with its HTTPS address), key (paste, type, test, remove), chat history, and the model under
  Advanced.
- **Problem cards:** the same title, message, diagnostic detail and action as today's popup
  ("Try Google again" / "Retry translation"), or "Subtitles couldn't load" with Retry; each has
  "Ask AI about this", which sends the problem with its English diagnostic detail.
- **Chat:** replies render natively (bold, code, lists) and can be selected and copied. While
  waiting, "Thinking…" shows. A failure shows a short, translated reason (wrong key, no credit,
  unknown model, too many requests, no connection, service problem) and Try again.
- **Model and thinking level:** the model button lists the current model, the service's suggested
  models, and "All <service> models…", a searchable page of the service's own model list (marked
  Free or Pictures where OpenRouter says so) where any typed name can be used too. A new model
  answers one tiny request before the chat switches to it; if it fails, the chat stays on the old
  model and says why. Thinking is Auto (nothing sent), Low, Medium or High (`reasoning_effort`); a
  model that refuses it says to choose Auto.
- **Pictures and files:** + opens the system photo picker or file picker. Up to 4 pictures or files
  per question wait above the input box with a thumbnail or icon and a remove button; a question
  can be only files ("Please explain this."). Pictures are scaled to at most 1568 px and sent as
  JPEG, PDFs up to 8 MB as they are, and text and subtitle files up to 2 MB as quoted text (the
  first 20 000 characters). Other files, and larger ones, are refused with the file's name. Sent
  messages show the files' names. A model that cannot read pictures or PDFs says so; Try again
  resends them (after choosing another model), and the next question goes on without them.
- **Suggestion chips:** "What can this app do?", "How do I make subtitles bigger?", and, while a
  subtitle line is active, "Explain the current line" (sends that line and its translation).
- **History:** each chat is saved after every reply. The History view lists saved chats (newest
  first) with a delete button; tapping one reopens it. Chats older than the chosen period are
  deleted when the app starts and after each save. Off keeps the current chat in memory only and
  deletes saved chats.
- The assistant answers in the app's interface language and knows the current settings,
  languages, translation engine, build and version, but not browsing history, URLs or saved words.

## Technical constraints / invariants

- One WebView only ([invariants](../project/tech-stack.md#architecture-invariants)): replies are
  rendered in Compose, never in a WebView.
- The F-Droid build keeps every proprietary or online service out:
  `BuildConfig.AI_ASSISTANT = !isFdroidBuild`, like `ONLINE_TRANSLATION`.
- UI text lives in `res/values*/strings_ai.xml` in all 8 languages (`AppLanguageTest`, lint
  `MissingTranslation`). Diagnostic text from providers stays English.
- Keys: AES-256-GCM key in `AndroidKeyStore` (never exportable); the encrypted API key and its last
  4 characters live in the `ai_assistant_keys` preferences file, excluded from cloud backup and
  device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`, `BackupRulesTest`). Keys
  never appear in logs, error details, the AI's context, chat history or practice exports.
  Provider error text is redacted for the key before it is shown.
- Network: OkHttp, HTTPS only (cleartext is already off in the manifest), one host per request
  (the chosen service), IPv4 addresses first, 10 s connect / 15 s write / 90 s read timeouts, 30 s
  for a key check or model list and 100 s for a question, a 1 MiB reply cap (4 MiB for a model
  list), cancellation on New chat. A custom address must be `https://` without user info.
- Pictures and files live only in the open chat's memory. Saved chats keep their names, never their
  content, so reopening a chat sends nothing old. One request carries at most 16 MiB of base64
  pictures and PDFs, newest first.
- Chat history lives in `files/ai-chats/` (excluded from backup), written atomically, at most 100
  chats.
- Unit tests stay plain JUnit4 without Android: Keystore and SharedPreferences sit behind small
  interfaces; the provider client is tested through its pure request/reply functions.
- "Reset all settings" keeps resetting subtitle settings only; AI settings, keys and history are
  separate.

## Proposed approach / plan

1. `assistant/` package: `AiProvider` (base URL, default model, key page), `AiSettings` (stored in the
   `ai_assistant` preferences), `AiKeyStore` + `KeystoreSecretCipher`, `AiChatClient`
   (OpenAI-compatible `/chat/completions`, error mapping, key redaction), `AiConversation` (message
   model, request building with a size budget), `AiChatHistory` (JSON encode/decode, retention
   pruning, atomic file store), `AiAssistantGuide` (system prompt from `assets/ai/assistant-guide.md`
   plus a settings snapshot), `AiMarkdown` (bold, code and list spans).
2. `AiAssistantController` holds the panel state and actions; `AiAssistantViewModel` wires it to
   Android (Keystore, preferences, files, OkHttp) and is provided through `LocalAiAssistant` (null
   in the F-Droid build).
3. UI: `AiAssistantButton` in the top bar, `AiAssistantPanel` over `DualSubExperience`,
   `AiAssistantSettings` as a new `MoreSettingsSection.AI_ASSISTANT` (GitHub build only); the
   settings page can open straight on that section from the panel.
4. Backup rules, strings in 8 languages, docs: mission, tech stack, PRIVACY.md, AGENTS.md, README.

## Acceptance criteria

- [ ] GitHub build, AI on (default): the top-right AI button opens the panel; Back, the close
  button and a tap outside close it.
- [ ] With no key, the panel and settings offer the service picker, each service's steps and key
  link, and Paste key; nothing is sent.
- [ ] A new key, model or address must pass the key check before the input box appears; a network
  timeout during a later check keeps an earlier pass, a rejected key does not.
- [ ] The panel uses the app theme's colors, also where it is drawn outside `DualSubApp`.
- [ ] The panel's gear opens the AI settings inside the panel; Back returns to the chat.
- [ ] A saved key is stored only encrypted, shows as "ending in abcd", survives an app restart, and
  Remove deletes it. Backup rules exclude `ai_assistant_keys.xml` and `ai-chats/`.
- [ ] Sending a question returns the provider's reply; wrong key, unknown model, no credit, rate
  limit, no connection and server errors each show their own translated message and Try again.
- [ ] Test connection reports success or the same error messages.
- [ ] The bar under the input box changes the model (after a test request) and the thinking level;
  the model page lists the service's chat models and accepts a typed name.
- [ ] + adds up to 4 pictures or files; they show above the input box, can be removed, go with the
  next question as image, PDF or quoted-text parts, and are not saved in chat history.
- [ ] With a translation fallback or failure or a caption error, the button shows a dot and the panel
  shows the problem card with its existing action and "Ask AI about this".
- [ ] AI off: today's translation problem icon and popup are unchanged and no AI request is made.
- [ ] F-Droid build: no AI button, no AI settings section.
- [x] Chat history: kept 7 days by default; Off/30 days/Forever change retention; Off and "Delete
  chat history now" delete saved chats; History reopens and deletes chats.
- [x] All new text exists in the 8 interface languages.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: key store with a fake cipher, request/reply/error parsing and redaction, URL validation, request budget, history retention and JSON round trip, markdown spans, backup rules, strings in every language | Local and CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local and CI |
| Managed-device/emulator | `AiAssistantPanelUiTest`: button opens the panel, setup card and service picker without a key, a key typed in the panel's settings fails then passes its check before the input box appears, the panel background is the theme color, problem card with "Ask AI about this", a fake reply appears, the model and thinking bar, files above the input box sent with a question, AI off shows the old icon | CI managed device |
| F-Droid | `fdroid-build` and `fdroid-device-tests` stay green; APK has no AI button | CI |
| Physical-device/manual | Preview APK on a phone with a real Gemini (free) key: ask a question, wrong key message, test connection, history after restart | Owner, preview APK |
| Live YouTube | Problem card during a real Google Translate fallback | Owner, opportunistic |
| Documentation/process | PRIVACY.md, mission, tech stack, AGENTS.md describe the optional bring-your-own-key assistant | Review |

## Risks / edge cases

- Keystore keys can be lost (device reset of secure storage): the key then can't be decrypted, is
  removed, and the user is asked to paste it again.
- Providers differ in error shapes (Gemini's compatible endpoint may return a list): parsing
  accepts both and falls back to the HTTP status.
- Model names change: defaults live in `AiProvider`; the model field overrides them.
- Subtitle text is written by strangers: the system prompt marks it as quoted content, and the
  assistant cannot change anything in this PR.
- Cost: only the last 16 messages and about 24 000 characters are sent per request, plus at most
  16 MiB of pictures and PDFs, newest first.
- Not every model reads pictures or PDFs (OpenAI's chat endpoint reads PDFs, Gemini's and
  OpenRouter's read both, many open models read neither): the refusal gets its own message, and the
  chat can go on without the files.
- Big PDFs use memory while they are encoded: they are capped at 8 MB each.

## Release intent

`release:minor`: a large new user-facing feature. The owner accepted this recommendation on
2026-10-08.

## Implementation result

PR 1 of the plan, as approved:

- `assistant/`: `AiProvider` (Gemini, OpenAI, OpenRouter, Other; key prefix detection),
  `AiSettings` (on by default, model per service, custom HTTPS address, history retention),
  `AiKeyStore` (Keystore AES-GCM, one key per service, last 4 characters as the hint, an
  unreadable key is removed), `AiChatClient` (OpenAI-compatible `/chat/completions`, error kinds,
  key redaction, 1 MiB cap), `AiConversation` (16 messages / 24 000 characters per request),
  `AiChatHistory` (retention, 100-chat cap, atomic JSON file), `AiAssistantGuide` +
  `assets/ai/assistant-guide.md`, `AiMarkdown` (bold, code, headings, bullets),
  `AiAssistantController` and `AiAssistantViewModel`.
- UI: the top-right button with an amber/red problem dot, falling back to the old
  translation icon when the assistant is off or absent (`ui/AiAssistantUi.kt`); the right-side
  panel with problem cards, the setup card (Get a free key, Paste key that detects the service
  and tests the connection), the welcome chips, "Explain the current line", chat bubbles,
  retry, and history (`ui/AiAssistantPanel.kt`, `ui/AiAssistantChatParts.kt`); More settings →
  AI assistant (`ui/AiAssistantSettings.kt`), also shown inside the panel.
- `BuildConfig.AI_ASSISTANT` is false in the F-Droid build, so it has no view model, button, or
  section.
- Backup rules exclude `sharedpref:ai_assistant_keys.xml` and `file:ai-chats/`.
- 77 English strings in `strings_ai.xml` (3 service names untranslatable), translated into the
  7 other interface languages.
- Deviations: the custom address must be HTTPS, so a local Ollama server over plain HTTP is not
  supported yet. "Reset all settings" leaves the assistant's settings, keys, and chats alone.
  The panel shows the AI settings itself instead of opening More settings, which keeps
  `DualSubApp` unchanged apart from the top-right button.

## Changes after the owner's first phone test (2026-10-08)

The owner tried preview build 383 with a real key and reported three problems and three requests.

- **Light purple panel.** `LearningPlayerRoot` draws the panel beside `DualSubApp`, outside
  `DualSubTheme`, so Material's light defaults showed. `AiAssistantOverlay` now wraps itself in
  `DualSubTheme`, the scrim and problem colors come from the theme, and AGENTS.md has a new "UI
  conventions" section so later features follow the theme.
- **Saved key never worked** ("Testing…" forever, then `InterruptedIOException` in chat). With
  fake keys all three services answer within about 1 s from a server, so the address and request
  are right; the 120 s whole-call timeout fired on the phone. The likely cause (inferred, not
  reproduced) is the one the caption client already works around: OkHttp 4 tries a host's
  addresses one by one, and on a network with a broken IPv6 route each IPv6 attempt stalls until
  it times out, while the WebView races IPv4 and IPv6. The AI client now resolves IPv4 first
  (`PreferIpv4Dns`, IPv6 kept as the fallback), connects within 10 s, gives the key check 30 s and a
  question 100 s, and reports a timeout as its own message ("didn't answer in time") with the
  exception text as detail. Keys with characters an HTTP header cannot carry are refused when
  pasted.
- **Chat before the key works.** Chat now opens only after the key check passes (see "Key check").
  The passed setup (address and model) is stored next to the key in the excluded-from-backup key
  file, so it survives restarts and a new key, model or address is checked again.
- **Settings button in the panel**, **service picker with key links**, and **OpenCode support**:
  as described above. OpenRouter's default model is now `openrouter/free` (free models only), so a
  new free key works without credit; OpenCode Zen defaults to its free `big-pickle`, OpenCode Go to
  `deepseek-v4-flash`. OpenCode only serves some models through `/chat/completions` (not its GPT,
  Claude, Gemini or Qwen models); others fail the key check with the service's message. A plain
  `sk-` key stays with the chosen OpenAI or OpenCode service.

Also asked for on 2026-10-08 and built in this PR: the model and thinking-level bar under the
input box with the searchable model page, and sending pictures and files (see "User-visible
behavior"). The code review before pushing also found and fixed: a reply body that timed out after
its headers could escape as a raw `IOException`; a rejected key in a question sent before a
service switch could wipe the new service's passed check; a used-up free limit (429) wiped a passed
check; a 200 reply that carries only an error object (OpenRouter) showed as "no text" instead of
its own error; a failed Test connection still said "Connected"; Try again showed while the key
needed a new check.

## Changes after the owner's second phone test (2026-10-08)

OpenRouter worked; Gemini still did not, some free OpenRouter models could not be chosen, and the
OpenCode key page was unclear.

- **Gemini:** replaying the app's exact key check with the owner's key showed that the key and the
  request were right. Google answered 503 "This model is currently experiencing high demand" for
  `gemini-flash-latest` on 2 of 3 tries, so the check failed and chat never opened. Offline tests
  and CI use a fake service, so they could not see this. The app now asks a busy service (503 or
  429 rate limit) again after 1 s and 3 s, a key check that only finds the model busy counts as
  passed (a refused key fails before that), and the busy message suggests another model.
  `gemini-pro-latest` left the suggestions because free keys have no quota for it
  (`gemini-2.5-flash` replaces it). Keys starting with `AQ.` (AI Studio's newer format) are
  recognised as Gemini keys.
- **OpenRouter free models:** some are reserved for coding apps (403 "only available on agentic
  harnesses") and some providers were rate-limited (429). A 403 that does not name the key now
  shows the service's reason instead of "rejected the key".
- **OpenCode:** the steps now say where the key is: workspace page, API Keys, Create API Key.

## Validation result

Local, on Linux with JDK 21 (2026-10-08, after the model bar and attachments):

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck` | Passed. Files this PR adds have zero ktlint violations; detekt finds no smells. |
| `testDebugUnitTest` | Passed: 564 tests, 0 failures. AI tests cover key checks and their persistence, IPv4-first DNS, timeouts, thinking levels, model lists, error-only 200 replies, attachment kinds, text cuts, picture sizing, data URLs, request parts and budget, history keeping only file names, the attachment notices, and the controller's model switch and draft files. `AppLanguageTest` passes with `strings_ai.xml` in all 8 languages. |
| `lintDebug` | Passed; no warnings in the AI files, no `MissingTranslation`. |
| `assembleDebug assembleDebugAndroidTest` | Passed. |
| `AiAssistantPanelUiTest` (managed device) | Not run locally (no emulator here); runs in CI `managed-device-tests`. CI on the previous push failed two tests that clicked the off-screen Save button; both now scroll to it first. |
| F-Droid build and device tests | Not run locally (needs the NDK and submodules); runs in CI `fdroid-build` and `fdroid-device-tests`. |
| Real provider key, physical phone, photo and file pickers, live Google Translate fallback | Not verified; needs the owner's preview APK test with a real key. Whether Gemini's OpenAI-compatible endpoint accepts PDF `file` parts is also unverified; a refusal shows the "can't read pictures or PDFs" message. |

The offline tests prove the logic; the panel's behavior on a phone and with real services stays
open until CI's managed-device run and the owner's phone test confirm it.
