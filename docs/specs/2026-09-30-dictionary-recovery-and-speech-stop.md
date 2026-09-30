# Dictionary recovers from a bad file; a phrase selection stops the word's speech

## Status

Implemented. Approved for implementation by the owner on 2026-09-30 ("do what you recommend",
after the review of the external Sol 6.1 audit, items 4 and 5 in their smaller recommended
form). Local checks pass; see the PR for the final-head CI.

## Context / problem

Three small problems, reproduced on 2026-09-30 by compiling the unchanged
`JapaneseDictionaryStore.kt` with a harness, or confirmed by reading the code:

1. **A same-size corrupt dictionary never recovers.** `isInstalled()` checks only the file's size.
   If the stored jar is damaged after a verified install (storage corruption), every load fails with
   `ZipException`, the file stays, and every retry fails the same way. Japanese keeps the heuristic
   tokenizer for good.
2. **An interrupted download leaves a `.part` file.** `installFrom` deleted the partial file only on
   its normal path, so a dropped connection left up to 13 MB behind until the next attempt
   overwrote it.
3. **The download had no overall deadline.** Connect (20 s) and read (60 s) timeouts do not bound a
   connection that keeps trickling bytes, and only one download runs at a time.
4. **A word's speech could start after the selection became a phrase.** With "Pronounce tapped
   words" on, tapping a word starts recording and playing it. Extending the selection to a phrase
   called `WordPronouncer.forgetUnless`, which cleared the cache but left the speech job running,
   so the word could still play a moment later.

## Goals

- A dictionary that fails to load as a zip or with an I/O error is deleted, so the next retry
  downloads it again. Running out of memory keeps the file.
- No partial file remains after any failed download.
- The whole transfer is bounded (OkHttp call timeout, 10 minutes for 13 MB).
- Moving the selection away from the word being pronounced stops that word's speech.

## Non-goals

- No hashing on every start, no new status model or retry UI, no Wi-Fi-only rule (the owner chose
  first-use download on any network).
- No change to phrases never auto-speaking, the explicit Pronounce action, reuse of the last word's
  recording, or the tap-inside-selection-to-clear behavior.
- `MediaPlayer.prepare()` on the small local recording stays synchronous.

## User-visible behavior

- **Before:** a damaged dictionary file left Japanese on heuristic word splitting permanently; a
  tapped word could still speak after extending the selection to a phrase.
- **After:** the dictionary downloads again on the next retry (about a minute later, or the next
  launch); extending a selection silences the word right away.

## Technical constraints / invariants

- Keep the pinned size and SHA-256 check and the atomic rename; an intact file is never replaced by
  a failed download (downloads only start when the file is missing or was just deleted).
- Plain JUnit4 tests through the existing injectable `open` function.

## Proposed approach / plan

1. `installFrom`: `try/finally` deletes `.part` unless the rename succeeded.
2. `loadTokenizer`: on `IOException` (including `ZipException`), delete the file and rethrow.
3. OkHttp client: `callTimeout(10, MINUTES)`.
4. `PronunciationCache.holds`; `WordPronouncer.forgetUnless` calls `stop()` when the cache is not for
   the new selection.

## Acceptance criteria

- [x] A dropped connection returns false and leaves the dictionary folder empty.
- [x] A same-size corrupt file fails to load, is no longer installed, and the next `install()`
  downloads once and loads.
- [x] Existing dictionary tests (verified install, mirror fallback, damaged/oversized rejects, no
  second download) pass.
- [x] The cache reports which word it holds while recording, so a phrase selection stops it.
- [ ] Owner's phone: tap a Japanese word, then drag to a phrase before it speaks; the word stays
  silent (not run).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest`, including new `JapaneseDictionaryStoreTest` and `PronunciationCacheTest` cases | Local Linux, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Local Linux, CI |
| Managed-device/emulator | Existing suite | CI |
| Physical-device/manual | Word → phrase during speech; real first-use download | Owner's phone (not run) |

## Risks / edge cases

- An `IOException` while reading a healthy file (for example the storage being unmounted) now also
  deletes it, costing one extra 13 MB download. That is rare and self-healing.
- Moving the selection to another word or phrase also clears a speech message left from the previous
  selection (for example "No voice is available"), since it no longer describes what is selected.

## Release intent

`release:patch` (repository default for a bug fix). The version number depends on merge order with
the other audit PRs and is an estimate until reserved.

## Implementation result

As planned.

## Validation result

- `./gradlew formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
  passed locally (Linux, JDK 21, Android SDK 36).
- With `JapaneseDictionaryStore.kt` reverted, `aDroppedConnectionLeavesNoPartialFile` and
  `aCorruptInstalledDictionaryIsDownloadedAgainAfterItFailsToLoad` fail; with the fix both pass.
- The speech stop itself runs on Android's text-to-speech and `MediaPlayer`, so it is covered only
  through `PronunciationCache.holds`; the phone check above is not run.
- Final-head CI: see the PR.
