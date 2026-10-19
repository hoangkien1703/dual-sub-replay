# Google's voice for every language, and the phone's voice at once when Google is down

## Status

Implemented (physical-phone check on 5G pending). On 2026-10-19, after testing #133, the owner
reported that with on-device translation on 5G "Chinese pronunciation doesn't work either" and asked
for a new PR in which every language uses Google when possible and the device otherwise. That
authorizes this scope, not merge or release. The owner then sent the 5G problem details, which
only read "Google Translate did not answer within 2 s"; they asked for useful information, so this PR
also makes the details say why each Google address failed.

## Context / problem

- `WordPronouncer` asked Google Translate's speech address (`translate.googleapis.com/translate_tts`)
  only for Chinese, waited up to 8 s, then used the phone's voices. On the owner's 5G Google Translate
  cannot be reached, so every Chinese word waited 8 s and then depended on the phone having a Chinese
  voice; Google's speech engine's Chinese voice is often a network voice that fails the same way.
  This is inferred from the code and the translation failure on the same network.
- Other languages never used Google's voice, even where it sounds better than the phone's.
- Google Translate also answers at `translate.google.com`, a host the app did not try.
- After the quick switch (#133) the problem details only say Google did not answer within the wait.
  The background request can take minutes to fail (15 s per address and try), and even then said
  only "could not be reached" for the first address, without the reason.

## Goals

- While Google translates subtitles, words in every language Google speaks use Google's voice;
  otherwise, and with on-device translation chosen, the phone's voices (unchanged).
- Google's voice is tried at three addresses (`translate.googleapis.com`, `translate.google.com`,
  `clients5.google.com`), the one that last answered first, with a 3 s connect timeout.
- When no address answers, the phone speaks at once for 2 minutes instead of waiting on Google.
- Translation also tries `translate.google.com/translate_a/t` after its other addresses.
- The problem details list each Google address tried and why it failed (address lookup (DNS),
  connection refused, no connection within 5 s, no reply within 15 s, secure connection failed,
  HTTP status, blank reply), updated live while Google is waited for in the background.
- An address that cannot be connected to hands over after 5 s instead of 15 s.
- With no voice for the language, "Open speech settings" opens the speech engine's voice download
  page first.

## Non-goals

- No bundled voices, no new setting, no change to Cantonese (Hong Kong, Macau), which stays with the phone.

## Acceptance criteria

- [x] Every language but Cantonese maps to a Google voice; only Google's engine uses it (`GoogleWebSpeechTest`).
- [x] A refused or unreachable address hands over to the next and is remembered; an address that
  answers without audio is not an outage (`GoogleWebSpeechTest`).
- [x] After no address answers, Google is skipped for 2 minutes, then asked again (`GoogleWebSpeechTest`).
- [x] Translation lists the fourth address in its refusal detail (`GoogleWebTranslatorTest`).
- [x] Connection failures are said in words; when every address fails the detail lists each one,
  and the live list clears when a new request starts (`GoogleWebTranslatorTest`, `GoogleWaitTest`).
- [ ] Owner's phone on 5G: a Chinese word plays with the phone's voice without waiting, or the
  message offers to download a voice; on Wi-Fi, words in every language play with Google's voice.

## Release intent

`release:patch` (default).

## Validation result

| Check | Result |
| --- | --- |
| `GoogleWebSpeechTest`, `GoogleWebTranslatorTest`, `GoogleWaitTest` | Passed (local, 2026-10-19) |
| `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` | Passed (local, JDK 21, SDK 36) |
| Physical phone on 5G and Wi-Fi | Not run; owner's preview test |
