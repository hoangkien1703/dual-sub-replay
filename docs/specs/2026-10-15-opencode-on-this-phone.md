# OpenCode on this phone (through Termux)

## Status

Implemented. On 2026-10-15 the owner asked for the AI assistant to use OpenCode through the OpenCode
CLI rather than OpenCode's paid API, and asked to discuss first. After the options below they picked
"Termux + auto-start" on the decision card. This does not authorize merging or publishing.

## Context / problem

- The assistant already offers OpenCode Zen and OpenCode Go (`assistant/AiProvider.kt`), but a Zen
  key needs billing details, even for its free models.
- The OpenCode CLI uses OpenCode's free models without any key. The owner runs OpenCode 2.0.29 in
  Termux on their phone with "DeepSeek V4.1 Flash Free".
- What OpenCode accepts, tested on 2026-10-15 with `@opencode/cli` 2.0.29 (linux-x64) in a cloud
  container:
  - A request to `https://opencode.ai/zen/v1/chat/completions` with the CLI's anonymous key is
    refused: HTTP 403 `FreeTierError` "OpenCode's free tier can only be used from within OpenCode."
  - The same refusal comes back for `POST /api/experimental/generate` and for
    `POST /api/session` + `/prompt` sent straight to `opencode serve`, and for `opencode run` with a
    custom agent, or with the Plan agent's permissions set to `deny`.
  - `opencode run --agent plan --format json -m opencode/<model>` answers, with the message on
    stdin. With `{"agent":{"plan":{"permission":"ask"}}}` in the working folder's `opencode.json`,
    every tool call (shell, read, glob, webfetch) is answered "This non-interactive run cannot ask
    the user for permission, so the request was rejected", and nothing runs.
  - OpenCode 2.0's `run` talks to a background server it starts itself, so later runs skip the cold
    start (about 2 to 15 seconds per answer in the container, depending on the model).
- We do not imitate the CLI or change its requests to get past that refusal.

## Goals

- A new service, "OpenCode on this phone", that needs no key and uses the free models of the
  OpenCode CLI the user installed in Termux.
