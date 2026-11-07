# AI translation: fewer requests and replies that always parse

## Status

Implemented. Owner picked "Batch requests + JSON mode" on 2026-11-07. Merge and release need the owner's OK.

## Context / problem

Owner report 2026-11-07: AI translation with Gemini free keys still ran into the free limits, even with keys from two Google accounts. A screenshot also showed "The AI reply is not the JSON asked for: Unterminated array at character 33 of `{"sentences":[{"id":1,"rows":[""Green" means v…`".

Two causes were found in the code:

1. **Background drip.** `prefetchInBackground` (`ui/PlaybackTranslation.kt`) runs every 3 s while the video plays and hands minutes 2 to 5 ahead to `AiSubtitleTranslator.translateNextBatch`. That function sent a request for any missing sentence, even one. As playback moves, one or two sentences cross the five-minute mark at each step, so the app sent a small request every few seconds. The new `AiBackgroundPrefetchTest` counts 117 requests for 25 minutes of playback on `main` (1fce735). The design in [AI subtitle translation](2026-10-28-ai-subtitle-translation.md) expects about one request per 60 sentences. Free keys count requests, not their size, so per-minute and daily limits ran out.
2. **Unescaped quote.** No JSON output mode was requested, so a model sometimes wrote a quote inside a row without escaping it. The reply could not be read, and all of its up to 60 sentences fell back to Google's lines until the app restarted. This wastes a request but does not cause retries.

Not causes: limited keys already rest 1 minute, then 2, 4… up to 30, and a busy server gets two retries (1 s, 3 s). Gemini counts free limits per Google Cloud project, so two keys in one project share them.

## Goals

- The background look-ahead sends AI requests only when they carry most of a request.
- Gemini and OpenAI replies are valid JSON of the asked shape. A stray quote from any other service is still read.

## Non-goals

- No new settings or UI. No change to Google Translate pacing, key rotation or the assistant chat.

## User-visible behavior

No UI change. While a video plays, AI lines still arrive before their rows play. Far fewer requests reach the AI service, and a reply with a stray quote no longer turns its whole window into Google's lines.

## Proposed approach / plan

- `translateNextBatch` returns without a request unless the first batch has at least `MIN_AI_BACKGROUND_SENTENCES` (40) sentences or `MIN_AI_BACKGROUND_CHARS` (4,000) characters (`fillsBackgroundRequest`). The background loop then waits for 30 s of playback, as it already does when nothing is missing. Sentences that never fill a request go out with the playing window's request, which fills up to 60 sentences from the minutes ahead.
- `aiTranslationJsonSchema()` is a strict `json_schema` `response_format` for `{"sentences":[{"id":int,"rows":[string]}]}`. `AiChatRequest.responseFormat` carries it, and `chatRequestBody` sends it. Only Gemini and OpenAI get it (`aiTranslationResponseFormat(provider)`). When a model refuses it (400, or a 400 naming the model), the translation model drops it for the rest of its life and asks again without it, the way a refused thinking level is dropped.
- `parseAiTranslationReply` retries a broken reply with `withStrayQuotesEscaped`: inside a string, a quote that is not followed by `, : ] }` is escaped.

## Acceptance criteria

- [x] `AiBackgroundPrefetchTest`: 25 minutes of steady playback sends at most 16 requests (14 measured, 117 before), and the minute after the playing time always has AI lines.
- [x] `theBackgroundWaitsUntilItCanFillARequest`: 39 missing sentences send nothing; 40 send one request.
- [x] `aQuoteTheModelLeftUnescapedIsStillRead`: the screenshot's reply shape parses; valid JSON is unchanged.
- [x] Gemini and OpenAI requests carry the schema; other services don't. A refused schema costs one extra request, once.
- [x] Existing translator, prefetch and access tests pass unchanged.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | Cloud container |
| Managed-device/emulator | PR CI `managed-device-tests` | CI |
| Physical-device/manual | Owner: watch 20+ minutes with Gemini AI translation and check usage at aistudio.google.com/rate-limit | Owner's phone; the live Gemini API was not reachable from the cloud |

## Risks / edge cases

- Gemini's OpenAI-compatible endpoint documents structured output but was not tried live with streaming. If it refuses the schema, the fallback sends the old request once per opened video.
- Slow speech (under 40 sentences in 4 minutes) leaves the background idle; the playing window's request then translates those sentences about a minute ahead, as after a seek.
