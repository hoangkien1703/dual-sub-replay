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
- Bring-your-own-key setup for Gemini, OpenAI, OpenRouter and a custom OpenAI-compatible
  address, with a "Get a free key" link, a model field and "Test connection".
- API keys encrypted with the Android Keystore and kept out of backups.
- The app's current problems (translation fallback, translation stopped, caption load failure)
  shown at the top of the panel with their existing action and "Ask AI about this".
- Chat history kept on the phone for 7 days by default; Off, 30 days and Forever in settings,
  plus "Delete chat history now". Never backed up.
- A switch that turns the whole feature off, on by default.

## Non-goals

- The AI changing settings (a later PR, with a confirmation card and Undo).
- "Ask AI" in the word card and phrase bar, "Save to my words", answer cache (word-help PR).
- Pictures, on-device Gemini Nano, streaming replies, the button in fullscreen video.
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
  Top to bottom: header (History, New chat, Close); problem cards; chat; suggestion chips when the
  chat is empty; input box with Send.
- **No key yet:** the panel shows a setup card with the service choice, a key field, Save, a
  "Get a free key" link (Gemini, OpenRouter) or "Get a key" (OpenAI), and a short note on what is
  sent. More settings → AI assistant has the same controls plus model, server address (Other),
  Test connection and chat history.
- **Problem cards:** the same title, message, diagnostic detail and action as today's popup
  ("Try Google again" / "Retry translation"), or "Subtitles couldn't load" with Retry; each has
  "Ask AI about this", which sends the problem with its English diagnostic detail.
- **Chat:** replies render natively (bold, code, lists) and can be selected and copied. While
  waiting, "Thinking…" shows. A failure shows a short, translated reason (wrong key, no credit,
  unknown model, too many requests, no connection, service problem) and Try again.
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
  (the chosen service), 15 s connect / 90 s read / 120 s call timeouts, 1 MiB response cap,
  cancellation on New chat. A custom address must be `https://` without user info.
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
- [ ] With no key, the panel and settings offer service, key, Save and the key link; nothing is sent.
- [ ] A saved key is stored only encrypted, shows as "ending in abcd", survives an app restart, and
  Remove deletes it. Backup rules exclude `ai_assistant_keys.xml` and `ai-chats/`.
- [ ] Sending a question returns the provider's reply; wrong key, unknown model, no credit, rate
  limit, no connection and server errors each show their own translated message and Try again.
- [ ] Test connection reports success or the same error messages.
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
| Managed-device/emulator | `AiAssistantPanelUiTest`: button opens the panel, setup card without a key, problem card with "Ask AI about this", a fake reply appears, AI off shows the old icon | CI managed device |
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
- Cost: only the last 16 messages and about 24 000 characters are sent per request.

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
  AI assistant (`ui/AiAssistantSettings.kt`), opened directly from the panel.
- `BuildConfig.AI_ASSISTANT` is false in the F-Droid build, so it has no view model, button, or
  section.
- Backup rules exclude `sharedpref:ai_assistant_keys.xml` and `file:ai-chats/`.
- 77 English strings in `strings_ai.xml` (3 service names untranslatable), translated into the
  7 other interface languages.
- Deviations: the custom address must be HTTPS, so a local Ollama server over plain HTTP is not
  supported yet. "Reset all settings" leaves the assistant's settings, keys, and chats alone.
  The settings-open request goes through `AiAssistantHost` rather than a new `DualSubApp`
  parameter, to stay within the detekt limits.

## Validation result

Local, on Linux with JDK 21 (2026-10-08):

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck` | Passed. New files have zero ktlint violations; `DualSubApp`/`DualSubExperience` stay within the detekt limits. |
| `testDebugUnitTest` | Passed: 528 tests, 0 failures. New: `AiSettingsTest` (7), `AiKeyStoreTest` (4), `AiChatClientTest` (6), `AiConversationTest` (4), `AiChatHistoryTest` (5), `AiMarkdownTest` (4), `AiAssistantControllerTest` (15), `AiAssistantPromptTest` (6), plus the updated `BackupRulesTest`. `AppLanguageTest` passes with `strings_ai.xml` in all 8 languages. |
| `lintDebug` | Passed; no warnings in the new files, no `MissingTranslation`. |
| `assembleDebug assembleDebugAndroidTest` | Passed. |
| `AiAssistantPanelUiTest` (5 managed-device tests) | Not run locally (no emulator here); runs in CI `managed-device-tests`. |
| F-Droid build and device tests | Not run locally (needs the NDK and submodules); runs in CI `fdroid-build` and `fdroid-device-tests`. |
| Real provider key, physical phone, live Google Translate fallback | Not verified; needs the owner's preview APK test with a free Gemini key. |

Criteria checked above are proven by the offline tests. The others are covered by
`AiAssistantPanelUiTest` and the controller tests offline, and stay open until CI's managed-device
run and the owner's phone test confirm them.
