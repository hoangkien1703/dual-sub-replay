# Azerbaijani interface and online-only subtitle languages

## Status

Implemented. Requested in [discussion #81](https://github.com/hoangkien1703/dual-sub-replay/discussions/81#discussioncomment-19351247)
(an Azerbaijani user asked for Azerbaijani as an interface language). The owner picked
"Both in one PR" on 2026-11-02: the Azerbaijani interface, and more subtitle translation
languages so that the same user can also get Azerbaijani subtitles. The Azerbaijani
translation is a first draft that a native speaker has not reviewed yet.

## Context / problem

- The interface was available in 8 languages; Azerbaijani was not one of them.
- The "Translate to" list only offered the 59 languages Google ML Kit has on-device models
  for, so Azerbaijani, Kazakh, Uzbek, Armenian, Burmese, Khmer, Nepali and others could not
  be picked, although Google Translate, the AI services and YouTube's own translated captions
  all handle them.

## Goals

- Azerbaijani (`az`, Latin script) is an interface language, picked by the device language
  or the drawer's language button.
- 42 more subtitle translation languages that Google Translate supports and ML Kit does not.
- Those languages work with the default translator (Google), the AI choices and the
  YouTube fallback, and say clearly what to do with "On this device only".

## Non-goals

- On-device translation for languages ML Kit has no model for.
- Right-to-left interface languages, or Azerbaijani in Cyrillic or Arabic script.
- Translating the store listings.

## User-visible behavior

- Interface: "Azərbaycan dili" in the drawer's language list and in Android 13+
  Settings > Apps > Language; a phone set to Azerbaijani shows the app in Azerbaijani.
- "Translate to" lists 101 languages. Languages without an ML Kit model (for example
  Azerbaijani) are not offered under Languages on this device.
- With Google (default) or AI: translation works as for any language. If Google fails, rows
  take YouTube's own translated captions, else ask Google again (there is no device model to
  fall back to); the problem icon and the 2-minute Google re-check work as before. A line
  Google leaves blank stays blank instead of being translated on the device.
- With "On this device only": the captions show alone with "Azerbaijani has no on-device
  translation. In Settings, set “Translate subtitles with” to Google or AI."
- Word card translation follows the same rule: an online-only language stays with Google.

## Technical constraints / invariants

- `TranslationLanguageOption.onDevice` marks ML Kit languages; `TranslationLanguagesTest`
  checks the flag against `TranslateLanguage.fromLanguageTag` and that every ML Kit language
  is offered. `OnDeviceTranslator.supports` is the runtime check.
- New interface language: `values-az/` with every file of `values/`, `APP_LANGUAGES`,
  `locales_config.xml` and `AppLanguageTest` (lint `MissingTranslation` and the test catch
  gaps and placeholder differences).

## Proposed approach / plan

1. `TranslationLanguages`: add the online-only languages with `onDevice = false`.
2. `AppViewModel`: in `followOnDevice`, `translateOnDevice` and `translateText`, skip the
   device for pairs ML Kit cannot translate (YouTube, then Google again; blank stays blank),
   and raise `status_translation_needs_online` for "On this device only".
3. `values-az/` translated from English; register `az`.
4. README, site and `llms.txt`: 101 languages, 59 offline.

## Acceptance criteria

- [x] Azerbaijani appears in the language picker and every string has an Azerbaijani
  translation with the same placeholders (`AppLanguageTest`, lint).
- [x] "Translate to" offers Azerbaijani; the on-device flag matches ML Kit
  (`TranslationLanguagesTest`).
- [x] "On this device only" with Azerbaijani shows the new message instead of a crash or
  an English "not supported" error.
- [ ] Preview APK: a video translated into Azerbaijani with Google shows Azerbaijani lines.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew :app:testDebugUnitTest` passes | Cloud container |
| Android lint/build | `./gradlew :app:lintDebug :app:assembleDebug` passes | Cloud container / PR CI |
| Managed-device/emulator | PR CI managed-device tests pass | PR CI |
| Physical-device/manual | Preview APK: app in Azerbaijani; English video → Azerbaijani subtitles | Owner's phone |

## Risks / edge cases

- Azerbaijani wording may read unnaturally in places; the requester was asked to proofread.
- Online-only languages have no offline fallback, so on a network without Google the second
  line depends on YouTube's translated captions.

## Release intent

`release:patch`: new languages, no breaking change.