- The app starts the work in Termux by itself (Termux's `RUN_COMMAND` intent); the user never has
  to open Termux after the one-time setup.
- Chats, memory, instructions, text files and the app's actions work as with other services.

## Non-goals

- Shipping OpenCode or Termux inside the app, or installing either.
- Calling OpenCode's servers directly, or `opencode serve` over HTTP.
- Pictures, PDFs and the Thinking menu for this service (the CLI cannot take an app's files, and
  model variants differ per model).
- OpenCode 1.x support beyond what happens to work; the owner runs 2.0.29.

## User-visible behavior

- The service picker lists "OpenCode on this phone — Free models through OpenCode in Termux, no key".
- Choosing it shows setup instead of key buttons:
  1. Install Termux and OpenCode, and check that `opencode` starts in Termux.
  2. Run one command in Termux, with a Copy button:
     `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`
  3. Allow DualSub Replay to run commands in Termux (an Allow button asks Android).
  4. Connect: the app asks OpenCode for one word; when that answers, the chat opens.
- Each question runs `opencode run` in Termux in the background (`~/.dualsub-replay`), with the
  app's instructions, the chat so far, the action list and the question on stdin. The answer shows
  as usual. Actions are asked for with `<action>{"name": …, "arguments": {…}}</action>` lines,
  which the app removes from the text and runs under the usual rules (Undo chips, tap-to-confirm).
- The model button lists `opencode models` (the user's connected providers included); OpenCode's
  free ones are marked free. Default: `opencode/deepseek-v4.1-flash-free`.
- Errors say what to fix: Termux not installed, permission not allowed, Termux not allowing other
  apps yet, OpenCode not found in Termux, OpenCode's free-model limit, a refused free-tier request
  (OpenCode's own message in the details), or no answer in time. Pictures and PDFs show the existing
  "this model can't read pictures or files" message.

## Technical constraints / invariants

- Assistant invariants in [AGENTS.md](../../AGENTS.md): no project server or shared key; the
  assistant acts only through the fixed list in `assistant/AiActions.kt`; subtitles, files and
  error text stay quoted data and never trigger memory changes.
- OpenCode's own tools must never run for the app. The app rewrites `~/.dualsub-replay/opencode.json`
  with `"permission": "ask"` for the Plan agent before every run, and `opencode run` refuses every
  "ask" because it cannot ask anyone.
- The prompt travels on stdin (Termux `RUN_COMMAND_STDIN`, Termux 0.118 or newer) and the model
  name as an argument to a fixed script, so no text from the chat is ever parsed by the shell.
- Termux returns at most 100 KB of stdout; the app reads the JSON event lines it gets.
- The result comes back through a mutable `PendingIntent` to a non-exported receiver registered for
  one request, with an explicit package.
- Interface text in `strings_ai.xml` for every language (`AppLanguageTest`, lint `MissingTranslation`).

## Proposed approach / plan

1. `AiProvider.OPENCODE_PHONE` (`needsKey = false`, `AiWireFormat.OPENCODE_TERMUX`); the controller
   treats a no-key service as having a key and skips the HTTPS address check for it.
2. `assistant/AiOpenCodeTermux.kt`: the stdin prompt (instructions, actions, transcript), parsing of
   `--format json` events and `<action>` lines, error mapping, the model list, the fixed Termux
   scripts, and `AiOpenCodeTransport` (chat and model list through a `TermuxRunner`).
3. `assistant/TermuxCommandRunner.kt`: the Android `RUN_COMMAND` intent with stdin, background mode,
   a per-request result receiver and a timeout.
4. `AiRoutingTransport` sends OpenCode-on-this-phone requests to the Termux transport and the rest
   to `AiHttpTransport`.
5. Manifest: `com.termux.permission.RUN_COMMAND` and package visibility for `com.termux`.
6. UI: setup steps with Copy command, Allow and Connect in the setup card and AI settings; new
   error messages; strings in all eight languages. Guide, README and tech stack mention the service.

## Acceptance criteria

- [x] The stdin prompt carries the instructions, the action list (only when actions are offered),
  the whole chat including earlier actions and their results, and tells the model not to use tools
  (`AiOpenCodeTermuxTest`).
- [x] Text and `<action>` lines are read from `opencode run --format json` output; the lines are
  removed from the shown text; broken action JSON is ignored (`AiOpenCodeTermuxTest`).
- [x] OpenCode's error events map to rate limit, unknown model and refused request, keeping
  OpenCode's message as the detail; exit codes 90 and 91 and Termux's own errors map to their
  setup messages (`AiOpenCodeTermuxTest`).
- [x] Questions with pictures or PDFs fail with the unsupported-attachment message before Termux is
  asked (`AiOpenCodeTermuxTest`).
- [x] The model list reads `provider/model` lines and marks OpenCode's free models
  (`AiOpenCodeTermuxTest`).
- [x] Choosing OpenCode on this phone needs no key: Connect checks it through the transport, and
  the chat opens after it passes (`AiAssistantControllerTest`).
- [x] The setup card shows the OpenCode steps, the command and the buttons (`AiAssistantPanelUiTest`).
- [x] Other services are unchanged (existing unit and UI tests).
- [ ] On a phone with Termux and OpenCode 2.0.29, Connect passes, a question gets an answer, and
  "pause the video" pauses it (owner, preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including `AiOpenCodeTermuxTest` | Cloud container, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Cloud container, CI |
| Managed-device/emulator | `AiAssistantPanelUiTest` passes on `pixel2Api36` | CI `managed-device-tests` |
| Physical-device/manual | Setup steps, Connect, a question, an action, with Termux + OpenCode 2.0.29 | Owner, preview APK |
| CLI behavior | `opencode run --agent plan` with the app's `opencode.json`: answers, tools refused | Cloud container, OpenCode 2.0.29 linux-x64 (done 2026-10-15) |
| Live YouTube | Not applicable | — |

## Risks / edge cases

- OpenCode may tighten its free tier again; the refusal then shows with OpenCode's own message.
- Free models follow the `<action>` format less reliably than native tool calls; a missed action
  just leaves a text answer.
- The Plan agent's coding-assistant system prompt shapes the tone; the app's instructions come
  first in the message.
- OpenCode's install script puts `opencode` in `~/.opencode/bin` and adds it to PATH only in
  `~/.bashrc`, which a background login shell never reads. The owner's first phone test (2026-10-15,
  build 405) failed with "OpenCode wasn't found" for this reason, so the script now adds
  `~/.opencode/bin`, `~/.bun/bin`, `~/.local/bin` and `~/bin` to PATH.
- OpenCode's Linux build needs glibc and does not run in plain Termux, so people (the owner
  included) run it in a proot-distro Linux behind `alias opencode="proot-distro login debian --
  opencode"` in `~/.bashrc`. The script reads that alias and runs
  `proot-distro login <distro> --work-dir ~/.dualsub-replay -- opencode …`; proot-distro shares
  Termux's home at the same path, so the folder's `opencode.json` still keeps the tools on "ask".
  proot-distro passes its command on as one line, so the app accepts only `provider/model` names
  made of letters, digits and `._:@+-/`.
- OpenCode 1.x refuses `"permission":"ask"` ("Expected PermissionActionConfig"); version 2 is needed.
- Fresh users need Termux, a proot-distro Linux and OpenCode in it before the app's own steps; the
  app cannot install those. Gemini stays the default for everyone else.
- Termux older than 0.118 ignores stdin; `opencode run` then gets no message and fails.
- Some phones stop Termux in the background; Termux's own notification keeps it alive while running.

## Release intent

`release:patch` — the standing default; the owner did not ask for a minor.

## Implementation result

As planned. `AiProvider.OPENCODE_PHONE` (`needsKey = false`), `AiOpenCodeTermux.kt` (prompt, reply
and error parsing, scripts, `AiOpenCodeTransport`, `AiRoutingTransport`), `TermuxCommandRunner.kt`,
`ui/AiOpenCodeSetup.kt`, five new `AiErrorKind`s with messages, the manifest permission and package
visibility, strings in all eight languages, and the guide, README, tech stack and AGENTS.md.
`AiKeyStore.checkedSetup` keeps a passed check for a service without a key. The Thinking menu is
hidden for this service.

After the first phone test, the owner asked for a guide that the OpenCode agent in Termux can read
and follow: [docs/opencode-termux-setup.md](../opencode-termux-setup.md) checks the Termux version,
where `opencode` is, `allow-external-apps`, and runs exactly what the app runs.

Deviation from the first discussion: the app does not call `opencode serve` over HTTP, because
OpenCode refuses its free models there (see Context).

## Validation result

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed in the cloud container, 2026-10-15 |
| `AiOpenCodeTermuxTest`, new `AiAssistantControllerTest` cases | Passed |
| CLI behavior with OpenCode 2.0.29 (linux-x64) | Done: Plan agent answered with the free models; with the app's `opencode.json`, shell, read, glob and webfetch calls were refused and a canary file was never read |
| `AiAssistantPanelUiTest.openCodeOnThisPhoneShowsTermuxStepsAndConnectsWithoutAKey` | Not run locally (no emulator); runs in CI `managed-device-tests` |
| Phone with Termux and OpenCode 2.0.29 | Build 405 (owner, 2026-10-15): Allow worked and Termux ran the script and returned its result. It first exited with "OpenCode wasn't found" (`~/.opencode/bin`, alias to proot-distro Debian); with a link, "cannot execute: required file not found" (glibc build). The owner's OpenCode agent also upgraded OpenCode 1.17.9 to 2.0.29. Fixed by reading the proot-distro alias; retest pending |
