# Chinese character details

## Status

Implemented (owner request 2026-10-24 in the "Chinese character details" project thread; after the
research below the owner picked option A, "Bundle Chinese, as kanji now"); waiting for the owner's
phone test.

## Context / problem

Owner: "now i want you to do the same thing like you did with Japanese kanji, but now for Chinese
and Chinese characters. Research how other app and projects done it successfully. should we only
download that part in Chinese and Japanese when users really use it?"

The kanji pages ([spec](2026-10-24-word-meaning-and-kanji-details.md)) only cover Japanese words.
Chinese words had no per-character help.

Research (summarised in the thread's Claude Doc): Pleco, Hanzi Writer, Skritter, Outlier, Du Chinese
and HelloChinese show each character's pinyin, meaning, animated brush-shaped stroke order, its parts
with the meaning part and the sound part, and a memory aid. Open data covers everything but the
stories: Make Me a Hanzi (used by Hanzi Writer) has brush outlines and centre lines for 9,000+
characters (Arphic Public License) and parts with meaning and sound markers (LGPL-3+); ivankra/hsk30
(MIT) has the HSK 3.0 character list; Unihan and KANJIDIC2 give Hán Việt. Shared mnemonics are not
usable (Remembering the Hanzi, Mandarin Blueprint, Outlier and Skritter are copyrighted), so the
stories are written for this app.

On-demand download was weighed: the Chinese data adds about 3.5 MB compressed, while 27 MB of the
35.7 MB APK is the on-device translator's native code for four CPU types. The owner chose to bundle
the data like the kanji data and keep one APK.

## Goals

- Chinese words get a **Characters** section on the word card: one row per character (the
  character, Hán Việt for Vietnamese learners, the pinyin the word uses, keyword). A row opens that
  character's page inside the card:
  - the character with Hán Việt, pinyin, keyword, English meanings, strokes, HSK level and the
    traditional or simplified form;
  - how to write: Play paints each stroke along its centre line inside its brush outline, and
    numbered frames show each stroke;
  - its parts, with the radical, the meaning part and the sound part (with that part's pinyin);
  - common HSK words with the character;
  - how to remember: a ready-made story for every HSK 3.0 character in English and Vietnamese,
    the learner's own story (saved on the phone) and "Another story with AI" when the assistant is on.
- Traditional forms of HSK characters (葉 for 叶) get pages too, with the simplified form's story.
- The existing switch, renamed **Character details** (Settings → Study tools), covers kanji and
  Chinese characters; the assistant knows about both.
- Works offline with no setup.

## Non-goals

- On-demand download or per-CPU APKs (offered, not picked).
- Handwriting practice or stroke checking, spaced repetition, character search.
- Stories for characters outside HSK 3.0 (their pages show everything but the bundled story).

## User-visible behavior

- Chinese word (word language zh, zh-Hans, zh-Hant …) with Han characters: below the word details,
  "Characters" and one card per character. Tapping one replaces the card's content with that
  character's page and a "Back to 树叶" button.
- The pinyin shown first is the one the word uses (了 in 好了 is le), when the word's reading splits
  one syllable per character.
- Vietnamese learners see Hán Việt in capitals (DIỆP), Vietnamese keywords, parts named with their
  bộ or Hán Việt, and Vietnamese stories that name the Hán Việt reading, usually with a common word
  ("DIỆP như "diệp lục""). Everyone else sees English keywords and stories.
- Every story builds the character only from the parts the page lists and gives the pinyin of the
  part that carries the sound ("A woman 女 with a horse 马 (mǎ) for the sound: mā, mom.").
- "Your own story" is saved in `files/kanji-stories/mine-zh.txt`, apart from the kanji stories.
- With Character details off, neither section shows.

## Technical constraints / invariants

- Data files only, in `app/src/main/learner-data/` (copied into `assets/learner-data/` by
  `LearnerLanguageData`), read with `LearnerData.open` on a background thread:
  `zh-hanzi.txt` (4,627 characters and parts, 358 KB), `zh-hanzi-strokes.txt` (7.4 MB, 3.1 MB
  compressed; scanned line by line for one character, never held in memory), `zh-hanviet.txt`,
  `zh-hanzi-stories-en.txt`, `zh-hanzi-stories-vi.txt`. Simplified radical forms (讠, 钅, 鸟 …) were
  added to `ja-radicals.txt`.
- `tools/hanzi/build_hanzi_data.py` rebuilds the first three from pinned commits of Make Me a Hanzi
  and hsk30 plus Unihan 18.0 and KANJIDIC2. Hán Việt corrections go in its `HAN_VIET_FIXES`;
  readings are written in current spelling (Hóa, Thúy).
- Licences: the stroke file keeps Make Me a Hanzi's Arphic change notice; `zh-hanzi.txt` stays
  LGPL-3+ and `zh-hanviet.txt` CC BY-SA 4.0. Full texts are in `assets/licenses/hanzi.txt` (shown on
  the licence screen) and credits in `THIRD_PARTY_NOTICES.md`. Stories are the project's own (MIT).
- Colors only from `MaterialTheme.colorScheme`; every new string in all 8 interface languages.

## Proposed approach / plan

1. Build the data with the script; check Hán Việt by hand while writing stories.
2. `data/ChineseCharacters.kt`: parsers, `HanziDictionary`, `hanziPartMeaning`, `pinyinInWord`,
   `myHanziStories`.
3. `ui/HanziDetails.kt` (`HanziSection`, `HanziPage`, brush drawing), sharing the writing, story
   and own-story pieces with `ui/KanjiDetails.kt`.
4. Word card, setting text, assistant guide and context, strings, licences.
5. Stories: 812 kanji stories reused where the kanji and the simplified character share their parts,
   meaning and Hán Việt; 2,188 new ones. Each is checked by script and by `ChineseCharactersTest`.

## Acceptance criteria

- [x] Every HSK 3.0 character has strokes matching its stroke count, centre lines, and both stories
  that use only the character and its parts; Vietnamese stories name the Hán Việt reading
  (`ChineseCharactersTest.everyHskCharacterHasStrokesAndBothStoriesFromItsOwnParts`).
- [x] Parts are named by their radical or their own keyword, with the sound part's pinyin (讠 words,
  马 mǎ); a traditional form borrows its simplified form's story.
- [x] A Chinese word card shows 树 and 叶; the 叶 page shows DIỆP, yè, the 口 part, a story with DIỆP,
  Play, and saves the learner's own story (`WordLearningDialogTest`).
- [ ] Owner's phone test of a Chinese word card and character page.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: new `ChineseCharactersTest` and existing tests pass | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | Cloud container and PR CI |
| Managed-device/emulator | `WordLearningDialogTest.chineseWordShowsEachCharactersPage` passes with screenshots | PR CI (no KVM in the cloud) |
| Physical-device/manual | Owner opens a Chinese word, a character page, plays strokes, reads the story | Owner's phone, preview build |
| Live YouTube | Not applicable: no caption or network change | |
| Performance (baseline/target) | Opening a page reads one line of the stroke file off the main thread | Code review |
| Documentation/process | Spec, AGENTS.md note, notices | This PR |

## Risks / edge cases

- Some Hán Việt readings may still be another form's reading; fixes go in the script.
- Stories are written to be memorable, not etymology.
- APK size grows by about 3.5 MB (mostly stroke outlines).

## Release intent

`release:patch` (the repository default; a new on-device feature with no migration).

## Implementation result

As planned.

## Validation result

Local results and PR CI are recorded in the PR.
