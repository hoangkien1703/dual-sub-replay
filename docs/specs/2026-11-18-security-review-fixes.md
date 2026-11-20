# Security review fixes (2026-11-18)

## Status

Approved for implementation (owner pick 2026-11-18: "A and B, as two PRs"). Part B (app) shipped
in PR #246 (merged 2026-11-18 after the owner tested sign-in on a phone); part A (CI and signing)
is in its own PR, below. Merge and release stay the owner's call.

## Context / problem

A defensive code review of `main` at f8bbffe found no critical or high-severity issue, and six
findings ranked medium to info. The full report is in the project's security review document.
Two app findings are fixed here:

- **3 (medium-low).** `AiAnswerRun` decided whether the model had read text the user did not type
  (`otherText`) from the newest message only. Earlier messages of the chat are sent again with each
  question, with their subtitle lines, files, web sources and the notes of actions that read the
  video, the screen, a search or the saved words. An instruction hidden in an earlier turn's
  subtitles could therefore change a setting or save a memory without a tap on a later plain
  question ("thanks").
- **5 (low).** While Google sign-in runs, every HTTPS page stayed inside the WebView, which shows no
  address, so a link from a Google page could open a page the user cannot check.

Part A (findings 1, 2, 4) is about CI: the preview signing key in the Actions cache is readable by
PR code, the production key shares its job with the Gradle build, and the outside-PR flags miss
test code.

## Goals

- Without full access, a setting change or memory save waits for a tap whenever any message that
  goes with the request carried other text.
- During sign-in, only Google's own hosts stay inside the app.

## Non-goals

- No change to full access (owner decision 2026-10-21: most changes run at once even after other
  text).
- No change to which pages open inside the app outside sign-in.

## User-visible behavior

- AI assistant: in a chat that earlier read a subtitle line, a file, a web search, a YouTube search,
  the screen or the saved words, later setting and memory changes show their button instead of
  running at once, until that message no longer fits in the request (16 messages or 24,000
  characters).
- Sign-in: a non-Google page opened during sign-in goes to the browser, like at any other time.

## Technical constraints / invariants

