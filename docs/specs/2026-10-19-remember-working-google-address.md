# Remember the Google address that works, across restarts

## Status

Implemented. On 2026-10-19, after #134, the owner asked whether the app should keep using a Google
address that works until it fails, and then said "yes create pr for that" to keeping it across
restarts. That authorizes this scope, not merge or release.

## Context / problem

- Translation (`GoogleWebTranslator.preferredEndpoint`) and pronunciation (`GoogleSpeechHealth.preferred`)
  already try the address that last worked first, until it fails.
- Both lived only in memory, so after every restart the app started again from
  `translate.googleapis.com`. On a network that blocks that address, the first lines waited up to
  the 5 s connect timeout before the next address was tried.

## Goals

- The name of the translation address and of the voice address that last worked is kept in the
  `google_addresses` preferences file and tried first after a restart.
- It is written only when it changes; an unknown or missing name starts from the first address.

## Non-goals

- No setting, no change to the order of addresses, timeouts or retries. Not part of Reset all settings.

## Acceptance criteria

- [x] A new translator starts with the address the previous one kept, and writes only on a change
  (`GoogleWebTranslatorTest`).
- [x] An unknown kept name starts from the first address (`GoogleWebTranslatorTest`).
- [x] The voice address that worked is kept and asked first after a restart (`GoogleWebSpeechTest`).

## Release intent

`release:patch` (default).

## Validation result

| Check | Result |
| --- | --- |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed (local, JDK 21, SDK 36; 819 unit tests) |
| Physical phone | Not run; owner's preview test |
