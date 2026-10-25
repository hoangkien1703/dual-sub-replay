# Word meaning box and kanji details

## Status

Implemented (owner request 2026-10-24 in the "Word meaning and Kanji details" project thread; the
owner picked option A, ready-made stories, after the research below); waiting for the owner's phone
test.

## Context / problem

The owner's screenshot of the 葉っぱ word card: "i want the "meaning" when translating more nổi bật,
and help people easy to notice it. also in Japanese i want a button or something to show the
meaning of Kanji, how to write, radicals, and importantly how to remember it. since it is not easy,
research to see how other app and projects do this successfully".

The meaning was a plain outlined text field, easy to miss among the other rows. Japanese words had
no per-kanji help at all.

Research (summarised in the thread's Claude Doc): apps that teach kanji well (WaniKani, Kanji Study,
Koohii/RTK, Takoboto, jisho.org) show each kanji's keyword, stroke order animation, its parts with
the radical, and a memory story built from those parts. Open data covers everything but the
stories: KANJIDIC2 (CC BY-SA 4.0) has readings, meanings, grades, stroke counts and Hán Việt for
2,134 of the 2,136 jōyō kanji; KanjiVG (CC BY-SA 3.0) has stroke paths and parts with radical and
sound-part markers. Shared mnemonics are not usable (Koohii's are CC BY-NC-SA, WaniKani's are
copyrighted, Kanji alive withholds its hints), so the stories are written for this app.

## Goals

- The meaning stands out: a filled box in the primary container color with an accent bar, a
  "Meaning (language)" label and the meaning in large bold text; a pencil edits it in place.
- Japanese words get one **Kanji** section on the card: one row per kanji (the kanji, its Hán Việt
  for Vietnamese learners, keyword and stroke count). A row opens that kanji's page inside the card:
  - the kanji with Hán Việt, keyword, English meanings, strokes, school grade and radical;
  - on and kun readings;
  - how to write: Play draws it stroke by stroke, and numbered frames show each stroke;
  - its parts, the radical and the part that gives the sound marked (with that part's reading);
  - how to remember: a ready-made story for every jōyō kanji in English and Vietnamese, the
    learner's own story (saved on the phone), and "Another story with AI" when the assistant is on.
- One switch, Settings → Study tools → **Kanji details**, on by default; the assistant can change it.
- Works offline with no setup; the AI story is an optional extra.
- Owner add-on (same thread): a small "See the GitHub repo" link next to "Open-source licenses" at
  the bottom of the drawer, so people can find the repo and star it.

## Non-goals

- Handwriting practice or stroke checking, spaced repetition of kanji, kanji search.
- Stories for the 863 jinmeiyō (name) kanji: their pages show everything but the bundled story.
- Hán Việt or stories in other interface languages (they get the English stories).

## User-visible behavior

- Word card, any language: the Meaning box replaces the outlined field. While translating it shows
  a progress bar; the pencil turns it into a text field and a check saves it.
- Japanese word with kanji: below the word details, "Kanji" and one card per kanji. Tapping one
  replaces the card's content with that kanji's page and a "Back to 葉っぱ" button.
- Vietnamese learners (meaning language or interface Vietnamese) see Hán Việt in capitals (DIỆP)
  on rows, page and parts, Vietnamese keywords, parts named with their bộ or Hán Việt (艹 THẢO · cỏ) and Vietnamese
  stories ending with the Hán Việt reading and a common word that uses it ("DIỆP như "diệp lục"").
  Everyone else sees English keywords and stories.
- "Your own story" saves 0.6 s after typing stops, in `files/kanji-stories/mine.txt`, so Reset all
  settings keeps it; an empty field removes it.
- "Another story with AI" opens the assistant with the kanji's facts (meanings, readings, Hán Việt
  for Vietnamese learners, parts, the bundled story) and asks for a different story.
- With Kanji details off, the section does not show.
- Drawer bottom: "Open-source licenses · See the GitHub repo"; the link opens the repository.

## Technical constraints / invariants

- Data files only, in `app/src/main/learner-data/` (copied into `assets/learner-data/` by
  `LearnerLanguageData`), read with `LearnerData.open` on a background thread:
  `ja-kanji.txt` (2,999 kanji, 270 KB), `ja-kanji-strokes.txt` (2.6 MB, about 1 MB compressed in
  the APK; scanned line by line for one kanji, never held in memory), `ja-radicals.txt`,
  `ja-kanji-stories-en.txt`, `ja-kanji-stories-vi.txt`.
- `tools/kanji/build_kanji_data.py` rebuilds the first two from KANJIDIC2 and KanjiVG (tag pinned
  in the script) and carries 15 Hán Việt corrections where KANJIDIC2 gives the reading of another
  form or none (for example 戦 Chiến, 条 Điều, 鶏 Kê, 𠮟 Sất).
