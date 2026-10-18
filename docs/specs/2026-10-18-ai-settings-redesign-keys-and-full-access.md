# AI settings redesign, several keys per service, and full access for the assistant

## Status

Approved for implementation. Kien asked in the project thread (2026-10-18), with a screenshot of
the current AI settings page, for three things: the AI service part becomes a list everyone can
choose from, the other parts move into sections like More settings, an option (off by default)
that gives the AI assistant full permission (keys, saved words, reset all settings…), and several
keys so the assistant switches to another key when one fails. Merge and release stay with the owner.

## Context / problem

The AI settings page ([AI assistant spec](2026-10-08-ai-assistant-panel.md)) is one long column:
seven radio rows for the service, then the key, memory, chat history and an Advanced toggle.
On a phone the service list alone fills the screen, and the rest has no grouping.

Each service keeps one key (`AiKeyStore`). Free keys run out (Gemini's free quota, OpenRouter's
daily free limit) and the assistant then stops until the user pastes a new key.

The assistant's actions ([actions spec](2026-10-08-ai-assistant-actions.md),
[settings spec](2026-10-16-translation-position-and-ai-settings.md)) never touch keys, the AI
settings, saved words or Reset all settings. Some users want to ask the assistant for those too.

## Goals

- **AI service** becomes one compact row showing the chosen service and what it costs. Tapping
  it opens a list of every service (name, cost note, a mark on services with a saved key);
  picking one closes the list. Other's address field shows under the row when Other is chosen.
- The rest of the page uses the expandable sections of More settings (icon, title, one-line
  summary, one section open at a time): **API keys**, **Model**, **Memory**, **Chat history**
  and **Permissions**. The AI assistant switch stays on top.
- **Several keys per service** (at most 10). The keys section lists each saved key by its last
  four characters with its state (In use, Works, Not checked, or why it failed) and a Remove
  button, then Add another key (Get a key, Paste key, or type it), and Test all keys.
- **Automatic fallback.** A request tries the key that worked last first. When a key is
  rejected, has no credit left, or hits its rate limit, the same request goes to the next key at
  once; the key that answers becomes the one in use. Only when every key fails does the chat
  show the error. A rejected key loses its check; the chat stays usable while another key works.
- **Full access** (Permissions → "Full access for the assistant"), off by default, with a
  warning. When on, the assistant also gets these actions:
  - `change_ai_setting`: service, model, thinking level, Use memory, chat history retention.
    Runs at once with Undo. A model is tried with one tiny request first, like the model menu.
  - `add_api_key`: saves a key the user typed in this chat (exact text from their own message).
    Runs at once with Undo.
  - `remove_api_key`: removes a saved key by service and last four characters. Always waits for
    a tap (a removed key can't come back).
  - `find_saved_words`, `delete_saved_word`, `edit_saved_word`: list, delete or change the
    meaning or reading of saved words. Delete and edit run at once with Undo.
  - `reset`: Reset all settings or Reset subtitle positions. Always waits for a tap.
  - The navigation, translation and reload/download/interface-language changes that ask today
    (`AiAction.alwaysAsks`) run at once with Undo where they have one.
- The prompt-injection rule stays even with full access: after subtitles, files, error text or
  saved words were read in an answer, every change waits for a tap.

## Non-goals

- Keys don't move between services: fallback stays within the chosen service, because each
  service has its own model and address.
- Full access can't change itself, the AI assistant switch, language downloads (deleting
  models), or memory outside `save_memory`/`forget_memory`.
- No reordering of keys; the list order is the order they were added.

## User-visible behavior

Before: seven radio rows, then one key, memory, history, Advanced, all open in one column.

After: the AI assistant switch, the AI service row ("Google Gemini · Free key with a Google
account ▾"), then five closed section cards. Opening API keys shows, for example:

- •••• fpzA · In use · Remove
- •••• 9xQe · Works · Remove
- Add another key: Get a free key · Paste key · typed key field + Save key
- Test all keys
- "When a key fails or runs out, the assistant switches to the next one."

Permissions shows the Full access switch (off) with: "Lets the assistant change its own AI
settings, add or remove keys, change or delete saved words, and reset settings. Removing a key
and resetting still wait for your tap."

## Technical constraints / invariants

- Keys stay AES-GCM encrypted with the Keystore key in `ai_assistant_keys` preferences
  (`BackupRulesTest`). The first key keeps today's preference names (`key_<service>`,
  `hint_<service>`, `checked_<service>`), so existing keys keep working with no migration; more
  keys use `key_<service>.<id>` names and `keys_<service>` holds the order.
- A key never appears in the state, chat records or the model's notes; only its last four
  characters do. `add_api_key` notes say "a key ending in xxxx".
- A tool call is only run when its tool was offered with that question, so full-access calls
  are refused while full access is off.
- All new text in `strings_ai.xml` with all 8 interface languages.

## Proposed approach / plan

