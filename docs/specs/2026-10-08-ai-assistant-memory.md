# AI assistant memory and instructions

## Status

Approved for implementation. This is PR 3 of the plan in the
[AI assistant panel spec](2026-10-08-ai-assistant-panel.md), after the
[actions PR](2026-10-08-ai-assistant-actions.md). On 2026-10-08 the owner asked for the assistant to
"auto update memory, add instructions memory", and on a decision card chose that memories save on
their own from the user's words, with a chip and Undo. The rest follows the recommendations in the
doc "AI assistant: actions and memory": an instructions box, a Memory list with a switch, memory on
for new users, no memories from text the user did not type, a chat without memory, and a one-time
"what's new" note for people who already use the assistant.

## Context / problem

Every chat starts from nothing: a learner has to say their level, goal and how they like answers
each time. ChatGPT, Claude, Gemini and Copilot all keep a short memory the user can see, edit and
delete, plus a box for standing instructions. Saved memories are also a known attack path: a web
page once got ChatGPT to save false memories, and here a video's subtitles could try the same.

## Goals

- An instructions box in AI settings: text the user writes, sent with every question.
- Saved memories: short notes the assistant saves when the user's own message says something
  lasting about them, or "remember …". Each save shows a chip under the answer with Undo and Manage.
- The assistant can update a memory that changed and forget one the user asks it to forget, both
  with Undo.
- A Memory page (in the panel and in More settings → AI assistant) lists memories with edit and
  delete, a Delete all, and a Use memory switch.
- A new chat can be started without memory.
- People who already used the assistant see a one-time note about actions and memory.

## Non-goals

- Summarising whole chats into memory, or searching old chats.
- Reading the vocabulary or Practice progress to guess the user's level.
- Syncing memories between phones; memories never leave the phone except with a question.
- A memory per video or per language.

## User-visible behavior

- **Instructions:** More settings → AI assistant → Memory (and the panel's Memory page) has a
  "Your instructions" box, up to 1,500 characters, for example "I'm JLPT N4. Explain in Vietnamese.
  Keep answers short." It goes with every question, also in chats without memory.
- **Saving:** when the user's own message says something lasting (level, goal, exam date, how they
  like explanations) or asks to remember something, the assistant saves one short sentence, at most
  200 characters, in the reply language. A chip under the answer says "Saved to memory: “…” · Undo
  · Manage". It never saves from subtitles, files, pictures, error details or its own answers; when
  the question carried such text, or the assistant read the screen, a save waits for a **Save**
  button instead, like setting changes do. It does not save health, religion, politics or ID and
  account numbers unless asked.
