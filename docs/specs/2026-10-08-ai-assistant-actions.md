# AI assistant actions

## Status

Approved for implementation. This is PR 2 of the plan in the
[AI assistant panel spec](2026-10-08-ai-assistant-panel.md). On 2026-10-08 the owner asked for the
chatbot to "directly change settings, open videos" and then chose, on decision cards in the project
thread, that small setting changes (text size, which captions show, colors, playback speed) apply at
once with an Undo chip, while opening a video and changing translation ask first. That replaces the
"always confirm" rule of the earlier design. API keys and the AI switch are never touched. Memory and
an instructions box come in the next PR.

## Context / problem

The assistant from PR 1 can only explain: "make the subtitles bigger" gets a list of steps that the
user then has to follow by hand. Other assistants (ChatGPT, Gemini in Android, Copilot) act
directly and show what they did with a way to undo it. Learners also ask "what did she just say?",
which needs the lines on screen, and "save this word", which the word card already does but the chat
cannot.

## Goals

- The assistant can change the subtitle and learning settings that the settings page offers,
  replay or pause the video, change its speed, read the subtitle lines around the current position,
  save a word to the vocabulary, search YouTube, open a YouTube link the user typed, and change the
  translation engine or language.
- Small, reversible changes happen at once and show a chip under the answer with Undo.
- Searching YouTube, opening a video and changing translation show a card with the action's own
  button and Cancel; nothing happens until the user taps it.
- The user always sees what was done or read, in their own language.

## Non-goals

- Memory and the instructions box (next PR).
- Changing the original caption language, natural subtitle flow, overlay positions, theme colors,
  downloads, or anything in the AI settings (service, key, model, history, the switch).
- Deleting or editing saved words, import or export, Reset all settings, signing in or out.
- Opening links other than YouTube, or a video whose link the user did not type (no guessed IDs).
- Playing a paused video without replaying a line; reading the video title, comments or the page.
- A switch that turns actions off (actions are only what the user asks for, and each one shows).

## User-visible behavior

- **At once with Undo:** text size (80–200%), when original and translated captions show (Always,
  Only when paused, Never), Highlight spoken words, Default view (Transcript panel or
  Scroll-friendly overlay), Caption format, Landscape split view, Custom subtitle colors and the
  three subtitle colors, Pronounce tapped words, Word learning mode, Tap word for definition, Lock
  overlay to video player, playback speed (0.25×–2× for the open video), and Save to vocabulary. Each
  shows a chip under the answer, for example "✓ Text size: 150% · Undo". Undo puts the earlier
  value back and the chip then reads "Undone". Choosing a subtitle color also turns Custom subtitle
  colors on, and Undo turns it back.
- **At once, nothing to undo:** replay the current or previous line, pause the video. A chip says
  what happened.
- **Reading the screen:** when the question is about what is being said and no line came with it,
  the assistant may read the current line and up to two lines before and after (with their
  translations). A chip "Read the subtitles on screen" shows each time.
- **Asks first:** Search YouTube for "…", Open this YouTube video, Google Translate on or off,
  Translate to <language>. A card under the answer shows the action with its button (Search, Open,
  Change) and Cancel. Search and Open close the panel and load the page in the app's YouTube
  view. A translation change reloads the video's translations, and its chip then offers Undo.
- **Text the user did not type:** when the question carried a subtitle line, a problem's details,
  pictures or files, or the assistant read the subtitles while answering, setting changes also
  wait for a tap (Apply / Cancel), so a sentence in a video cannot change the app on its own.
- **Limits:** at most 3 actions per answer (reading the screen does not count), at most 4 requests
  per question. A model or service that cannot take actions answers as before, with steps.
- **Saved chats:** chips keep their text. Undo works only while the app stays open (it survives a
  screen rotation); a card that was never answered reads "Not done" after the app restarts.
- **No video open:** playback actions and reading the screen tell the model that no video is open;
  nothing changes.

## Technical constraints / invariants

- One WebView only. Search and open reuse the existing navigation request
  (`browserNavigationRequestId`) and `classifyMainFrameUrl`; only `https://m.youtube.com/` pages are
  opened. The speed script re-checks the executing origin like the pause script
  (`PlaybackArchitectureTest`).
- The model only chooses from a fixed list of actions with validated arguments (`assistant/AiActions.kt`);
  the app maps them to its own setters. Values outside the list or range are refused with an
  English reason the model can explain.
- Tool calls use the OpenAI `tools` format. Gemini signs its calls (`extra_content`), so each
  assistant tool-call message is sent back exactly as received. Tool messages live only inside
  one answer; saved chats keep a one-line note per action instead, which is also what later
  questions show the model.
