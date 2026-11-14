# Spanish learner help

## Status

Approved for implementation. On 2026-11-13 the owner asked in the project thread for Spanish
learners to get the same help as English and Japanese learners ("grammar, etc."), said he does
not know Spanish, left the scope to Claude's recommendation, and authorized merging the PR once
Claude is confident in it (CI green). No version was named, so the release is the next minor
version (v1.9.0 after v1.8.38). Publishing outside the normal release workflow is not authorized.

## Context / problem

Japanese, Chinese and English lines are tagged in context: each word is colored by its part of
speech, the word card shows the dictionary form, and grammar cards with levels (JLPT, HSK, CEFR)
and the "Explain the grammar with AI" button appear ([English spec](2026-10-18-english-learner-help.md)).
Spanish lines use the generic spaced-language heuristic: most words are "Noun", a tap on
"fuimos" shows nothing about ir or ser, and there are no grammar cards. Spanish is one of the most
learned languages and a common caption language on YouTube.

### Research (2026-11-13)

- **What learners need.** Spanish textbooks and the Instituto Cervantes curriculum (*Plan
  curricular*, PCIC) put verb conjugation at the centre: about 50 forms per verb, many irregular
  (fui = ir or ser, pongo, dije), the preterite / imperfect split, the subjunctive and its
  triggers (quiero que…, ojalá, cuando + subjunctive), ser / estar, pronouns written onto verbs
  (dámelo), se in its several uses, and por / para. A reading tool helps most by naming the
  dictionary form, tense and person of each verb and pointing at these patterns.
- **Parts of speech.** Apache OpenNLP publishes a Spanish maxent model trained on Universal
  Dependencies Spanish GSD (`opennlp-models-pos-es:1.3.0`, Apache-2.0, 1.7 MB). Its feature
  generator is the same file as the English model's, so the app's library-free tagger runs it
  unchanged (renamed `OpenNlpPosTagger`). UDPipe and spaCy were ruled out as for English (licence,
  size).
- **Dictionary forms and conjugations.** OpenNLP's Spanish lemmatizer model was tried and gave
  wrong lemmas on common tú forms (vayas → vaya, hablaste kept as it is). Wiktionary has full
  conjugation tables and noun / adjective entries for Spanish under CC BY-SA 4.0; Wiktextract's
  kaikki.org dump makes them machine-readable. A build script reduces them to the verbs common in
  subtitles (by the OpenSubtitles frequency list of hermitdave/FrequencyWords, CC BY-SA 4.0) with
  only their irregular stems written out, and to nouns, adjectives and determiners with their
  irregular plurals and feminines. Both output files are CC BY-SA 4.0 and credited.
- **Levels.** CEFR (A1–C2) is the scale Spanish learners know (DELE exams, PCIC). No free list
  of PCIC grammar levels exists in machine-readable form, so each point was placed by hand at the
  level the PCIC inventory and common textbooks (Aula, Nuevo Prisma, ¡Vamos!) first teach it:
  present, ser / estar, gustar, ir a A1; preterite, imperfect, present perfect, commands A2;
  present subjunctive and its triggers B1; imperfect subjunctive and unreal si B2; si + hubiera
  C1. Word levels are not shown: no free Spanish word-level list was found.

Data used:

| Data | Source | Licence | Size in APK assets (before compression) |
| --- | --- | --- | --- |
| Spanish part-of-speech model (UD Spanish GSD, maxent) | `org.apache.opennlp:opennlp-models-pos-es:1.3.0` (Maven Central, build time) | Apache-2.0 | ~1.7 MB |
| 2,477 verbs with their irregular forms (`es-verbs.txt`) | Wiktionary via Wiktextract (kaikki.org), ranked by FrequencyWords es_50k | CC BY-SA 4.0 | ~75 KB |
| 12,944 nouns, adjectives and determiners with irregular plurals and feminines (`es-words.txt`) | Wiktionary via Wiktextract (kaikki.org) | CC BY-SA 4.0 | ~143 KB |

## Goals

- Spanish lines (caption language `es*`) are tagged in context, so each word is colored by its
  part of speech in this sentence and tappable.
