# Simplified and Traditional Chinese translation

## Status

Implemented. Owner pick 2026-11-20 ("Split entry, Traditional online only") after a Reddit request: "would love to have an option for simplified chinese and traditional chinese for the translation". Patch release.

## Context / problem

"Translate to" had one entry, Chinese (`zh`), and it always gave Simplified:

- Google Translate got `zh-CN`; its Traditional code `zh-TW` was never asked for.
- YouTube's own translation (the fallback) matched `zh` by base code, so it took whichever Chinese YouTube listed first.
- AI translation and Meaning in this sentence named the language "Chinese", so the model chose the script.
- ML Kit has one Chinese model, and it writes Simplified.
- `TranslationLanguages.normalize` cut every code to its base, so `zh-TW` could not be stored as a target at all.

## Goals

- Two entries in every language list that offers translation languages (Translate to, the third line, the first-run "language you speak"): "Chinese (Simplified)" and "Chinese (Traditional)".
- People who picked Chinese before keep exactly what they had.
- Traditional reaches every online translator in its own script.

## Non-goals

- Offline Traditional (OpenCC tables or similar): owner chose online only; it can come later if people ask.
- Telling Simplified and Traditional apart for captions, the learning language, saved words or study tools: those keep the base `zh`.
- A Traditional Chinese app interface.
- Pre-picking a language on the first-run screen: it pre-picks none today, for any language.

## User-visible behavior

- The language lists show "Chinese (Simplified)" and "Chinese (Traditional)", named in the interface language ("Tiếng Trung (Giản thể)" / "Tiếng Trung (Phồn thể)", "中文（简体）" / "中文（繁體）"...). Search "Chinese" finds both.
- Traditional translates with Google (`zh-TW`), YouTube's own translation (`zh-Hant`) and AI ("Traditional Chinese"). Like Azerbaijani, it never moves to the device: "On this device only" shows the online-only message, and after a Google failure rows take YouTube's translation or ask Google again.
- Simplified is unchanged except that AI prompts now say "Simplified Chinese" instead of "Chinese", and YouTube's fallback asks for `zh-Hans` exactly.
- The AI assistant understands "Chinese", "Simplified Chinese" and "Traditional Chinese" (and codes such as zh-Hant) when asked to change the translation language.

## Technical constraints / invariants

- [Translation](../project/tech-stack.md#translation): online-only languages never move to the device; no service key.
- Saved `target_language = "zh"` must keep meaning Simplified (no migration).

## Proposed approach / plan

1. `TranslationLanguages`: `zh` renamed "Chinese (Simplified)" (aliases "Chinese", "Simplified Chinese"); new `zh-TW` "Chinese (Traditional)", `onDevice = false`. New `targetCode` (base code, but zh-TW / zh-HK / zh-MO / zh-Hant stay `zh-TW`), used by `find`, plus `findByName`, `englishTargetName` and `youTubeTranslationCode`. `normalize` is unchanged for captions and the learning language.
2. Target paths use `targetCode`: `setTargetLanguage`, recent languages, third line, first-run native language, the picker's selected mark, `GoogleWebTranslator` (cache keys and Google codes), `AiSubtitleTranslator` (prompt, same-language check, cache key), `languageDisplayName`.
3. `OnDeviceTranslator` never offers a model for an `onDevice = false` target, so Traditional is not translated to Simplified on the device.
4. YouTube's fallback asks for `zh-Hans` / `zh-Hant`; AI prompts (subtitle translation, Meaning in this sentence) name the script.

## Acceptance criteria

- [x] Both entries are listed and named by script; `zh` stays Simplified (`TranslationLanguagesTest`, `AppLanguageTest`).
- [x] zh-TW, zh-HK, zh-MO, zh-Hant find Traditional; zh-CN, zh-SG, zh-Hans find Simplified; `normalize("zh-TW")` is still `zh` (`TranslationLanguagesTest`).
- [x] Google gets zh-TW for Traditional and zh-CN for Simplified (`GoogleWebTranslatorTest`, `TranslationLanguagesTest`).
- [x] Traditional has no on-device model (`TranslationLanguagesTest.traditionalChineseTranslatesOnlineInItsOwnScript`, `onDeviceFlagMatchesMlKitModels`).
- [x] The AI prompt says "Traditional Chinese (zh-TW)" / "Simplified Chinese (zh)" (`AiSubtitleTranslatorTest`).
- [x] The landing site and README list both entries and say 102 languages (`tools/tests/test_site.py`).
- [x] Recent languages, third line and first-run choices keep the two apart (`RecentLanguagesTest`, `ThirdLineAndVideoFilterTest`, `OnboardingPreferencesTest`); the assistant maps names (`AiActionsTest`).
- [ ] The picker shows both entries with Traditional marked selected (`SubtitleUiTest.translateToListsSimplifiedAndTraditionalChinese`, screenshot `translate-to-chinese-scripts`): PR CI.
- [ ] Phone check: pick Chinese (Traditional), play an English video with Google, then with AI translation: lines in Traditional characters (owner, preview APK).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | The 9 touched classes with `testDebugUnitTest --tests ...`: pass | Cloud container, 2026-11-20 |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` | PR CI |
| Managed-device/emulator | `SubtitleUiTest` with the new picker test and screenshot | PR CI |
| Physical-device/manual | Traditional with Google and AI on an English video | Owner, preview APK |
| Live YouTube | YouTube fallback with `zh-Hant`: not checked from the cloud | Unverified |

## Risks / edge cases

- A video whose captions are Traditional still resolves to source `zh`, so with target Traditional Google is asked to convert it (harmless, just a request).
- The AI subtitle and source labels fall back to "Chinese (Simplified)" when YouTube gives no track name for a `zh` source; YouTube's own track names are shown when present.
- If YouTube names its Traditional target something other than `zh-Hant`, the page script's base-code match picks the first Chinese it lists (the old behaviour).