- A service that rejects `tools` (for example OpenRouter's 404 "No endpoints found that support
  tool use") is asked again without them, and that model gets no tools for the rest of the session.
- Labels shown to the user come from `strings_ai.xml` and the settings strings in all 8 languages;
  notes for the model stay English.
- Privacy: reading the screen sends up to five subtitle lines and their translations; video IDs,
  titles and saved words are still never sent. PRIVACY.md says so.
- F-Droid build: unchanged, no assistant.

## Proposed approach / plan

1. Client: `AiTool`, `AiToolCall`, `AiReply`; `tools` in the request body, `tool_calls` and
   `tool_call_id` in messages, `UNSUPPORTED_TOOLS` error mapping.
2. `assistant/AiActions.kt`: the tool list with JSON schemas, `parseAiAction` (validation into a
   sealed `AiAction`), `AiAppActions` (the app side: look, check, describe, perform; Undo is a list
   of actions that put things back), records (`AiActionRecord`) and their notes for the model.
3. Controller: a tool loop in `ask()` (max 4 requests, max 3 actions), the untrusted-text rule,
   pending cards, Undo, Apply/Cancel, records saved with the answer; history JSON keeps them.
4. UI: `ui/AiAssistantActions.kt` implements `AiAppActions` with `AppViewModel` setters, the
   caption-visibility preferences, the player mode, the page video (pause, replay, speed via a new
   `AiPlayerControls` bound in `DualSubExperience`), the vocabulary, and a new
   `AppViewModel.openYouTubePage`. Chips and cards under the assistant's bubble.
5. Guide (`assets/ai/assistant-guide.md`), PRIVACY.md, AGENTS.md AI invariant, strings in 8 languages.

## Acceptance criteria

- [x] A reply with a `change_setting` call changes that setting at once and shows a chip; Undo
  restores the earlier value (unit test with a fake app; UI test for the chip and Undo).
- [x] `search_youtube`, `open_youtube_video` and `change_translation` show a card and change nothing
  until the button is tapped; Cancel leaves everything as it was (unit + UI test).
- [x] With a subtitle line or file in the question, or after reading the screen, a setting change
  waits for Apply (unit test).
- [x] Invalid arguments, a fourth action, and a link the user never typed are refused with a note to
  the model and change nothing (unit tests).
- [ ] Gemini's signed tool calls are echoed back unchanged; a tools refusal is retried without tools
  (unit tests; live check with Gemini).
- [x] Saved chats keep the chips' text; pending cards read "Not done" after reload (unit test).
- [x] Reading the screen sends the current line with up to two lines before and after, marked as
  quoted data (unit test).
- [x] The speed script re-checks the YouTube origin (`PlaybackArchitectureTest`).
- [ ] All new text exists in the 8 interface languages; chips and cards use the app theme
  (screenshot check).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: tool JSON, call parsing, argument validation, tool loop, limits, untrusted rule, undo/confirm/cancel, history round trip, look text, saved word, search URL, speed script | Local and CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local and CI |
| Managed-device/emulator | `AiAssistantPanelUiTest`: a fake reply with a setting change shows a chip and Undo works; a search card waits for its button; screenshots `ai_panel_action_chip`, `ai_panel_action_card` | CI managed device |
| Live service | A few requests with the owner's Gemini key: a tool call round trip with a signed call | Build machine |
| Physical-device/manual | Preview APK: "make subtitles bigger", "slow down the video", "what did she just say?", "save 勉強", "find videos about cooking in Japanese" | Owner |
| Live YouTube | Search and open load in the app's YouTube view; speed changes the page video | Owner |

## Risks / edge cases

- Small models call the wrong action or loop: the 3-action and 4-request limits stop loops, every
  change shows a chip with Undo, and bigger actions need a tap.
- Prompt injection through subtitles, files or error text: setting changes then need a tap;
  navigation and translation always do; nothing can touch keys or the AI settings.
- Tool support differs: models without tools answer with steps as before. OpenRouter's free picker
  only picks models that take tools when tools are sent.
- YouTube may reset the speed on the next video; the chip says it applies to the open video.
- Undo after the user changed the same setting by hand puts back the value from before the
  assistant's change.

## Release intent

`release:patch` (no label). I recommended `release:minor`; the owner chose patch on the decision
card on 2026-10-08.

## Implementation result

Built as planned, with these details and deviations:

- `assistant/AiTools.kt` holds the tool and tool-call types and their JSON; `assistant/AiAnswerRun.kt`
  holds the loop for one answer, so the controller only starts it and keeps its results.
- Seven tools: `look_at_video`, `control_playback`, `change_setting`, `save_word`, `search_youtube`,
  `open_youtube_video`, `change_translation`.
- Undo is data, not a stored function: each done action returns the actions that put things back
  (the earlier setting value, removing the saved word, the earlier translation engine and language),
  and Undo runs them through the actions bound at that moment. So Undo after a screen rotation
  reaches the new screen, and the old screen is not kept in memory. If the app refuses an Undo (the
  video was closed, for example), the chip only loses its Undo button.
- A setting the assistant sets to the value it already has gets a chip without Undo, and the model
  is told it was already that value.
- The player's controls (`AiPlayerControls`) are bound in `DualSubExperience` rather than in
  `DualSubApp()`, which is at the method-length limit.
- An answer whose only content was actions the app could not run (for example a model that sends a
  tool call when none were offered) is shown as a bad reply instead of an empty bubble.
- The speed the assistant set is remembered for that video only; YouTube's own speed menu is not
  read, so a later change there is not seen by Undo.

## Validation result

- Passed locally: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug
  assembleDebugAndroidTest` (605 unit tests, 0 failures). Lint shows only warnings that were already there.
- Unit tests added: `AiToolsTest`, `AiActionsTest`, `AiAnswerRunTest`, `ui/AiAssistantActionsTest`,
  new cases in `AiAssistantControllerTest` (chip and Undo, Undo after rebinding, refused Undo,
  card confirm and cancel, history round trip, failure after an action, tools refusal remembered),
  `AiChatClientTest` and `PlaybackArchitectureTest` (speed script origin check).
- Managed device (`AiAssistantPanelUiTest`, screenshots `ai_panel_action_chip`,
  `ai_panel_action_card`): runs in PR CI; see the PR for the result.
- Live service: early in development, a Gemini request with a tool returned a tool call. The final
  code's round trip with the full tool list was not checked live, because using the owner's key
  from the chat was not allowed in this session. Unverified; unit tests cover the signed-call
  format (`AiToolsTest`, `AiChatClientTest`).
- Physical device and live YouTube: unverified; the owner tests the preview APK.
