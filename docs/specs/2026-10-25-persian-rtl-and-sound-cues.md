# Right-to-left subtitle lines and hiding sound descriptions (issue #152)

## Status

Implemented. In the "Add Persian RTL and hide sound cues" project thread (2026-10-25) Kien asked
for an opinion on [issue #152](https://github.com/hoangkien1703/dual-sub-replay/issues/152) and
picked **"Both, automatic RTL"**: per-line automatic direction with no direction setting, the
Persian half-space fix, and a Hide sound descriptions switch that is **on by default**. Merge and
release stay with the owner.

## Context / problem

Issue #152 (a Persian speaker learning English) reports two problems:

1. **Persian lines are laid out left to right.** Subtitle lines are Compose text with no text
   direction, and Compose resolves an unspecified direction to the layout direction (checked in
   Compose UI 1.12.1's `resolveTextDirection`: Unspecified becomes Ltr, not ContentOrLtr). The app's
   interface languages are all left to right, so a Persian (or Arabic, Urdu, Hebrew) line sits on
   the left, and its end punctuation, brackets and numbers land on the wrong side.
2. **Sound descriptions clutter the lines.** [Music], [Applause], [Laughter], (laughs) and ♪
   marks are shown and sent to translation as if they were speech.

While reading the code: tap-to-learn split Persian words that contain the zero-width non-joiner
(the half-space in words such as می‌خواهم) into two words, because the word pattern only joined
letters with apostrophes and hyphens.

## Goals

- Every subtitle line (transcript list and overlay, original and translation) takes its direction
  from its own first letter and starts on that side, whatever the app's language.
- Other text drawn with the app's type scale (saved meanings, word card, assistant answers) keeps
  punctuation and numbers in place for right-to-left paragraphs.
- Persian half-space words stay one word for coloring and tap-to-learn.
- Settings → Translation → Hide sound descriptions (on by default) removes sound descriptions
  before lines are merged and translated: [...] always, (...) only when it names a sound or is in
  capitals, ♪ marks around lyrics (the lyrics stay). Cues with nothing else disappear. It also
  applies to live captions and to YouTube's own translated captions (the translation fallback).
- The assistant can read and change the switch (`hide_sound_descriptions`); like
  `natural_subtitle_flow` it reloads the subtitles, so it waits for the user's tap.

## Non-goals

- A manual text-direction setting (owner decision: automatic only).
- A Persian interface language.
- Removing speaker marks such as `>>`.

## User-visible behavior

- A Persian translation under an English line now starts at the right edge, reads right to left,
  and "سلام!" shows its "!" at the left end. English lines are unchanged.
- With the switch on (default), a "[Music]" line no longer appears; "[Applause] Thank you." shows
  "Thank you."; "♪ Let it go ♪" shows "Let it go". Turning the switch off reloads the open
  video's subtitles with the descriptions back. Reset all settings turns it back on.

## Technical constraints / invariants

- User-visible text in all 8 interface languages (`strings_settings.xml`), no literals in Kotlin.
- Settings the assistant can change go through `AiSetting`; reloading settings set `asks = true`
  (`AiActionsTest`).
- Reset all settings removes `hide_sound_cues` (`SubtitleColors.kt`, `SubtitleHighlightTest`).

## Proposed approach / plan

1. `ui/SubtitleLineDirection.kt`: `TextStyle.inLineDirection()` (`TextDirection.Content`),
   `startsRightToLeft(text)` (first strong character), and `Modifier.lineSide(text, layout)`,
   which fills the width only when the line's direction differs from the screen's, so the line
   starts on its own side and left-to-right lines keep today's layout and tap areas.
2. Use them in `SelectableSubtitleText` and the plain-text fallbacks in `SubtitleCard.kt` and
   `LearningPlayerRoot.kt`; give `DualSubTheme` a type scale with `TextDirection.Content`.
3. Allow U+200C inside words in `LanguageAwareTokenizer` (both word patterns).
4. `data/SoundCues.kt`: `strip(text)` and `filter(cues)` (timed words inside brackets dropped too).
   Applied in `AppViewModel.persistCaptionTrack`, `liveTranslationKey` and before
   `youTubeRowTranslations`, behind `hide_sound_cues` (default true), which reloads the video.
5. Settings switch, `AiSetting.HIDE_SOUND_DESCRIPTIONS`, strings, tests.

## Acceptance criteria

- [x] `startsRightToLeft` is true for Persian/Hebrew lines (also after leading punctuation or
  digits) and false for English or letterless lines (`SubtitleLineDirectionTest`).
- [x] Persian half-space words tokenize as one word in the original and translation paths
  (`LanguageAwareTokenizerTest.keepsPersianHalfSpaceWordsWhole`).
- [x] Sound descriptions are removed, speech in ordinary parentheses is kept, lyrics lose only
  their ♪ marks, untouched lines come back unchanged, cue-only cues and their timed words are
  dropped (`SoundCuesTest`), and live captions follow the switch (`LiveSubtitleRecoveryTest`).
- [x] The assistant setting waits for a tap (`AiActionsTest`); Reset all settings covers the key.
- [ ] On a phone: a Persian translation reads right to left from the right edge in the list and
  the overlay; [Music] lines are gone with the switch on and back with it off.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes, including the new tests above | Cloud container / CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | CI |
| Managed-device/emulator | Existing Compose UI tests pass | CI |
| Physical-device/manual | Translate an English video to Persian; check direction, punctuation, tapping a half-space word; a video with [Music]/[Applause] cues with the switch on and off | Owner's phone (preview build) |
| Live YouTube | Same as above on live YouTube | Owner's phone |

## Risks / edge cases

- A parenthesis that names a sound but is speech ("(I love music)") is removed; only lines up to
  40 characters with the sound word in the first four words count, which keeps this rare.
- Mixed lines take the direction of their first letter; an English word first in a Persian line
  makes that line left to right (same rule as Android's own text fields).

## Release intent

`release:patch` (default; owner gave no other intent).

## Implementation result

As planned, plus two small changes:

- A track that holds nothing but sound descriptions keeps them, instead of failing as "no
  readable text".
- The assistant's labels for plain on/off settings moved into one map
  (`SETTING_SWITCH_TITLES` in `AiAssistantActions.kt`) to stay under the complexity limit.

## Validation result

- Passed (cloud container, 2026-10-25): `formatCheck complexityCheck testDebugUnitTest lintDebug
  assembleDebug`, including `SoundCuesTest` (6), `SubtitleLineDirectionTest` (2),
  `LiveSubtitleRecoveryTest` (7), `LanguageAwareTokenizerTest`, `AiActionsTest`.
- Not run here: managed-device tests (no KVM in the container; PR CI runs them).
- Unverified: the phone and live YouTube checks in the validation plan; they wait for the
  owner's test of the preview build.