- [AI](../project/tech-stack.md#ai-features): the assistant acts only through the fixed tools;
  other text is quoted data.
- [Player and YouTube page](../project/tech-stack.md#player-and-youtube-page): main-frame
  navigation goes through `classifyMainFrameUrl`.

## Proposed approach / plan

1. `aiRequestHistory` returns the messages `buildAiRequestMessages` sends; `buildAiRequestMessages`
   uses it. `AiChatMessage.carriesOtherText()` is true for context, attachments, sources, or a done
   action that read something (look, screen, web, a YouTube search's found videos, find saved
   words). `AiAnswerRun.run` sets `otherText` from the sent history.
2. `shouldOpenInsideApp(url, destination, signInEmbeddingActive)` keeps an `OPEN_EXTERNAL` page
   inside during sign-in only when `isGoogleSignInStepUrl` (Google country domains, gstatic,
   googleusercontent, googleapis, withgoogle).

## Acceptance criteria

- [x] A two-turn chat whose first question carried a subtitle line makes a setting change and a
  memory save wait (`AiAnswerRunTest.textFromAnEarlierQuestionOfTheChatStillMakesChangesWait`).
- [x] An earlier answer with web sources counts; a typed-only chat still runs changes at once
  (same test).
- [x] A message that no longer goes with the request no longer counts
  (`AiConversationTest.theRequestHistoryIsWhatGoesWithTheRequest`).
- [x] During sign-in, Google hosts stay inside and other pages go out
  (`PlaybackArchitectureTest.activeSignInKeepsGoogleHostsEmbeddedAndSendsOtherPagesToTheBrowser`).
- [ ] Google sign-in still works on a real phone with the preview build.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | PR CI |
| Managed-device/emulator | Existing device tests pass | PR CI |
| Physical-device/manual | Sign in to YouTube in the preview app; ask the AI about a line, then "thanks, make the text bigger" and see a button | Owner's phone |

## Risks / edge cases

- A Google Workspace account with its own company sign-in page now finishes in the browser and may
  not sign in inside the app. Rare for this app's users.
- Long chats that read a subtitle line early ask for taps a little longer than before.

## Release intent

`release:patch`: the APK changes.

## Implementation result

As planned. `FOUND_VIDEOS_NOTE` moved to `AiConversation.kt` and is shared with `AiAnswerRun`.
`docs/project/tech-stack.md` now also states the 2026-10-21 full-access rule correctly.

## Validation result

Unit tests and format/complexity checks: see the PR. Real-phone sign-in: not run yet (needs the
owner's phone).

## Part A: CI and signing

### Problem

- **1 (medium).** `android.yml` restored the preview signing key (`~/.android/debug.keystore`) from
  the Actions cache `dual-sub-replay-preview-signing-v1`. Main's runs save that cache, and every PR
  run, approved fork runs too, may read the default branch's caches. A PR's unit test could send
  the key out. With it, an APK installs over any preview build and keeps its YouTube sign-in and AI
  keys. Fork builds were also published to the official `preview` release automatically.
- **2 (medium).** `publish-main` handed the production keystore and passwords to the whole Gradle
  build: every plugin, build script and downloaded library ran next to the key, with no dependency
  verification.
- **4 (low).** `tools/pr_review_flags.py` did not count test and benchmark code as code that runs
  on CI.

### Approach

1. `verify-build` restores no key: its debug APK keeps the runner's throwaway debug key. Fork PRs
   build the package `com.kienhoang.dualsubreplay.forkpreview` (`.forkpreview`), which has no
   update channel and never installs over a `.preview` app.
2. `publish-pr-preview` (runs main's file on `workflow_run`) uses the `preview` environment
   (deployment branches: `main`). It checks the package with `aapt`, then re-signs own-branch
   previews with `apksigner` and `PREVIEW_KEYSTORE_BASE64` / `PREVIEW_KEYSTORE_PASSWORD` /
   `PREVIEW_KEY_ALIAS`. Fork PRs publish only with the owner's `publish-preview` label (then re-run
   their CI), keeping their throwaway key.
3. `release-on-main.yml`: `build-release` builds `app-release-unsigned.apk` with `contents: read`
   and no secrets and uploads it as a one-day artifact. `publish-main` (environment `release`)
   downloads it, runs `zipalign -f -p 4` and `apksigner sign` with the production key, then keeps
   every existing check (pinned certificate f664…116c, package, version) and step.
4. `RUNS_CODE` gains `app/src/test/*`, `app/src/androidTest/*`, `benchmark/*`, and the comment says
   approving runs all of the PR's code.

### Owner setup before merge

1. On the computer: `keytool -genkeypair -keystore preview.jks -alias preview -keyalg RSA -keysize
   3072 -validity 10000 -dname "CN=DualSub Replay preview"` (pick a password), then base64 the file
   (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("preview.jks"))`).
2. Settings → Environments → New environment `preview`; Deployment branches: selected, `main`.
3. Add the secrets `PREVIEW_KEYSTORE_BASE64`, `PREVIEW_KEYSTORE_PASSWORD`, `PREVIEW_KEY_ALIAS`
   (`preview`) to that environment.
4. Afterwards testers uninstall the old preview app once: the new key cannot update it.

### Acceptance criteria

- [x] `zipalign` + `apksigner` on a real APK give a v2 + v3 signature with one signer, aligned;
  re-signing a debug-signed APK replaces its signature (dry run in the cloud container, 2026-11-18).
- [x] `actionlint` (with shellcheck) reports nothing new on the three workflows; `tools/tests` pass.
- [ ] The first own-branch PR after merge publishes a preview signed with the new key.
- [ ] The next release publishes through `build-release` + `publish-main` and passes the pinned
  certificate check.

### Release intent

`release:skip`: workflows, tools and docs only.