- **Updating and forgetting:** a new fact that changes a saved one replaces it ("Updated memory:
  “…”"); "forget that …" removes one ("Forgot: “…”"). Both have Undo. Duplicates are not saved
  twice.
- **Full:** at most 30 memories. When full, the assistant asks which one to replace instead of
  dropping one.
- **Memory page:** the list (newest last) with edit and delete for each, "Delete all memories" with
  a confirmation, and a "Use memory" switch, on by default. Off: the assistant neither reads nor
  saves memories; the list stays until deleted. Manage on a chip opens this page.
- **Chat without memory:** a new, empty chat shows a "Use memory in this chat" chip, on by default.
  Turned off, that chat neither reads nor saves memories (instructions still apply), and the chat
  shows "Memory is off in this chat."
- **First page and what's new:** the intro page says the assistant changes settings with Undo and
  remembers what the user tells it. People who answered the intro before this version see a
  one-time card at the top of the chat ("New: actions and memory") with Manage memory and Got it.
- **Privacy:** memories and instructions stay on the phone in `files/ai-memory/`, excluded from
  backups and device transfers, and go to the chosen AI service with each question. PRIVACY.md says
  so.

## Technical constraints / invariants

- Memory changes only through two new tools in the fixed list (`save_memory`, `forget_memory`),
  checked by the app like the other actions; the model never writes the file directly. The memory
  tools are offered only while memory is on for the chat.
- Memories are numbered in the system prompt; `replaces` and `forget_memory` use those numbers,
  resolved against the list sent with that answer, so a list that changed during the answer
  cannot make the model touch the wrong memory.
- Undo is data (actions that put the memory back), like the other actions.
- All new text in `strings_ai.xml` in the 8 interface languages; notes for the model stay English.
- `BackupRulesTest` covers the new directory. The F-Droid build has no assistant.

## Proposed approach / plan

1. `assistant/AiMemory.kt`: `AiMemory`, `AiMemoryData` (instructions and memories), JSON encoding,
   `AiMemoryStore` (atomic file writes in `files/ai-memory/`), the prompt section, limits, and the
   checks for memory actions.
2. `AiActions.kt`: `SaveMemory`, `ForgetMemory` (from the model) and `RemoveMemory`,
   `RestoreMemory` (only for Undo); kind `MEMORY`; both ask after other text. `aiTools(memory)`.
3. Controller: loads and saves memory, adds instructions and memories to the system prompt,
   offers the memory tools only when memory is on for the chat, runs memory actions, the switch,
   edit, delete, delete all, the chat-without-memory flag (saved with the chat), and the what's-new
   flag (`ai_news_seen`).
4. UI: `AppAiActions` labels memory actions; chips get Manage; a Memory page and settings section;
   the welcome chip; the what's-new card; the intro line.
5. Guide, PRIVACY.md, AGENTS.md, backup rules, strings in 8 languages.

## Acceptance criteria

- [x] A reply with `save_memory` saves the memory at once, shows a chip with Undo and Manage, and
  the next question's system prompt lists it; Undo removes it (unit + UI test).
- [x] `replaces` and `forget_memory` change the numbered memory from the answer's list; Undo puts
  the old one back; a wrong number, a full list and a duplicate are refused or reported with a note
  to the model (unit tests).
- [x] After other text (a subtitle line, a file, reading the screen), a save waits for its button
  (unit test).
- [x] Use memory off, or a chat without memory: no memories in the prompt and no memory tools;
  instructions are still sent (unit test).
- [x] Memories and instructions survive a restart, and the chat's memory flag survives in history
  (unit test); `files/ai-memory/` is excluded from backups (`BackupRulesTest`).
- [ ] The Memory page lists, edits and deletes memories and edits instructions (UI test).
- [x] The what's-new card shows once for people who saw the intro before, and not for new users
  (unit test).
- [ ] All new text exists in the 8 interface languages; the chip, Memory page and card use the app
  theme (screenshot check).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: memory JSON and store, prompt section, tool parsing, checks, controller save/replace/forget/undo, switch, chat flag, news flag, backup rules | Local and CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local and CI |
| Managed-device/emulator | `AiAssistantPanelUiTest`: memory chip with Undo and Manage opens the Memory page; edit and delete there; screenshots `ai_panel_memory_chip`, `ai_panel_memory_page`, `ai_panel_whats_new` | CI managed device |
| Physical-device/manual | Preview APK: "I'm studying for JLPT N3 in December", then a new chat "what level am I?"; "forget my exam date"; a chat without memory | Owner |

## Risks / edge cases

- Models save too much or too little: the guide says what to save, the 30-item cap and the chip
  with Undo keep it visible, and the Memory page fixes mistakes.
- Prompt injection through subtitles or files: saves then wait for a tap, and the model is told to
  save only the user's own words.
- Memory and instructions make each request longer (at most about 7,500 characters); older chat
  messages are dropped first to stay within the request budget.
- Small models that cannot call tools never save memories; instructions and existing memories still
  reach them.

## Release intent

`release:patch` (no label), the default; the owner chose patch for the actions PR and gave no other
intent for this one.

## Implementation result

- `assistant/AiMemory.kt`: notes and instructions, limits (30 notes of at most 200 characters,
  1,500 characters of instructions), the prompt section, the checks (`aiMemoryRefusal`), the
  changes with their Undo (`changeAiMemory`), JSON encoding and `AiMemoryStore`
  (`files/ai-memory/memory.json`, written through a temporary file).
- `AiActions.kt`: `SaveMemory`, `ForgetMemory`, `RemoveMemory`, `RestoreMemory`, kind `MEMORY`,
  and `AI_MEMORY_TOOLS` (`save_memory`, `forget_memory`). `parseAiAction` takes the numbered list
  the model saw. Instead of the planned `aiTools(memory)`, the controller adds `AI_MEMORY_TOOLS` to
  `AI_TOOLS` while memory is on for the chat.
- `AiAssistantController`: loads and saves memory (one write at a time), appends
  `aiMemoryPrompt` to the system prompt, implements `AiMemoryKeeper` for the app's actions, and
  has the switch, instructions, edit, delete, delete all, the new-chat memory chip
  (`AiChat.memory`, saved with the chat) and `dismissNews`. The intro marks the news as seen, so
  new users never get the card. Settings gained `memoryEnabled` (`ai_memory_enabled`) and
  `newsSeen` (`ai_news_seen`, `AI_NEWS_VERSION = 1`).
- UI: `AppAiActions` labels memory actions and runs them through the controller; memory chips
  have Manage; `ui/AiAssistantMemory.kt` has the Memory page and settings section, the chat chip
  and the what's-new card; the intro has a memory line and its settings line now mentions Undo.
  The instructions box saves with a Save button instead of on every keystroke.
- Guide, PRIVACY.md, AGENTS.md, both backup rule files, and 34 new strings plus the updated intro
  line in all 8 languages.

## Validation result

- Local: `formatCheck`, `complexityCheck`, `testDebugUnitTest` (622 tests, 0 failures),
  `lintDebug` (no new warnings), `assembleDebug` and `assembleDebugAndroidTest` passed.
- Unit tests: `AiMemoryTest` (text, prompt, checks, save/update/forget/undo, JSON, store),
  `AiActionsTest` (parsing and numbers), `AiAnswerRunTest` (save at once, wait after a subtitle
  line, numbers from the answer's list), `AiAssistantControllerTest` (chip with Undo and the next
  question's prompt, update/forget/undo, full and duplicate, memory off and chat without memory,
  restart, what's-new), `AiChatHistoryTest`, `AiSettingsTest`, `AiAssistantPromptTest`,
  `BackupRulesTest`.
- `AiAssistantPanelUiTest` (memory chip, Manage, edit, instructions, Undo, delete, Delete all,
  switch, chat without memory, what's-new card and the three screenshots) runs on the CI managed
  device; the Memory page criterion and the screenshot check stay open until it passes there.
- Not verified: a live round trip with a real AI service (the owner's key from the chat may not
  be used in this session) and the on-phone scenarios in the validation plan.