- The word card shows the dictionary form (fuimos → ir, canciones → canción, bonitas → bonito),
  and for a verb its form and person ("Verb form: Preterite · nosotros") and the pronouns written
  onto it ("Pronouns attached: me, lo").
- Grammar cards for Spanish with CEFR badges: 103 catalogue patterns and 31 rule-based meanings
  (tenses and moods, commands, ser / estar, pronouns and se, si / cuando / aunque clauses), each
  explained in all 9 interface languages, plus the existing AI button.
- Release as **v1.9.0**.

## Non-goals

- No word levels for Spanish, no definitions (the meaning keeps using the translator), no
  diminutives (casita → casa) and no vosotros / vos-only special cases beyond their own points.
- No settings: like English, Spanish help appears by itself for Spanish lines, and nothing uses
  AI unless the user presses the existing AI button.
- Japanese, Chinese and English behavior is unchanged.

## User-visible behavior

- **Before:** "Ayer fuimos al cine" colors every word as a noun; a tap on "fuimos" shows only
  the translation.
  **After:** "fuimos" is a verb with "Dictionary form: ir", "Verb form: Preterite · nosotros" and
  a "pretérito indefinido · A2" grammar card; "al" shows "al / del · A1" (a + el).
- "Dámelo" shows "Dictionary form: dar", "Verb form: Command · tú", "Pronouns attached: me, lo"
  and the "dímelo, hacerlo" card.

## Technical constraints / invariants

- User-visible text lives in `strings_grammar_es.xml` in every `values-*` folder.
- Every Spanish point has its own example sentence in `SpanishGrammarCatalogueTest` that must find
  it; unit tests stay plain JUnit4 and read the data from the classpath.
- The data files are generated by `tools/spanish/build_spanish_data.py`, never hand-edited;
  `tools/spanish/spanish_rules.py` and `SpanishLexicon.kt` apply the same plural / feminine rules.
- Data loads on a background thread on first use; `tokenizerRevision()` changes when it is
  ready. No network access is added.

## Proposed approach / plan

1. `OpenNlpPosTagger` (the English tagger, renamed) loads the Spanish model too; Gradle adds the
   model to `learnerData` as `es-pos.zip`.
2. `tools/spanish/build_spanish_data.py` writes `es-verbs.txt` (regular endings are implied;
   irregular stems or whole forms are listed per tense cell) and `es-words.txt`.
3. `SpanishMorphology`: tags, then splits del / al and attached pronouns, retags words the model
   guessed as nouns or names when they can only be verb forms, and orders a verb's readings by
   context (fue a → ir, fue un → ser, ¡Habla! → command).
4. `SpanishGrammarCatalogue` + `SpanishGrammar` rules, `GrammarLanguage.SPANISH`, word-card lines.
5. Tests, coverage on 3,000 Tatoeba lines, notices, licence screen, README, tech stack, AGENTS.md.

## Acceptance criteria

- [ ] Every Spanish catalogue point and rule meaning is found in its own example sentence
  (`SpanishGrammarCatalogueTest`), the catalogue covers A1–B2, and tense, si and pronoun cases
  are told apart.
- [ ] Tags equal real OpenNLP's on named sentences; conjugations, dictionary forms and attached
  pronouns are right for named words (`SpanishMorphologyTest`).
- [ ] The analyzer loads from the APK on a device (`LearnerDataDeviceTest`), and the word card
  shows the verb form line (UI test with a saved screenshot).
- [ ] Japanese, Chinese and English tests still pass unchanged.
- [ ] `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` pass locally and both
  CI jobs pass on the final head.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` passes | Local JVM, CI |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug` pass | Local, CI |
| Managed-device/emulator | `pixel2Api36DebugAndroidTest` passes, including `LearnerDataDeviceTest` and the word-card test | CI |
| Physical-device/manual | Open a Spanish video, tap verbs, nouns and pronouns, check the card | Owner's phone, preview APK |
| Live YouTube | Not testable from the cloud (YouTube blocks cloud addresses) | Owner's preview test |
| Coverage/performance | 3,000 random Tatoeba Spanish sentences: verbs with a reading, lines with a point, time and heap, recorded below | Local JVM (scratch, not committed) |

## Risks / edge cases

- The tagger makes mistakes on short, informal subtitle lines; a word it calls a noun that could
  only be a verb form is retagged, but a word that is both (como, vino, sobre) keeps the model's
  tag.
