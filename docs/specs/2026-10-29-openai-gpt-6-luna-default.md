# OpenAI starts on GPT-6 Luna

## Status

Implemented. On 2026-10-29 the owner asked whether the OpenAI default should stay `gpt-5-mini` or
move to "GPT 6.1 Luna", the cheapest model with good enough quality. There is no 6.1 Luna: the model
is `gpt-6-luna` (released 2026-09-22); only Sol and Terra are 6.1. After the comparison below the
owner picked "Yes, switch to GPT-6 Luna". This does not authorize merging or publishing.

## Context / problem

- `AiProvider.OPENAI.defaultModel` was `gpt-5-mini`; the quick list was mini, nano, `gpt-5`.
- Price per million tokens (input / output, checked 2026-10-29 on third-party price pages):
  `gpt-6-luna` $0.10 / $0.50, `gpt-5-mini` $0.25 / $2.00, `gpt-6.1-sol` $2 / $10. Output is most of
  the cost of an AI subtitle window, so Luna is about 4x cheaper, and it is a newer generation.
- AI subtitle translation turns OpenAI's thinking off with `reasoning_effort: "minimal"`. Only the
  first GPT-5 models (`gpt-5`, `gpt-5-mini`, `gpt-5-nano`) know "minimal"; later GPTs, Luna included,
  call it "none". The app would drop the setting after the 400 and translate with the model's default
  (medium) thinking: slower and dearer.
- Later GPTs reportedly answer function tools in Chat Completions only with `reasoning_effort: "none"`
  ("Function tools with reasoning_effort are not supported"). The assistant offers its actions with
  almost every question, and Auto sends no effort, so the model's own default would apply. That 400
  mentions "function", so it was read as "this model cannot take actions" and the actions were
  dropped for that setup.

## Goals

- New OpenAI keys use `gpt-6-luna`; the quick list is `gpt-6-luna`, `gpt-6.1-sol`, `gpt-5-mini`.
- People whose OpenAI key was checked with `gpt-5-mini` and who never chose a model keep it
  (`aiModelsKeptFromChecks`, as for the Gemini default change of 2026-10-11).
- Translation turns thinking off with the word the model knows: "minimal" for the first GPT-5
  models, "none" for every other OpenAI model (`openAiNoThinkingEffort`).
- With Auto thinking and actions offered, an OpenAI model outside the first GPT-5 models gets
  `reasoning_effort: "none"` (`aiAssistantEffort`). Low, Medium and High are still sent as chosen.
  If the service refuses that "none" as a thinking level, the round is asked again without a
  thinking level, as Auto always was (owner merged without a live test, so this keeps Auto working).
- A 400 about `reasoning_effort` on a request with tools and a thinking level is a thinking-level
  problem (`UNSUPPORTED_THINKING`: "Choose Auto under the chat box"), not a refusal of actions.

## Non-goals

- No change to other services, the web search model (`gpt-5-search-api`), keys or chat history.
- No move to OpenAI's Responses API.

## Validation

- Unit tests: default and quick list (`AiSettingsTest`), effort per model and Auto with tools
  (`AiSettingsTest`), Auto's retry (`AiAssistantControllerTest`), translation effort (`AiTranslationAccessTest`), the tools + reasoning_effort
  400 (`AiChatClientTest`).
- Result 2026-10-29 in a cloud container: `formatCheck`, `complexityCheck`, `lintDebug` and all 1,006
  `testDebugUnitTest` tests passed.
- Not verified live: nobody had an OpenAI key on 2026-10-29. Luna's effort values and the tools rule
  come from model spec pages. Before merge, someone with an OpenAI key should try the preview build:
  ask the assistant to change a setting, and turn on Translate subtitles with → AI translation.