1. `AiKeyStore`: list, add (duplicates return the existing id), load, check marks and remove per
   key id; unit tests including the old single-key layout.
2. `AiAssistantController`: `keys` per service in the state, fallback in `request`, per-key test
   results, `fullAccess` setting, and the full-access operations the app actions call.
3. `AiActions`/`AiAnswerRun`: new actions, tools offered only with full access, the
   offered-tools check, and the ask rules above; unit tests.
4. `AppAiActions`: perform the new actions with Undo.
5. UI: service row with list, expandable sections, key list. Strings in 8 languages.
6. Docs: AGENTS.md AI invariant, this spec, the settings spec's non-goal note.

## Acceptance criteria

- [x] With two Gemini keys where the first answers 401 or 429, a question is answered with the
      second key, which shows In use; no error shows (`AiAssistantControllerTest`).
- [x] With every key failing, the chat shows the last key's error (`AiAssistantControllerTest`).
- [x] An existing single key saved by v1.6.x still loads and stays checked after the update
      (`AiKeyStoreTest`).
- [x] Full access off: the full-access tools are not offered and a call to one is refused
      (`AiAnswerRunTest`).
- [x] Full access on: `change_ai_setting`, `delete_saved_word` and `edit_saved_word` run at once
      with Undo; `remove_api_key` and `reset` wait for a tap; after `look_at_video` every change
      waits for a tap (`AiAnswerRunTest`, `AiActionsTest`).
- [x] `add_api_key` is refused unless the key appears in the user's own message.
- [ ] The settings page shows the service row and the five sections on the dark and Light
      themes (`AiAssistantPanelUiTest` screenshots).
- [x] CI parity command passes.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` with the new key store, controller, answer run and action tests | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| Managed-device/emulator | `AiAssistantPanelUiTest` settings page checks and screenshots | PR CI (`managed-device-tests`) |
| Physical-device/manual | Kien adds two keys, removes one; tries full access by asking to delete a saved word and to reset settings | Owner's phone with the PR preview APK |
| Live YouTube | Not applicable: no change to the YouTube page | — |

## Risks / edge cases

- A user who types a key in the chat sends it to the AI service of that chat; the tool
  description and the Permissions text say keys are best added in settings.
- Testing all keys sends one tiny request per key.
- Rate limits shared by keys of the same account: fallback then fails on every key, and the
  normal error shows.

## Release intent

`release:patch` (the default rule for app changes). This adds user-visible features, so
`release:minor` would also fit; that is the owner's call and is raised in the PR.

## Implementation result

- `assistant/AiKeyStore.kt`: several keys per service (at most 10). The first key keeps the old
  preference names (id `0`); later keys use `key_<service>.<id>`, and `keys_<service>` holds the
  order. Adding a key that is already saved returns the existing id.
- `assistant/AiAssistantController.kt`: `withEachKey` tries the key in use first, then the
  others, on no key, unreadable key, rejected key, no credit and rate limit; only the last key
  waits out a rate limit. A rejected key loses its check; the key that answers becomes the one in
  use and is marked checked. Test all keys tests each key and moves "in use" to a working key.
  Model lists use the same fallback.
- `assistant/AiFullAccess.kt` and `ui/AiAssistantFullAccess.kt`: the seven full-access tools,
  the prompt section (AI settings and key endings, never keys) and how each is performed with
  Undo. `AiSettings.fullAccess` (`ai_full_access`, off by default).
- `assistant/AiActions.kt` / `AiAnswerRun.kt`: `aiActionAsks` decides when a tap is needed; a
  call to a tool that wasn't offered is refused; `add_api_key` is refused unless the key is in
  one of the user's messages; `find_saved_words` counts as other text.
- `ui/AiAssistantSettings.kt`: service row with a drop-down list (Kien's "list"; the first-time
  setup card in the chat keeps its radio list), then API keys, Model, Memory, Chat history and
  Permissions cards built on `SettingsExpandableCard`, extracted from `SubtitleSettingsDialog`.
- Strings in all 8 interface languages; AGENTS.md, the assistant guide asset, tech-stack and the
  settings spec's non-goal updated.

Deviation: none from the plan. Search, open video, translation and the `asks` settings run at
once with full access (they have no Undo where they had none before).

## Validation result

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed in the cloud container |
| `AiKeyStoreTest`, `AiAssistantControllerTest`, `AiAnswerRunTest`, `AiActionsTest` (fallback, legacy key, offered-tools check, ask rules, `add_api_key` refusal) | Passed |
| `AiAssistantPanelUiTest` settings page and screenshots, dark and Light | Runs in PR CI (`managed-device-tests`); no emulator in the container |
| Physical device | Unverified; owner tests the preview APK |

Found on the way: `SETTING_CHOICE_ALIASES` in `AiActions.kt` was built eagerly while `AiSetting`
reads that file's lists, so when `AiSetting` loaded first the map's keys were null and "white"
stopped meaning Light. It is now lazy.
