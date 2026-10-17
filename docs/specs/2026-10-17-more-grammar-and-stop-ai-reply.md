# More Japanese grammar points, and stopping an AI reply

## Status

Implemented. The owner asked in the project thread on 2026-10-17 for (1) more Japanese grammar
points when translating a sentence, discussed first with research into how other projects do it
and which references can be used, and (2) a way to stop the AI assistant's reply after a question
is sent. After the research the owner chose "Both" on the decision card: more on-device points for
everyone plus an AI grammar button for users with a key. Both parts ship in one PR. That does not
authorize merging or publishing.

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
- More on-device points for the small, frequent pieces found missing, and fixes for the wrong or
  missing cards found while measuring, so more lines are fully explained. Explanations are written
  for this project in all 8 interface languages; nothing is copied from the references.
- An **Explain the grammar with AI** button under the word card's grammar section, shown while the
  assistant is on. It closes the card, opens the assistant and asks it to explain the whole line,
  passing the line, the other subtitle line, the tapped words and the points the app found.

## Non-goals

- No streaming of partial answers (the assistant does not stream today).
- No copied grammar text and no new dependency or dataset shipped in the app. Tatoeba sentences
  were used only offline to measure coverage.
- Nouns that are vocabulary rather than grammar (上, 中, そんなこと) get no card.
- Stop does not undo actions the assistant already ran; they keep their Undo.

## User-visible behavior

- **Grammar, before:** 休んだ and 読んだら showed no past or たら card; 本を持っています showed the
  N1 〜をもって ("by means of"); 聞いて下さい (kanji) and してもらえなかった showed only 〜て;
  私たち, 寒さ, 経済的, 静かに, 早く, である, じゃない, じゃん, ございます, ３人 and sentence
  endings な, なあ, わ, さ showed nothing.
- **Grammar, after:** each of those has a card with its JLPT level (counters show as 〜人, 〜時).
  Japanese cards also show **Explain the grammar with AI** while the assistant is on.
- **Stop, before:** after Send, the button is disabled and "Thinking…" stays until an answer or error.
- **Stop, after:** the button shows a Stop icon ("Stop the reply"). Tapping it removes "Thinking…",
  shows "You stopped this reply." with Try again under the question, and turns the button back
  into Send. An answer that arrives after Stop is dropped. Asking a new question or Try again
  clears the note.

## Technical constraints / invariants

- `AiChatClient.execute` already cancels the OkHttp call when its coroutine is cancelled, so
  cancelling the send job closes the connection.
- Strings live in `strings_ai.xml` in all 8 languages (`AppLanguageTest`, lint `MissingTranslation`).
- Actions that ran before Stop are kept and saved, as on any cancelled request.
- Grammar stays Kuromoji IPADIC plus the catalogue and rules of
  [grammar up to N1](2026-10-07-grammar-to-n1-and-auto-caption-language.md); every catalogue point
  keeps an example sentence in `JapaneseGrammarCatalogueTest` and its own meaning.
- The AI button only queues a question; the assistant still sends nothing until it has a checked
  key, and the line goes to the model as quoted data like "Explain the current line".

## Proposed approach / plan

1. `AiAssistantController.stop()`: cancels the send job, sets `stopped`, saves the chat. `send`,
   `retry`, `newChat` and `openChat` clear `stopped`.
2. `AiComposer`: Stop button (`ai_stop_button`) in place of Send while sending.
3. `AiStoppedRow` under the messages, with Try again when the last message is the question.
4. Assistant guide: one line on Stop.
5. Catalogue: 24 points (〜たち, 〜さん/様/氏, 〜さ, 〜的, 〜性, 〜化, 〜者, 〜人, 〜語,
   〜くなる/〜になる, 〜くする/〜にする, 〜にする, adverbial 〜に and 〜く, 〜である, 〜じゃない,
   〜じゃん, sentence-final な, なあ, わ, さ, ございます, 〜ねばならない, 〜ください).
6. Rules: voiced past だ/だら after a verb; 下さる and もらえる as て-helpers; 〜ていい without も;
   counters after a number or 何. Fixes: 〜をもって no longer matches 持つ in kanji or 〜ている;
   〜んです keeps the う of のでしょう.
7. `AiAssistantController.askFromApp` / `sendPendingQuestion`: the word card queues a question;
   the panel sends it once the assistant is ready and not busy; closing the panel drops it.
8. `GrammarExplanations` computes the line's points once and shows the AI button;
   `aiGrammarContext` builds the hidden context; the guide asks for a part-by-part answer.

## Acceptance criteria

- [x] Stop while waiting: not sending, `stopped` set, question kept and saved, a late answer is
  dropped, Try again asks again and gets the answer (`AiAssistantControllerTest`).
- [x] A new question after Stop is sent and clears the note; Stop with nothing waiting changes
  nothing (`AiAssistantControllerTest`).
- [ ] Panel: Send becomes Stop while thinking; Stop shows the note and Try again; Try again gets
  the answer (`AiAssistantPanelUiTest.stopEndsAWaitingQuestionAndTryAgainAsksIt`, CI).
- [x] Every new catalogue point is found in its own example sentence with its own meaning
  (`JapaneseGrammarCatalogueTest`).
- [x] 休んだ is past, 読んだら is 〜たら, 持っています is not 〜をもって, 聞いて下さい,
  してもらえなかった, していいか, counters, 〜くなる, 〜にする and sentence-final な are found as
  intended (`JapaneseGrammarTest`).
- [x] On the same 3,000 random Tatoeba sentences, fully explained lines rise from 63% to 82% and
  covered grammar morphemes from 91% to 96% (scratch measurement, not committed).
- [x] A queued question waits without a checked key, is sent with its context once ready, and is
  dropped when the panel closes (`AiAssistantControllerTest`); the context quotes the line, the
  tapped words and the found points (`AiAssistantPromptTest`).
- [ ] The grammar button asks about the whole line and closes the card; it is hidden while the
  assistant is off (`AiAssistantPanelUiTest`, CI).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including the Stop panel test | CI |
| Physical-device/manual | Ask a question with High thinking, tap Stop, then Try again; tap Translate on a Japanese line, check the new cards and tap Explain the grammar with AI | Owner's phone, preview APK |

## Risks / edge cases

- Stop between two tool rounds keeps the actions that already ran, shown in an answer bubble.
- A provider may still bill for a request that was cancelled after it reached the service.
- Short endings (な, さ, わ) can fire on analyzer mistakes, for example さ in 「使いやすさ」; the
  measurement showed these are rare. 〜にする lists "make into, treat as, or choose" because the
  words around it rarely tell which.

## Release intent

`release:patch` (default; app code changes, no owner override).
