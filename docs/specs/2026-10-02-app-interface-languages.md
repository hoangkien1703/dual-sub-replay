# App interface in more languages

## Status

Implemented. Requested by the owner on 2026-10-02 ("I want to support more language in my app,
so more people can use it easily. the default language is English, and language of the app will
follow device language if possible. the icon to change language show at near bottom left, above
"check the latest version..."") and the suggested plan was agreed in the project thread. See the
PR for final-head CI. Translations are first drafts that native speakers have not reviewed yet.

## Context / problem

Every on-screen label was a Kotlin string literal, so the app could only be shown in English.
Learners who are not comfortable in English have to guess at settings and messages.

## Goals

- The interface is available in English, Vietnamese, Spanish, Brazilian Portuguese, Japanese,
  Korean, Simplified Chinese, and Indonesian.
- With no choice saved, the app follows the device's language list and falls back to English.
- A language button in the navigation drawer, just above "Check the latest version of the app on
  GitHub", lets people pick a language; the choice overrides the device and is remembered.
- Language names in the app (subtitle and translation languages) appear in the interface language.

## Non-goals

- Translating captions, translations, or anything that comes from YouTube or a translator.
- Translating diagnostic error details raised by the caption provider and translators
  (`data/`, `translation/`); their English text is matched by recovery code and logs. The app's
  own headline and status messages around them are translated.
- Right-to-left languages, Traditional Chinese, and other languages: each can be added later with
  one more `values-*` folder and a line in `APP_LANGUAGES` and `locales_config.xml`.
- The website and store listings.

## User-visible behavior

Before: the app is always in English.

After: on first launch the welcome screen is already in the device's language when it is one of
the eight; otherwise English. Android picks the first supported language in the device's list.
The drawer shows a globe button with the current choice ("Device language" or the language's own
name). Tapping it lists "Device language" and the eight languages, each written in its own
language. Picking one restarts the screen in that language (the YouTube page reloads once) and
keeps it on later launches. "Device language" goes back to following the phone. On Android 13+
the same choice appears in Settings > Apps > DualSub Replay > Language.

## Technical constraints / invariants

- No change to the single WebView, navigation classification, caption provider boundary, or
  on-device translation ([tech stack](../project/tech-stack.md#architecture-invariants)).
- `PlaybackArchitectureTest` literal script assertions stay untouched.
- English wording is unchanged, so existing instrumented tests on the English emulator still hold.
- No AppCompat dependency: `MainActivity` stays a `ComponentActivity` with its framework theme.

## Proposed approach / plan

1. `ui/AppLanguage.kt`: the offered languages (`APP_LANGUAGES`), tag matching including legacy
   codes and Simplified-only Chinese, localized language names, and `AppLanguageSettings`.
   Android 13+ stores the choice with `LocaleManager`; older versions store `app_language` in
   app preferences and apply it in `MainActivity.attachBaseContext` and for view model messages.
2. `res/xml/locales_config.xml` plus `android:localeConfig` for the Android 13+ settings page.
3. `ui/AppLanguagePicker.kt`: the drawer button and dialog; `AppNavigation` places it above the
   GitHub line.
4. Move user-visible text into `res/values/strings_<area>.xml` (navigation, onboarding, settings,
   practice, player, status) and add the seven translations with the same file names.
5. Tests: `AppLanguageTest` (tag matching, names, locale config matches the folders, every
   language has every string with the same placeholders) and `AppLanguagePickerUiTest`.

## Acceptance criteria

- [ ] With the device in a supported language and no saved choice, the app shows that language;
      with an unsupported device language it shows English (resource fallback; manual check).
- [ ] The drawer shows the language button above the GitHub line; the dialog lists Device language
      plus all eight languages, and the current choice is selected (`AppLanguagePickerUiTest`).
- [ ] Choosing a language shows the app in it, survives restarting the app, and "Device language"
      returns to the device's language (manual check on Android 13+ and on Android 12 or lower).
- [ ] Every translated folder has every string and plural with the same placeholders, and
      `locales_config.xml` matches `APP_LANGUAGES` and the folders (`AppLanguageTest`, lint
      `MissingTranslation`).
- [ ] Existing unit and instrumented tests pass with English unchanged.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` passes, including `AppLanguageTest` | CI |
| Android lint/build | `./gradlew formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` passes | CI (Android SDK unavailable in the authoring container) |
| Managed-device/emulator | `managed-device-tests` and `fdroid-device-tests` pass, including `AppLanguagePickerUiTest` | CI |
| Physical-device/manual | Switch languages from the drawer on Android 13+ and on Android 12 or lower; restart the app; switch back to Device language | Owner's phone |
| Live YouTube | Not applicable beyond confirming playback still works after switching | Owner's phone |
| Documentation/process | Spec, AGENTS.md, and tech stack describe where strings live | Review |

## Risks / edge cases

- Machine-drafted translations may read unnaturally; native-speaker review is needed before or
  after release.
- Longer translations can wrap or truncate in tight places (buttons, chips); check screenshots.
- Messages already shown when the language changes stay in the old language until they refresh.
- On Android 12 and lower the activity, not the whole process, carries the language; text built
  from the application context outside the view model would stay in the device language.

## Release intent

`release:patch` (the repository default; no label needed). This is a new user-facing feature, so
`release:minor` would also fit if the owner prefers; that needs the owner's decision and the
`release:minor` label on the PR. Latest release is v1.3.3 (2026-10-02, no draft reservation seen),
so patch is estimated as v1.3.4 and minor as v1.4.0; numbers are estimates until reserved.

## Implementation result

As planned. 346 strings and plurals in six `strings_<area>.xml` files, each translated into the
seven languages. Deviations and details:

- Language names (subtitle and translation languages) use the platform's localized names via
  `languageDisplayName`; English keeps the catalog names. The language search matches both the
  shown and the English name.
- Week-day and month initials on the Progress chart use the interface locale's narrow names.
- A few English count strings now use the singular for 1 ("1 word" instead of "1 words").
- `VocabularyRepository` reports malformed records as a count; the screen writes the message.
- The view model resolves its messages through the chosen language (`AppLanguageSettings.wrap`).
- Translations are first drafts written without native-speaker review.

## Validation result

- Passed locally: ktlint formatting ratchet (`tools/check_format_ratchet.py`), detekt with the
  repository config and baseline (0 smells), and a script mirroring `AppLanguageTest`'s resource
  checks (every language has all 346 keys with matching placeholders and plural quantities).
- Not run locally: Gradle build, lint, unit and instrumented tests. The authoring container cannot
  reach Google's Maven repository, so the Android Gradle plugin is unavailable; CI runs them.
- Physical device and live YouTube: not verified; needs the owner's phone.
