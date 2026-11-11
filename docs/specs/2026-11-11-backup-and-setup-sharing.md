# Backup & sharing: one backup file and shared setup links

## Status

Implemented. Owner pick 2026-11-10 in the project thread "Add backup and setup sharing": after
discussing user-made mods, the owner chose "Backup file + Share my setup" over script mods, data
packs or patched APKs, then approved the plan and mockups ("ok you can implement it").

## Context / problem

The owner asked whether people could make and share mods on top of the app. Script mods would
need a frozen plugin API, could read the signed-in YouTube session or steer the AI with full
access, and patched APKs break the updater's signature check. What people actually lose today:

- The app is installed from GitHub, so Android's own backup usually does not bring it back on a
  new phone. Progress (immersion time, streak) had no export at all, and settings could not be
  moved or shared.
- Practice → Export already writes a full words backup (`data/PracticeTransfer.kt`).

## Goals

- Settings → **Backup & sharing**, the last section, just above Reset (in the App group since the [settings groups change](2026-11-11-settings-groups-and-current-values.md)).
- **Back up**: one JSON file the user saves anywhere, with ticks for Settings, Practice words
  (decks, review dates, known words, the learner's own kanji and hanzi stories) and Progress
  (study days and the daily goal setting).
- **Restore**: reads that file, shows what it adds, merges, and restarts the app.
- **Share link**: sends only look and study settings to a friend as a link.
- **Use a setup**: pastes a link, or the app opens it from the landing site; it lists the
  settings that change before anything is written; **Undo last setup** puts them back.
- Works across updates: every stored setting is sorted into share / backup / never, and a test
  fails when a new setting is not.

## Non-goals

- Mods, plugins, scripts, CSS, prompt packs (owner chose against, 2026-11-10).
- Cloud sync or an account. Sharing Progress or words with other people (Practice Export already
  shares words).
- Restoring AI keys, the YouTube sign-in, AI chats or memories, or Anki sound clips and pictures.

## User-visible behavior

- Back up: dialog "Back up" with the three ticks (all on), a note listing what is never in the
  file, then Android's save picker (`DualSub-Backup-<date>.json`). "Last backup: …" under the card.
- Restore: Android's open picker, then "Restore this backup?" with the date and version, one tick
  per part the file holds: Settings (replaces current settings), Practice words ("Adds N new
  words. Words you already have keep the newer review date."), Progress ("Adds N study days. Days
  you already have keep the higher time."), and a note to add AI keys again. Restore restarts the app.
- Share link: Android's share sheet with "My Dual Sub Replay setup: <link>". The link is
  `https://hoangkien1703.github.io/dual-sub-replay/setup/#1.<code>`; the code (deflated JSON,
  base64url) sits after `#`, which browsers never send to a server.
- The landing site's `setup/` page (noindex, English or Vietnamese by browser language) offers
  "Open in DualSub Replay" (`intent://setup?d=<code>#Intent;scheme=dualsubreplay;…`, falling back
  to the home page), "Copy link", and how to paste it in the app.
- "Use this setup?" lists the settings that change (by their Search settings names, then "Other
  settings"), says shared setups never change words, progress, AI settings or keys, and restarts
  the app on "Use setup". Sharing the link text to the app also opens it.

## Technical constraints / invariants

- `data/SettingsTransfer.kt` `SETTING_SPECS` gives every key in `dual_sub_preferences` and
  `ai_assistant` a scope and type. SHARE: look and study choices. BACKUP: translation language,
  overlay positions, Anki app and deck, daily goal, updates, TV layout, and every AI choice.
  NEVER: `ai_custom_base_url` (a planted address could send keys elsewhere), `ai_full_access`,
  the app language (Android 13+ keeps it outside preferences), tabs and accounts, one-time notices
  and phone state. `ai_model_*` and `practice_decks` `deck_*` are BACKUP.
- Every value read from a file or link is checked against its spec (key known, type right, number
  in range, text ≤ 4,000 characters); anything else is skipped, so a hostile file can't store a
  value the app would crash reading. Files are capped at 24 MiB; Practice words still go through
  `parseTransfer`'s checks.
- Restores write preferences with `commit()` and then restart the process, so every screen and
  ViewModel reads the restored data.

## Proposed approach / plan

1. `data/SettingsTransfer.kt`: specs, export/import, backup encode/parse, word and progress
   restore plans, setup link encode/decode, undo encode/decode.
2. `ImmersionRepository.allDays/mergeDays` (MAX per day and language),
   `KnownWordsRepository.allKnown`.
3. `ui/BackupSharing.kt`: section, dialogs, `SetupLinkInbox`, `PendingSetupDialog`, restart.
4. `MainActivity` hands setup links to the inbox; manifest `dualsubreplay://setup` filter.
5. Strings in all 9 languages (`strings_backup.xml`), AI guide line, landing page `site/setup/`.

## Acceptance criteria

- [x] A setting missing from `SETTING_SPECS` fails `BackupSharingTest.everySettingIsSortedIntoShareBackupOrNever`.
- [x] Shared links never carry AI, translation-language, Anki-app or daily-goal settings; the AI
      custom address and full access never leave the phone, even in backups.
- [x] Wrong types, out-of-range numbers and unknown keys in a file or link are dropped.
- [x] A backup round-trips settings, words, known words, stories and progress.
- [x] Restoring words adds new ones and keeps the further-reviewed copy; restoring progress keeps
      the higher time per day and language, so restoring twice adds nothing.
- [ ] On a phone: back up, clear app data, restore, and see settings, words and the streak back.
- [ ] On a phone: share a link to another phone (or paste it), use it, see the change, then Undo.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest --tests "*BackupSharingTest"` passes (16 tests) | Cloud container |
| Site | `python3 -m unittest discover -s tools/tests -p 'test_site.py'` passes | Cloud container |
| Android lint/build | CI `verify-build` | PR CI |
| Managed-device/emulator | `BackupSharingUiTest` (section, Back up choices, paste error, Use this setup, Restore) with screenshots | PR CI |
| Physical-device/manual | The two unchecked criteria above | Owner's phone, preview build |

## Risks / edge cases

- Restore and Use setup restart the app; a playing video stops. The dialogs say so.
- The landing page only exists after merge (Pages deploys from `main`); before that, test links by
  pasting them into Use a setup.
- A future preference that is read with a literal key instead of a `*_PREFERENCE` constant is not
  found by the scan; the test lists the known literal keys and the KDoc asks for constants.

## Release intent

`release:patch` (default for an APK change; the owner gave no other intent).

## Implementation result

As planned. Deviation from the mockup: "Use this setup?" lists setting names without their new
values, because many settings store internal codes; names come from Search settings.

## Validation result

- Unit: `BackupSharingTest` 16/16 passed locally. Site tests passed.
- Device tests and lint: see the PR's CI.
- Physical device: not run (owner's preview test).
