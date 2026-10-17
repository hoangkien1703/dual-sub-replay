# More Japanese grammar points, and stopping an AI reply

## Status

Draft. The owner asked in the project thread on 2026-10-17 for (1) more Japanese grammar points
when translating a sentence, discussed first with research into how other projects do it and
which references can be used, and (2) a way to stop the AI assistant's reply after a question is
sent. Part 2 is implemented. Part 1 waits for the owner's pick between on-device points, an AI
grammar button, or both. Both parts ship in one PR. That does not authorize merging or publishing.

## Context / problem

1. **Grammar.** [Grammar up to N1](2026-10-07-grammar-to-n1-and-auto-caption-language.md) gives
   about 390 on-device points. Measured on 3,000 random Tatoeba sentences (CC BY 2.0 FR), 91% of
   particles, auxiliaries, dependent words and suffixes fall inside a grammar card, but only 63%
   of sentences are fully explained. The misses are mostly small, frequent pieces: word-building
   suffixes (たち, さん, 的, さ, 性, 化), the formal copula である, adverbial に (確かに),
   nominal こと/もの/ところ on their own, sentence-final な, わ, さ, なあ, and casual じゃ/じゃん.
2. **Stop.** After a question is sent, the assistant shows "Thinking…" until the service answers
   or times out. A slow model, a long thinking level or a wrong question cannot be cancelled
   except by starting a new chat.

Research for part 1 (2026-10-17): Renshuu, ichi.moe/Ichiran, Yomitan and Migaku all pair a
morphological analyzer with a curated pattern list, as this app does; newer tools (Trancy,
Language Reactor's Aria) add an LLM sentence breakdown on top. No reusable open-source grammar
detector exists. Usable references: Tanos JLPT lists (CC BY) for levels and a checklist, JMdict
(CC BY-SA 3.0) to check meanings, Tatoeba (CC BY 2.0 FR) for examples. Not copied: Bunpro,
Renshuu and DOJG (copyrighted), Tae Kim (CC BY-NC-SA) and Hanabira (AGPL), which do not fit the
app's MIT license.

## Goals

- While a question waits for its answer, Send becomes **Stop**. Stop cancels the request, keeps
  the question in the chat with "You stopped this reply." and **Try again**, and leaves the chat
  ready for a new question.
- Part 1: to be filled in after the owner's pick.

## Non-goals

- No streaming of partial answers (the assistant does not stream today).
- Stop does not undo actions the assistant already ran; they keep their Undo.

## User-visible behavior

- **Before:** after Send, the button is disabled and "Thinking…" stays until an answer or error.
- **After:** the button shows a Stop icon ("Stop the reply"). Tapping it removes "Thinking…",
  shows "You stopped this reply." with Try again under the question, and turns the button back
  into Send. An answer that arrives after Stop is dropped. Asking a new question or Try again
  clears the note.

## Technical constraints / invariants

- `AiChatClient.execute` already cancels the OkHttp call when its coroutine is cancelled, so
  cancelling the send job closes the connection.
- Strings live in `strings_ai.xml` in all 8 languages (`AppLanguageTest`, lint `MissingTranslation`).
- Actions that ran before Stop are kept and saved, as on any cancelled request.

## Proposed approach / plan

1. `AiAssistantController.stop()`: cancels the send job, sets `stopped`, saves the chat. `send`,
   `retry`, `newChat` and `openChat` clear `stopped`.
2. `AiComposer`: Stop button (`ai_stop_button`) in place of Send while sending.
3. `AiStoppedRow` under the messages, with Try again when the last message is the question.
4. Assistant guide: one line on Stop.
5. Part 1: after the owner's pick.

## Acceptance criteria

- [x] Stop while waiting: not sending, `stopped` set, question kept and saved, a late answer is
  dropped, Try again asks again and gets the answer (`AiAssistantControllerTest`).
- [x] A new question after Stop is sent and clears the note; Stop with nothing waiting changes
  nothing (`AiAssistantControllerTest`).
- [ ] Panel: Send becomes Stop while thinking; Stop shows the note and Try again; Try again gets
  the answer (`AiAssistantPanelUiTest.stopEndsAWaitingQuestionAndTryAgainAsksIt`, CI).
- [ ] Part 1 criteria after the owner's pick.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the Stop panel test | CI |
| Physical-device/manual | Ask a question with High thinking, tap Stop, then Try again | Owner's phone, preview APK |

## Risks / edge cases

- Stop between two tool rounds keeps the actions that already ran, shown in an answer bubble.
- A provider may still bill for a request that was cancelled after it reached the service.

## Release intent

`release:patch` (default; app code changes, no owner override).
