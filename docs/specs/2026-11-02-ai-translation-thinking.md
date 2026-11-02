# AI thinking for translation

## Status

Implemented (owner request 2026-11-02). Merge and release still need the owner's go-ahead.

## Context / problem

AI subtitle translation ([spec](2026-10-28-ai-subtitle-translation.md)) always turns the model's
thinking off (`aiTranslationEffort`: Gemini `none`, OpenAI `none` or `minimal`), because subtitles
must arrive before the line is spoken and free limits count thinking tokens. The owner's Gemini
free-tier page (2026-11-02) showed a peak of 7 of 15 requests a minute and 3.9K of 250K tokens a
minute, so tokens are far from the limit, and the owner asked for a setting that switches
translation from no thinking (the default) to a higher level.

## Goals

- Settings → Translation → AI thinking for translation: Off (default), Low, Medium.
- Shown only with an AI translator, and only when the AI assistant's service can switch thinking
  through `reasoning_effort`: Gemini and OpenAI.
- The description says higher levels are slower and use more of the free limit.
- The AI assistant can change it (`ai_translation_thinking`), waiting for the user's tap because it
  translates the open video again.

## Non-goals

- No High level. A window holds up to 60 sentences; with High thinking the reply could run past the
  45 s request limit (`AI_TRANSLATION_TIMEOUT_MS`), and Google would translate instead.
- No level for Claude: Anthropic's `output_config.effort` is not thinking (no effort already means
  full effort, so "Low" would mean less). OpenRouter, OpenCode and custom services pick their own
  thinking; their models keep translating as they come.
- No separate level for the minutes translated ahead: the app sends one request at a time for the
  playing window plus the minutes after it, so live and ahead lines share one request.
- The assistant chat's own thinking level (More settings → AI assistant) is unchanged.

## User-visible behavior

- Google, or on this device only: no change, the row is hidden.
- AI translation (best quality) or AI only with a Gemini or OpenAI key: the row shows under AI model
  for translation. Off sends `none` (`minimal` for the first GPT-5 models), as before. Low and Medium
  send `reasoning_effort: low|medium`.
- Changing the level reloads the open video. Each level keeps its own translation cache
  (`<service>/<model>+thinking-<level>`), so a new level translates the video again; Off keeps the
  cache made before this setting existed.
- A model that refuses the level is asked again without it (existing fallback), so translation
  still works.
- With AI translation (best quality), Google's lines still show while the AI thinks. With AI only,
  the first lines take longer to appear.

## Technical constraints / invariants

- No new key or service; the user's own key only (see AGENTS.md, AI assistant).
- `AI_PROMPT_VERSION` is not raised, so Off keeps everyone's saved translations.
- All user text in the 8 interface languages.

## Proposed approach / plan

1. `AiTranslationThinking` (Off/Low/Medium), `aiTranslationThinkingLevels(provider)`,
   `aiTranslationEffort(provider, model, thinking)`, `aiTranslationScope` in
   `assistant/AiTranslationAccess.kt`; preference `ai_translation_thinking`.
2. `DualSubUiState.aiTranslationThinking`, `AppViewModel.setAiTranslationThinking` (reloads like the
   model choice), reset with Reset all settings.
3. `AiTranslationThinkingSettings` chips in `ui/GoogleWaitSettings.kt`, shown in
   `OnlineTranslationSettingsRows`.
4. `AiSetting.AI_TRANSLATION_THINKING` (asks) with its value text, aliases, current value, apply and
   label.

## Acceptance criteria

- [x] Gemini and OpenAI offer Off, Low and Medium; other services offer none (unit test).
- [x] Low and Medium are sent as `reasoning_effort`; Off sends the old values (unit test).
- [x] The chosen level goes with every translation request and names its own cache (unit test).
- [x] The row shows with an AI translator, Off selected by default, no High; it is hidden with Google
  (`OnlineTranslationUiTest`, CI).
- [x] The assistant parses `ai_translation_thinking` values and waits for a tap (`AiActionsTest`).
- [ ] Owner checks Low and Medium on a phone with a real Gemini key: quality and delay.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including `AiTranslationAccessTest` and `AiActionsTest` | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| Managed-device/emulator | `OnlineTranslationUiTest` thinking tests; screenshot `settings-ai-translation-thinking` | PR CI |
| Physical-device/manual | Owner compares Off/Low/Medium on a Japanese and an English video | Owner's phone, preview APK |
| Live YouTube | Same as above | Owner's phone |

## Risks / edge cases

- Medium on a long window may approach the 45 s limit on slow connections; Google then keeps those
  lines and the amber icon says why.
- Thinking tokens count toward the free tokens-per-minute limit (250K for Gemini Flash-Lite), far
  above the measured 3.9K peak.