- Many forms have several readings (hablamos: present or preterite; fue: ir or ser); the card
  lists up to three, likeliest first, instead of guessing one.
- Levels are a careful judgment from the PCIC and textbooks, not a published per-point list; the
  spec records this.
- The APK grows by about 1.9 MB of assets before compression.

## Release intent

`release:minor` label: the owner named no version and this is a new learning language, so the
expected version is **v1.9.0** (the latest release is v1.8.38).

## Implementation result

- `OpenNlpPosTagger` (renamed from `EnglishPosTagger`, unchanged logic) loads `es-pos.zip`.
- `tools/spanish/build_spanish_data.py` writes 2,467 verbs (805 with irregular cells, 75 KB) and
  13,574 words with 54 irregular plural / feminine exceptions (151 KB). Besides nouns (n),
  adjectives (a) and determiners or pronouns (d), the words list marks other parts of speech (f:
  para, como, sobre) so the analyzer knows when a word the model calls a preposition or adverb
  really is a verb (comí was tagged as a preposition). Verbs are ranked by their infinitive and
  the forms only they have, so soler comes before solar (suelo) and apostar before aponer.
- `SpanishMorphology`: del / al split, up to two attached pronouns split (accent removed, +s for
  nos and +d for os, vámonos special-cased), guessed tags retagged to VERB, readings reordered by
  context (ir / ser, ir / ver, commands only at the start of a sentence).
- Grammar: 102 catalogue patterns (A1–B2) and 8 rules for 30 meanings, including C1 for
  si + hubiera + participio; texts in all 9 interface languages. Added after the first coverage
  run: subject pronouns, mí / ti, todo, cada, algo / alguien, quantity words, como, ni, el mío,
  and the infinitive.
- Word card: "Verb form: Preterite · nosotros" (up to three readings of the likeliest verb) and
  "Pronouns attached: me, lo", in `ui/SpanishVerbForm.kt`.
- No setting and nothing that uses AI: the help appears by itself for `es*` lines.

## Validation result

Local (Linux, JDK 21):

- `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`:
  see the PR's checks for the final head; both CI jobs must pass before merge.
- Tags equal OpenNLP 2.5.4's own output on five named sentences (`SpanishMorphologyTest`).
- Coverage on 3,000 random Tatoeba Spanish sentences (seed 20261113): 98.1% of verbs get a
  dictionary form, tense and person; 98.5% of grammar words (verbs, prepositions, articles,
  pronouns, conjunctions) belong to a grammar point; 93.1% of lines have every grammar word
  explained; 4.5 points per line. Before the added points it was 90.6% and 65.8%.
- Performance on the JVM: the analyzer loads in about 60 ms and holds about 17 MB; 3,000 lines
  are analyzed and matched in about 0.8 s.
- Known limits: a word that is both a verb form and another word (como, vino, sobre) keeps the
  model's tag, so "Todos los días como fruta" reads como as "like".
- Not verified here: the managed-device tests (CI), a physical phone and live YouTube (the
  owner's preview test).

## Follow-up (2026-11-14, v1.9.1 patch): casual lines

The owner opened a Spanish video and saw no grammar card for "Eh, sí porfa hale, ¡gracias!".
That line has only filler and courtesy words, which had no point, so only the AI button showed.
Two problems were found and fixed:

- Words the model calls verbs that are no verb form (azúcar, ah) were shown as verbs. The words
  list now gives each word its parts of speech (n, a, d, p preposition, c conjunction, r adverb,
  i interjection, instead of one f letter), so such a word takes its listed tag, and a capitalized
  interjection at the start of a line (Eh, Vale) is no longer a name.
- Two A1–A2 points were added: "por favor / gracias" (courtesy words: por favor, porfa, gracias,
  de nada, perdón, lo siento) and "vale / bueno / pues", a rule for conversation words (pues
  anywhere; vale, bueno, venga, mira, oye, eh at the start of a sentence when they stand alone,
  so not ¿Cuánto vale? or Mira la casa; a ver, o sea, en fin, por cierto, es decir).

"Hale" (Spain, "there you go") is slang that neither Wiktionary's lists nor Google Translate
know, so it still has no dictionary form or translation.