- Licences: the two generated files stay CC BY-SA (4.0 and 3.0) and say so in their headers; the
  credits are in `THIRD_PARTY_NOTICES.md` and on the licence screen. Radicals and stories are the
  project's own (MIT). No WaniKani, Koohii or Kanji alive content.
- Colors only from `MaterialTheme.colorScheme`; every new string in all 8 interface languages.
- A new study setting follows the usual pattern (`STUDY_SETTING_KEYS`, `StudySettings`, the
  assistant's `AiSetting`), so Reset all settings resets it.

## Proposed approach / plan

1. Build the data files with the script; write `ja-radicals.txt` by hand.
2. `data/JapaneseKanji.kt`: parsers, `KanjiDictionary` (lazy maps; stories per language with
   English fallback), part naming (`kanjiPartMeaning`), `MyKanjiStories`.
3. `ui/KanjiDetails.kt`: `KanjiSection`, `KanjiPage`, stroke drawing with Compose `PathParser` and
   `PathMeasure` for the partial stroke.
4. `WordLearningSheet.kt`: `MeaningPanel`, the Kanji section and the in-card page.
5. Setting, assistant setting, drawer link, strings, licences.
6. Stories for the 2,136 jōyō kanji, checked by script: each uses only the kanji and the parts
   KanjiVG names inside it, contains its keyword, stays under 230 characters, and the Vietnamese one
   names a Hán Việt reading of the kanji in capitals.

## Acceptance criteria

- [x] Every jōyō kanji has stroke paths matching its stroke count, and both stories
  (`JapaneseKanjiTest.everyJoyoKanjiHasStrokesPartsAndBothStories`).
- [x] Parts are named by their radical or their own keyword; 月 from 肉 is "meat", 儿 is "legs".
- [x] Own stories are saved per kanji and removed when blank.
- [x] A Japanese word card shows the Meaning box and the 葉 row; the 葉 page shows DIỆP, the 世
  part, a story with DIỆP, Play, and saves the learner's own story (`WordLearningDialogTest`).
- [x] Settings → Study tools → Kanji details turns the section off (`StudyToolsUiTest`); the
  assistant reads and changes `kanji_details`.
- [ ] Owner's phone test of the card, the kanji page and the drawer link.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: new `JapaneseKanjiTest`, updated settings tests pass | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | Cloud container and PR CI |
| Managed-device/emulator | `WordLearningDialogTest`, `StudyToolsUiTest` pass with screenshots | PR CI (no KVM in the cloud) |
| Physical-device/manual | Owner opens a Japanese word, a kanji page, plays strokes, writes a story, taps the GitHub link | Owner's phone, preview build |
| Live YouTube | Not applicable: no caption or network change | |
| Performance (baseline/target) | Opening a kanji page reads one line of the stroke file off the main thread | Code review |
| Documentation/process | Spec, AGENTS.md note, notices | This PR |

## Risks / edge cases

- A few KANJIDIC2 Hán Việt readings may still be the reading of another form; the script's fix list
  is where corrections go.
- Stories are written to be memorable, not etymology; they say "Grass 艹 sprouts over the world's 世
  trees 木", not the kanji's historical origin.
- APK size grows by about 1.5 MB (mostly stroke paths).

## Release intent

`release:patch` (the repository default; a new on-device feature with no migration).

## Implementation result

As planned. Story language follows the word's meaning language or the interface language
(Vietnamese for either), and Hán Việt shows exactly when Vietnamese stories do.

## Validation result

Local results and PR CI are recorded in the PR.

## Follow-up: hiding the Kanji, Characters and Grammar sections (owner request 2026-10-24)

The Kanji (Japanese), Characters (Chinese) and Grammar sections can be long, so each heading
now has an arrow (▾ open, ▸ hidden). A tap on the heading hides or shows the section; while
hidden, the heading shows how many kanji, characters or grammar points it holds. Each section
remembers its own choice for every later word card, also after a restart
(`word_card_kanji_open`, `word_card_hanzi_open`, `word_card_grammar_open` in the app
preferences, open by default, cleared by Reset all settings). The "Explain the grammar with AI"
button stays visible. Code: `ui/WordCardSection.kt`.

Acceptance criteria:

- A tap on a heading hides its rows and shows the count; another tap shows them again.
- A new word card opens each section the way the user last left it.
- Tests: `GrammarExplanationsUiTest.headingHidesThePointsAndTheChoiceIsRemembered`, the kanji and
  Chinese character checks in `WordLearningDialogTest`, and the reset key list in
  `SubtitleHighlightTest`.
