# Ready for many more users: update reminders, remote switches, Google backoff, problem reports

## Status

Implemented (owner request 2026-10-30 in the "App scaling" chat: "help me fix it and do
everything you recommended", with the answers below); waiting for CI and the owner's phone test.

Owner answers (2026-10-30): improved update-reminder design; remote config limited to
**switches only**; problem reports **without a server**; everything in **one PR** (the owner's
explicit choice over AGENTS.md's one-change-per-PR rule). The owner keeps ad skipping and accepts
that risk; it stays as it is, and the remote switches can turn it off without a new APK.

## Context / problem

A review of what would break at a million users found no server to overload (there is none), but:

1. **Slow fixes.** YouTube and Google change often. Update automatically is off by default and the
   app never says a new version exists, so after a breakage most people stay on the broken
   version for weeks. A security fix reaches nobody until they look.
2. **Shared IP addresses.** Mobile carriers put thousands of phones behind one address. GitHub's
   API allows 60 requests an hour per address, and Google Translate answers 429 when one address
   asks too much; the background translation of minutes 2 to 5 kept its 3 s pace even then.
3. **No fix without an APK.** When one Google address or the page token stops working, only a
   new release can switch it off.
4. **No signal.** There is no telemetry (by design), so the owner hears about failures only when
   someone writes, and their report rarely says which step failed.

## Goals

- **Update reminders, on by default** (Settings → App updates → Tell me about new versions):
  - Once a day when the app opens it reads one small file from the latest GitHub release
    (`update-info.json`, served from GitHub's download hosts, not the rate-limited API).
  - A new version shows a small banner, at most once a week whichever version it names, so daily
    releases never bring a daily banner, and none in the week after the app was installed or updated
    however that happened (Android's `lastUpdateTime`; owner questions 2026-10-30); **Skip this version** hides
    that version for good, and a newer one still waits for the week.
  - A **security update** (a release whose PR had the `security:critical` label) shows a dialog at
    once; after Not now it returns daily for that fix's first 3 days, then weekly, and only a newer
    security fix starts again (owner decision 2026-10-30: no permanent skip, but no daily nagging
    forever). A small **What was fixed** link opens the release page, whose notes the release
    automation starts with a security note (owner request 2026-10-30). It is based on a *minimum safe version*: someone
    two releases behind the security fix still gets it after a later ordinary release. People
    can always decline.
  - Never shown over a playing video: a reminder found while a video plays waits for pause.
  - Check for updates in the menu reads the same file first and the API only as a fallback.
- **Remote switches** (`remote-config/switches.json` on `main`, fetched with the daily check):
  they only choose among behaviour already in the app, so a changed file can at worst turn
  things off. Switches: Google Translate address order (and leaving addresses out), a slower
  background translation pace, Skip ads off, Skip sponsors off, page-token captions off, and one
  of a fixed list of notices (text in the app's strings). Rules can target app version ranges.
- **Google backoff**: when Google refuses (403/429) background requests slow down
  (3 s doubling to 60 s) and a failing background step rests 2, 4, 8 then 16 minutes instead of
  always 2; a clean answer resets both. The playing line is never delayed by this.
- **Problem log and report**: the app keeps the last 50 problems (which step failed, HTTP status
  and Google address; never video IDs, titles, subtitle or translated text) in its private cache.
  Menu → **Report a problem** shows exactly what would be sent and opens a prefilled GitHub
  issue the person reviews and submits themselves. Nothing is sent automatically.

## Non-goals

- A project server, analytics, crash SDK or any automatic upload.
- Remote scripts, selectors or endpoints not already in the app (the owner chose switches only).
- Forcing an update: every reminder can be declined.
- Removing or changing ad skipping (owner decision 2026-10-30).

## User-visible behavior

- Settings → App updates gets **Tell me about new versions** (on by default) above Update
  automatically. Off: the app reads nothing from GitHub unless Check for updates is tapped (and
  so gets no remote switches either); a note under the switch says security updates go
  unannounced too. The app never checks behind the user's back when it is off.
- Banner (bottom of the screen, app theme): "Version X is available" — Update, Not now, Skip
  this version. Update opens the existing update dialog.
- Security dialog: "Security update" — "Version X fixes a security problem in the version you
  have. Update now?" — Update, Not now.
- Notice banner (from a remote switch), for example "YouTube changed something and captions may
  not load. A fix is on the way." — Close (shown again only for a different notice).
- Menu → Report a problem: a dialog listing the app version, Android version, phone model and
  recent problems, with Open GitHub and Copy.

## Technical constraints / invariants

- Hosts: `github.com` and GitHub's release-asset hosts (already allowed for updates) and
  `raw.githubusercontent.com` for the switches only; HTTPS, size-capped (64 KiB), no cookies, no
  identifiers. The update APK checks (SHA-256, package, newer version code) are unchanged.
- No new permission. User-visible text in `strings_updates.xml` and `strings_status.xml` in all
  8 interface languages; diagnostic text stays English.
- The new setting is on the settings page, so the AI assistant reads and changes it
  (`update_reminders`, asks first); Reset all settings turns it back on.
- Release automation: `security:critical` is a separate label from the `release:*` labels. The
  reservation metadata records it; every release from then on publishes `update-info.json`
  with the highest version code of any security release as `minimum_safe_version_code`, and
  publishing requires that file. Old reservations without the field count as not critical.

## Proposed approach / plan

1. `data/AppUpdates.kt`: `UpdateInfo` parsing, `AppUpdate.critical`; `data/UpdateReminders.kt`:
   the reminder rules as pure functions. `update/AppUpdater.kt`: daily check from `onAppStarted`,
   pending reminder state, Not now / Skip memory. `ui/AppUpdateUi.kt` + `ui/AppNotices.kt`:
   banner, security dialog, notice banner, setting.
2. `tools/automatic_release.py`: `security` in metadata, `minimum_safe_version_code` output, and
   `update-info.json` required for publication; `release-on-main.yml` writes and uploads it and
   creates the `security:critical` label; Python tests.
3. `data/RemoteSwitches.kt` (parse, version rules, clamp) + `update/RemoteSwitchFetcher.kt`
   (fetch, cache 30 days); wire into `GoogleWebTranslator`, `prefetchInBackground`, ad skip
   and page-token mode.
4. `GoogleWebTranslator` refusal streak + `backgroundPrefetchPauseMs` / `backgroundRestMs`.
5. `data/ProblemLog.kt` (record, cap, format, issue URL) and the Report a problem dialog; record
   Google, caption and update failures.
6. Split the largest file, `AppViewModel.kt`, without changing behaviour.
7. Docs: AGENTS.md, tech-stack, PRIVACY, releasing, the earlier update spec's non-goal.

## Acceptance criteria

- [ ] Reminder rules (unit tests): a banner shows at most once in 7 days, even when a newer
      version came out meanwhile, and not within 7 days of the app's own install or update; Skip hides that version; below the
      minimum safe version a security dialog shows, again after 1 day during the fix's first 3
      days, then weekly, and at once for a newer fix; nothing shows while a video plays; a clock moved back does not block.
- [ ] `update-info.json` parsing ignores files with another schema, missing fields or bad SHA-256.
- [ ] Release tool (Python tests): `security:critical` sets `security`; the minimum safe code is
      the highest security version code including the new release; publish refuses a release
      without `update-info.json`.
- [ ] Remote switches (unit tests): unknown schema → defaults; version rules apply in order;
      unknown addresses and out-of-range paces are ignored or clamped; unknown notices dropped.
- [ ] Backoff (unit tests): pause doubles per refusal up to 60 s and resets; rest doubles to 16 min.
- [ ] Problem log (unit tests): keeps 50, merges repeats, the issue URL stays under 6,000
      characters and contains no text other than the recorded codes.
- [ ] Existing update, ad-skip and translation tests still pass; CI parity command passes.
- [ ] Owner: a preview build shows the banner for a newer build of the same PR.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. new `UpdateRemindersTest`, `RemoteSwitchesTest`, `ProblemLogTest`, backoff tests | Local + CI |
| Release tools | `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v` | Local + CI |
| Android lint/build | CI parity command from AGENTS.md | Local + CI |
| Managed-device/emulator | Existing `AppUpdateUiTest` plus banner/report dialog tests | CI |
| Physical-device/manual | Preview build N shows the banner for build N+1; Report a problem opens GitHub | Owner's phone |
| Live YouTube | Not applicable (no caption or page change) | — |

## Risks / edge cases

- `releases/latest/download/` follows GitHub's "Latest" release; an older retry never becomes
  Latest (existing rule), so the file always belongs to the newest version.
- The first release with this change is the first with `update-info.json`; until then the app
  falls back to the API, as before.
- A wrong switch file could turn features off for everyone until fixed; it cannot add code, and a
  file that fails to parse changes nothing. Cached switches expire after 30 days.
- Daily reads from the same carrier address reach GitHub's CDN, not the API.

## Release intent

`release:patch` (default; changes the APK). `release:minor` would also fit the size of this change;
it is the owner's call and not applied without asking.

## Implementation result

- Update reminders: `data/AppUpdates.kt` (`UPDATE_INFO_URL`, `UpdateInfo`, `parseUpdateInfo`,
  `updateFromInfo`, `AppUpdate.critical`), `data/UpdateReminders.kt` (rules),
  `update/AppUpdater.kt` (daily check with reminders or Update automatically, `update-info.json`
  first then the API, reminder state, Not now / Skip / Update), `ui/AppNotices.kt` (banner,
  Security update dialog, notice banner, Report a problem dialog) placed over the main screen in
  `AppNavigation.kt`, and the Tell me about new versions switch in `AppUpdateUi.kt`. With Update
  automatically on, an ordinary update installs itself without a banner; a security update still
  shows the dialog.
- Release automation: `tools/automatic_release.py` (`SECURITY_LABEL`, `security` in the reservation
  metadata, `minimum_safe_version_code`, `update-info` action, `update-info.json` required to
  publish) and `release-on-main.yml` (creates the `security:critical` label, writes and uploads
  the file). Only published security releases (plus the one being made) raise the minimum, so an
  unpublished draft can never ask apps for a version they cannot get.
- Remote switches: `data/RemoteSwitches.kt`, `update/RemoteSwitchFetcher.kt` (own preferences
  file, so Reset all settings keeps them), `remote-config/switches.json` (no rules) and its README.
  Wired into `GoogleWebTranslator` (`addressNames`), the background pace in `AppViewModel`,
  `AdSkipSettings.withRemote` in `AdSkipPageScripts`, and the page-token mode in
  `PageTranscriptSource`.
- Backoff: `nextRefusalStreak` in `GoogleWebTranslator`, `backgroundPrefetchPauseMs` and
  `backgroundRestMs` in `PlaybackTranslation.kt`; `prefetchInBackground` takes `pauseMs` as a
  function so each step reads the current pace.
- Problem log: `data/ProblemLog.kt`, initialised in `MainActivity`; records Google failures (and
  answers that came only after a 403/429), caption failures (page step and exception class name
  only), update failures and switch-download failures. Menu → Report a problem in `DrawerFooter`.
- Split: `DualSubUiState`, `LoadStage` and the pure helper functions moved unchanged from
  `AppViewModel.kt` (2,243 → 1,958 lines) to `ui/DualSubUiState.kt`; only `withGoogleAgain`
  became `internal` because the view model still uses it. The view model's own methods were not
  split further in this PR.
- AI assistant: `update_reminders` setting (asks first); Reset all settings turns it back on.
- Strings: `strings_updates.xml` additions and a new `strings_notices.xml` in all 8 languages.
- Deviations: the transcript-panel fallback has no remote switch (turning it off needs a new
  provider path, so it was left out); the problem log lives in the cache folder instead of
  `files/`, so it is never in backups without changing the backup rules.

## Validation result

| Check | Result |
| --- | --- |
| CI parity command (`formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`) | Passed locally (Linux, JDK 21) |
| New unit tests: `UpdateRemindersTest` (8), `RemoteSwitchesTest` (7), `ProblemLogTest` (5), `BackgroundBackoffTest` (3); extended `GoogleWebTranslatorTest`, `AiActionsTest`, `BackgroundPrefetchTest` | Passed |
| `python3 -m unittest discover -s tools/tests -p 'test_*.py'` (42, incl. new release-tool tests) | Passed |
| Workflow YAML parse, `remote-config/switches.json` parse | Passed |
| PR CI on the first head (`verify-build`, `managed-device-tests`, incl. `AppNoticesUiTest`) | Passed on [PR 181](https://github.com/hoangkien1703/dual-sub-replay/pull/181); see the PR for the final head |
| Release workflow writing `update-info.json` | Not run: it runs on the first release after merge (logic covered by the Python tests) |
| Owner's phone: banner for a newer build of this PR, Report a problem opens GitHub | Not run yet (owner) |
| Live YouTube | Not applicable |
