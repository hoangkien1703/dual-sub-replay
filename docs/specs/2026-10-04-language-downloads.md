# Languages on this device: see and download translation languages ahead of time

## Status

Implemented. The owner asked for this in the project thread on 2026-10-04 ("show setting for
support language like Japanese, so people can know and decide to pre download languages").
Defaults picked by Claude and stated in the thread: the screen lives under Settings →
Translation, and the Japanese row also covers the Japanese word dictionary.

## Context / problem

Translation models and the Japanese dictionary download silently the first time a language is
needed. People cannot see which languages the app supports, which are already on the phone, or
download one before going offline. The only control is "Preload translation models in
background", which only covers the current pair.

## Goals

- A **Languages on this device** screen, opened from Settings → Translation, that lists every
  language the current build can translate.
- Each language shows whether it is downloaded and offers **Download** or **Remove**.
- Japanese also downloads (and removes) the Japanese word dictionary that tap-to-learn uses.
- Works in both builds: ML Kit (full) and Bergamot (F-Droid).

## Non-goals

- No per-language size from ML Kit (it does not report one); a general size hint is shown.
- No download progress percentage; rows show "Downloading…".
- No change to automatic on-demand downloads or the preload switch.
- No speech voices; Android manages those.

## User-visible behavior

- **Before:** no list of languages; models download only when first needed.
- **After:**
  - Settings → Translation has a **Languages on this device** row. It opens a full-screen list.
  - The list has two groups: **On this device** (English as "Built in", then downloaded
    languages) and **Available to download**. Names follow the interface language.
  - A row shows its status ("Downloaded", "Not downloaded", "Downloading…", "Removing…") and a
    **Download** or **Remove** button. A failed download says so on the row and can be retried.
  - Japanese says it includes the word dictionary for tapping words.
  - If the language list cannot be loaded (F-Droid build offline with no cached catalog), the
    screen says so and offers **Retry**.

## Technical constraints / invariants

- Translation stays on-device with no service key ([tech stack](../project/tech-stack.md)).
- The F-Droid variant must keep proprietary dependencies out; Bergamot code stays in
  `src/fdroid`, ML Kit code in `src/full`, with the same `OnDeviceTranslator` API.
- User-visible text goes in `strings_settings.xml` with every translated `values-*` copy.

## Proposed approach / plan

1. `OnDeviceTranslator` (both variants) gets `downloadableLanguages()`, `downloadedLanguages()`,
   `downloadLanguage(code)` and `removeLanguage(code)`.
   - ML Kit: `RemoteModelManager` with `TranslateRemoteModel`; removing drops cached
     "prepared" pairs that use the language.
   - Bergamot: a language is its `xx→en` and `en→xx` models that exist in Mozilla's catalog;
     removing deletes their folders and releases any loaded engine model.
2. `JapaneseDictionaryStore` gets a synchronized `remove()`, and `install()` becomes
   synchronized so a settings download and an on-demand download cannot write the same file.
   `JapaneseMorphology` exposes install/remove/installed for the settings screen.
3. `ui/LanguageDownloads.kt`: `LanguageDownloadsController` (state flow + download/remove/
   refresh), pure `languageDownloadRows` grouping, and the `LanguageDownloadsDialog` screen.
   `AppViewModel` owns the controller; `DualSubApp` provides it with a composition local, so
   the settings dialog shows the row only when a controller exists.

## Acceptance criteria

- [x] Rows group into downloaded (English first as built in) and available, each sorted by
  name; busy and failed states map to the right status (`LanguageDownloadsTest`).
- [x] Download marks a language downloaded on success, failed on error, and ignores a second
  tap while busy; Remove drops it from downloaded; a failed list load reports `loadFailed`
  (`LanguageDownloadsTest`).
- [x] Japanese counts as downloaded only with both its translation model and dictionary
  (`LanguageDownloadsTest`).
- [x] The screen shows both groups, Download calls the controller, and Retry appears after a
  failed load (`LanguageDownloadsDialogTest`).
- [ ] On a phone, downloading Japanese then turning on airplane mode still translates
  Japanese captions (owner's preview test).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes, including `LanguageDownloadsTest` | Local, JDK 21 |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local and CI |
| F-Droid build | `fdroid-build` CI job compiles the Bergamot implementation | CI (needs NDK) |
| Managed-device/emulator | `LanguageDownloadsDialogTest` in `managed-device-tests` | CI (no KVM locally) |
| Physical-device/manual | Download/remove a language and translate offline | Owner's preview |
| Live YouTube | Not applicable beyond the manual offline check | |

## Risks / edge cases

- ML Kit may refuse to delete a model that a translator is using; the row then shows the
  failure and the model stays.
- The Bergamot catalog needs network the first time; a cached catalog (7 days) works offline.
- Removing Japanese while its dictionary is loaded keeps tap-to-learn working until restart.

## Release intent

`release:patch`, the default; the owner gave no other intent. Expected version is the next
patch after v1.3.6 and PR #99 (estimate until reserved).

## Implementation result

Fill after implementation.

## Validation result

Fill after validation.
