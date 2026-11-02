# Decks in Practice

## Status

Implemented. Owner decision 2026-11-02 in the project thread: build "Decks + auto language decks"
for [issue #184](https://github.com/hoangkien1703/dual-sub-replay/issues/184), picked over a
language filter only and over tags.

## Context / problem

Issue #184: a learner of Arabic wants dialect words and Modern Standard Arabic words apart, and a
second language apart from the first, instead of one big Practice list. Saved words had no deck or
tag; Practice showed every word in one list and practised every due word together.

## Goals

- Saved words are split by language with no setup: every word not put in a deck sits in its
  language's automatic deck ("Arabic", "Japanese").
- Learners can make their own decks (for example "Egyptian Arabic" and "Modern Standard Arabic"),
  which a language filter cannot do because both are `ar`.
- Saving a word stays one tap: the deck last picked for that language is used again.
- Practice opens on the decks, each with its word and due counts, and practises one deck or all.

## Non-goals

- Tags or a word in several decks: one deck per word.
- Sending decks to AnkiDroid / AnkiConnect as subdecks (cards still go to the "DualSub Replay" deck).
- An import option that puts every imported word in a chosen deck.
- AI assistant tools to create, rename or move decks.

## User-visible behavior

- **Word card.** Above Save to vocabulary, a "Deck: Arabic ▾" chip. Its menu lists the word
  language's own deck, the named decks (A to Z) and New deck…, which asks for a name (up to 40
  characters; a name that differs from an existing deck only in letter case reuses that deck).
  Changing the deck of a saved word turns the button back to Save. The deck picked when saving is
  remembered per word language; an already-saved word opens with its own deck.
- **Practice, one deck.** A learner whose words sit in one deck (one language, no named decks) sees
  Practice exactly as before.
- **Practice, several decks.** Practice opens on the deck list (the deck of the newest saved word
  first), each row showing "N words · M due", and a hint that words are grouped by language. Import /
  Export, the all-words summary, Practice due words (every deck) and Search (every deck) stay on top.
  Tapping a deck opens it: a back arrow (or Back) returns to the decks; its summary, Practice due
  words and Search cover that deck only. A named deck's ⋮ menu offers Rename deck (renaming to an
  existing deck merges them) and Delete deck, which asks first and only moves its words back to their
  language decks with their review history.
- **Saved word page.** The same deck chip moves the word to another deck.
- **AI assistant.** Save to vocabulary uses the deck last picked for that language.
- **Backup and Anki text.** The JSON backup keeps each word's deck; old backups import into language
  decks. Anki text gets a last `deck` column, read back on import.

## Technical constraints / invariants

- Words stay in `vocabulary.db` as JSON payloads, so the optional `deck` field needs no database
  migration; older payloads read as language-deck words. Backup format stays version 1 (older app
  versions ignore the extra field).
- The remembered deck per language lives in its own `practice_decks` preferences, apart from the
  settings, so Reset all settings keeps it like saved words.
- User-visible text in `strings_practice.xml` for all 8 interface languages.
- Dialog rules: the name dialog has the X and asks before dropping a changed name; Delete deck is a
  confirmation without an X.

## Proposed approach / plan

1. `data/SavedWord.kt`: `deck: String? = null`; `VocabularyRepository` encodes/decodes it and adds
   `updateWhere` (one transaction, one publish) for rename and delete.
2. `data/PracticeDecks.kt`: `PracticeDeck`, `deckOf`, `cleanDeckName`, `resolveDeckName`,
   `namedDecks`, `deckSummaries` as small `internal` functions.
3. `data/PracticeTransfer.kt`: `deck` TSV column; import validation requires a tidy deck name.
4. `ui/PracticeDecks.kt`: deck chip, name dialog, deck list, open-deck header, delete dialog and the
   remembered-deck preferences.
5. `SavedWordsScreen`, `WordLearningDialog` (optional `deck` / `onDeckChange`), `DualSubApp`,
   `AppViewModel.saveWord` and the assistant's Save to vocabulary use them.

## Acceptance criteria

- [x] A word saved with a deck keeps it across app restarts, backups and Anki text round trips; a
  word saved without one, or from an older backup, sits in its language deck.
- [x] With words in more than one deck, Practice lists the decks with word and due counts; opening a
  deck lists and practises only its words.
- [x] Renaming a deck moves all its words at once; deleting it moves them to their language decks
  and keeps every word and its review history.
- [x] The word card's chip picks a named deck, the language deck or a new deck, and Save stores it.
- [x] A learner with a single deck sees Practice as before (existing `SavedWordsScreenTest`).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest`: `PracticeDecksTest` (grouping, counts, names, backup and TSV round trips, import validation) plus existing tests | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` | Cloud container |
| Managed-device/emulator | `PracticeDecksUiTest` (repository move, deck list, rename, delete, move from word page, word card chip), existing `SavedWordsScreenTest`, `WordLearningDialogTest` | PR CI (no /dev/kvm in the cloud container) |
| Physical-device/manual | Save words in two languages and a named deck, open Practice, rename and delete the deck | Owner's phone with the PR preview APK |
| Live YouTube | Not applicable: no YouTube page change | |

## Risks / edge cases

- A named deck called like a language ("Arabic") shows next to the automatic deck of the same name.
- Importing Anki text with Replace duplicates and no `deck` column moves replaced words back to their
  language decks.
- A remembered deck that no longer exists falls back to the language deck.

## Release intent

`release:patch` (default for an APK change). A new feature like this could justify
`release:minor`; the owner decides.

## Implementation result

As planned. "All words" is the deck list page itself (its summary, Practice due words and Search
cover every deck) rather than an extra row.

## Validation result

Local (cloud container, 2026-11-02): `formatCheck`, `complexityCheck`, `testDebugUnitTest`
(1075 tests, 0 failures, including the 7 `PracticeDecksTest` tests), `lintDebug`, `assembleDebug` and
`assembleDebugAndroidTest` passed. Not run locally: `PracticeDecksUiTest` and the other device tests
(no /dev/kvm; they run in PR CI). Not yet tried on a physical phone. See the PR for the final-head CI run.
