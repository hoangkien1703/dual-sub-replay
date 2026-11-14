#!/usr/bin/env python3
"""Builds the Spanish learner data in app/src/main/learner-data/ from Wiktionary and a subtitle word list.

Usage:
  python3 tools/spanish/build_spanish_data.py kaikki.org-dictionary-Spanish.jsonl[.gz] es_50k.txt app/src/main/learner-data

Inputs (both CC BY-SA 4.0, so the output files are CC BY-SA 4.0 too; credited in THIRD_PARTY_NOTICES.md):
- Wiktextract's Spanish dictionary from English Wiktionary, https://kaikki.org/dictionary/Spanish/
- hermitdave/FrequencyWords, OpenSubtitles 2018 Spanish top 50,000 words (content/2018/es/es_50k.txt)

Outputs:
- es-verbs.txt: one verb per line, most common first (by how often its forms appear in subtitles). A
  verb that conjugates like the regular -ar, -er or -ir model is only its infinitive; otherwise the
  line lists the cells that differ from that model (see `encode_verb`). The app's
  `SpanishConjugation.kt` holds the same regular endings and reads the file back.
- es-words.txt: nouns (n), adjectives (a), determiners or pronouns (d), prepositions (p),
  conjunctions (c), adverbs (r) and interjections (i) seen in subtitles, with the
  plural and feminine forms the app's rules can't find (`=form letter lemma`).
"""

import gzip
import json
import re
import sys
import unicodedata
from collections import defaultdict

PERSONS = "123456"
# Regular endings: tense letter -> class -> endings for persons 1-6 and vos (v). F and C go on the
# infinitive, every other tense on the infinitive minus -ar/-er/-ir. Keep in sync with SpanishConjugation.kt.
ENDINGS = {
    "P": {"ar": "o as a amos áis an ás", "er": "o es e emos éis en és", "ir": "o es e imos ís en ís"},
    "I": {"ar": "é aste ó amos asteis aron -", "er": "í iste ió imos isteis ieron -", "ir": "í iste ió imos isteis ieron -"},
    "M": {"ar": "aba abas aba ábamos abais aban -", "er": "ía ías ía íamos íais ían -", "ir": "ía ías ía íamos íais ían -"},
    "F": {c: "é ás á emos éis án -" for c in ("ar", "er", "ir")},
    "C": {c: "ía ías ía íamos íais ían -" for c in ("ar", "er", "ir")},
    "S": {"ar": "e es e emos éis en és", "er": "a as a amos áis an ás", "ir": "a as a amos áis an ás"},
    "R": {"ar": "ara aras ara áramos arais aran -", "er": "iera ieras iera iéramos ierais ieran -", "ir": "iera ieras iera iéramos ierais ieran -"},
    "E": {"ar": "ase ases ase ásemos aseis asen -", "er": "iese ieses iese iésemos ieseis iesen -", "ir": "iese ieses iese iésemos ieseis iesen -"},
    "A": {"ar": "- a - - ad - á", "er": "- e - - ed - é", "ir": "- e - - id - í"},
}
NON_FINITE = {"G": {"ar": "ando", "er": "iendo", "ir": "iendo"}, "D": {"ar": "ado", "er": "ido", "ir": "ido"}}
TENSES = "PIMFCSREA"
CELL_PERSONS = PERSONS + "v"


def verb_class(infinitive):
    return {"ar": "ar", "er": "er", "ir": "ir", "ír": "ir"}[infinitive[-2:]]


def stem_for(infinitive, tense):
    return infinitive if tense in "FC" else infinitive[:-2]


def ending(infinitive, tense, person):
    if tense in NON_FINITE:
        return NON_FINITE[tense][verb_class(infinitive)]
    value = ENDINGS[tense][verb_class(infinitive)].split()[CELL_PERSONS.index(person)]
    return None if value == "-" else value


def regular_cells(infinitive):
    cells = {}
    for tense in TENSES:
        for person in CELL_PERSONS:
            end = ending(infinitive, tense, person)
            if end is not None:
                cells[tense + person] = [stem_for(infinitive, tense) + end]
    for tense in NON_FINITE:
        cells[tense] = [infinitive[:-2] + ending(infinitive, tense, "")]
    return cells


CLITICS = {"me", "te", "se", "nos", "os"}


def cell_of(tags):
    """The cell code (P1, Av, G, D…) of a Wiktionary form's tags, or None for forms the app doesn't use."""
    tags = set(tags)
    if tags & {"combined-form", "table-tags", "inflection-template", "class", "negative", "formal"}:
        return None
    if tags == {"gerund"}:
        return "G"
    if tags == {"masculine", "participle", "past", "singular"}:
        return "D"
    if "future" in tags and "subjunctive" in tags:
        return None
    if "imperative" in tags:
        if "vos-form" in tags:
            return "Av"
        if tags >= {"second-person", "singular"}:
            return "A2"
        if tags >= {"second-person", "plural"} and "third-person" not in tags:
            return "A5"
        return None
    if "subjunctive" in tags:
        tense = "E" if "imperfect-se" in tags else "R" if "imperfect" in tags else "S" if "present" in tags else None
    elif "conditional" in tags:
        tense = "C"
    elif "indicative" in tags:
        tense = next((t for key, t in (("present", "P"), ("preterite", "I"), ("imperfect", "M"), ("future", "F")) if key in tags), None)
    else:
        return None
    if tense is None:
        return None
    if "vos-form" in tags:
        return tense + "v"
    number = "singular" if "singular" in tags else "plural" if "plural" in tags else None
    person = next((p for p in ("first-person", "second-person", "third-person") if p in tags), None)
    if number is None or person is None:
        return None
    index = ("first-person", "second-person", "third-person").index(person) + (0 if number == "singular" else 3)
    return tense + PERSONS[index]


def wiktionary_cells(forms):
    cells = defaultdict(list)
    for form, tags in forms:
        cell = cell_of(tags)
        if cell is None or not form or form == "-":
            continue
        words = form.split()
        if len(words) > 1:
            if all(w in CLITICS for w in words[:-1]):
                words = words[-1:]
            else:
                continue
        word = words[0]
        if word not in cells[cell]:
            cells[cell].append(word)
    # Tables of pronominal verbs add pásate, pasaos, pasándose next to pasa, pasad, pasando: leave those out.
    for cell in ("A2", "A5", "Av", "G"):
        found = cells.get(cell, [])
        plain = {strip_accents(f) for f in found} | {strip_accents(f[:-1]) for f in found if f.endswith("d")}
        kept = [f for f in found if not any(f.endswith(c) and strip_accents(f[: -len(c)]) in plain for c in ("se", "te", "os"))]
        if kept:
            cells[cell] = kept
        else:
            cells.pop(cell, None)
    return cells


def encode_verb(infinitive, cells):
    """The verb's line: `infinitive` then, per tense, `P1236=piens` (stem + regular endings for those
    persons) or `P1:soy` / `D:impreso,imprimido` (the forms themselves)."""
    regular = regular_cells(infinitive)
    parts = []
    for tense in TENSES + "GD":
        persons = CELL_PERSONS if tense in TENSES else [""]
        groups = defaultdict(str)
        literals = []
        for person in persons:
            cell = tense + person
            found = cells.get(cell)
            if not found or cell not in regular or found == regular[cell]:
                continue
            end = ending(infinitive, tense, person) if tense in TENSES else None
            if len(found) == 1 and end and found[0].endswith(end) and len(found[0]) > len(end):
                groups[found[0][: -len(end)]] += person
            else:
                literals.append(f"{cell}:{','.join(found)}")
        parts += [f"{tense}{persons_}={stem}" for stem, persons_ in groups.items()] + literals
    return " ".join([infinitive] + parts)


def decode_verb(line):
    words = line.split()
    lemma = words[0]
    infinitive = lemma[:-2] if lemma.endswith("se") and lemma[-4:-2] in ("ar", "er", "ir", "ír") else lemma
    cells = regular_cells(infinitive)
    for part in words[1:]:
        tense = part[0]
        if "=" in part:
            persons, stem = part[1:].split("=")
            for person in persons:
                cells[tense + person] = [stem + ending(infinitive, tense, person)]
        else:
            cell, forms = part.split(":")
            cells[cell] = forms.split(",")
    return lemma, cells


def strip_accents(word):
    return "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn" or c == "̃")


def read_entries(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            entry = json.loads(line)
            if entry.get("lang_code", "es") != "es":
                continue
            senses = entry.get("senses")
            if senses is not None:
                entry["form_of"] = sorted({f.get("word") for s in senses for f in (s.get("form_of") or []) + (s.get("alt_of") or []) if f.get("word")})
                entry["forms"] = [[f.get("form"), f.get("tags") or []] for f in entry.get("forms") or []]
                entry["tags"] = sorted({t for s in senses for t in (s.get("tags") or [])})
                entry["glosses"] = [g for s in senses[:2] for g in (s.get("glosses") or [])[:1]]
            yield entry


WORD = re.compile(r"^[a-záéíóúüñ]+$")
# n noun, a adjective, d determiner or pronoun, p preposition, c conjunction, r adverb, i interjection.
WORD_LETTERS = {
    "noun": "n", "adj": "a", "det": "d", "article": "d", "pron": "d", "num": "d",
    "prep": "p", "contraction": "p", "conj": "c", "adv": "r", "intj": "i", "particle": "i",
}
SKIPPED_FORM_TAGS = {"diminutive", "augmentative", "superlative", "apocopic", "abbreviation", "alt-of", "misspelling", "gender-neutral"}


def main(kaikki, frequency_path, out_dir):
    frequency = {}
    with open(frequency_path, encoding="utf-8") as source:
        for line in source:
            word, count = line.split()
            frequency[word] = int(count)
    verbs = {}
    words = defaultdict(set)
    word_forms = defaultdict(set)
    for entry in read_entries(kaikki):
        word, pos = entry.get("word") or "", entry.get("pos")
        if not WORD.match(word):
            continue
        if pos == "verb" and entry.get("forms") and word[-2:] in ("ar", "er", "ir", "ír") or (pos == "verb" and word[-4:-2] in ("ar", "er", "ir", "ír") and word.endswith("se") and entry.get("forms")):
            cells = wiktionary_cells(entry["forms"])
            # Old spellings such as her (haber) would add readings to common words.
            tags = set(entry.get("tags") or [])
            if "misspelling" in tags or (tags & {"obsolete", "archaic", "alt-of"} and len(entry.get("glosses") or entry.get("senses") or []) <= 1):
                continue
            if "P1" in cells or "P3" in cells:
                verbs.setdefault(word, cells)
        elif pos in WORD_LETTERS:
            letter = WORD_LETTERS[pos]
            tags = set(entry.get("tags") or [])
            if entry.get("form_of"):
                # Plural and feminine forms (casas → casa, esta → este), not diminutives or short forms.
                if tags & {"plural", "feminine"} and not tags & SKIPPED_FORM_TAGS:
                    for lemma in entry["form_of"]:
                        if WORD.match(lemma) and lemma != word:
                            word_forms[lemma].add((word, letter))
            elif not tags & {"gender-neutral", "neologism", "misspelling"} and not word.endswith("x"):
                words[word].add(letter)
    # A pronominal verb (llamarse) is left out when its plain verb (llamar) is there.
    verbs = {v: c for v, c in verbs.items() if not (v.endswith("se") and v[:-2] in verbs)}
    # How common a verb is: its infinitive, and its gerund and participle unless another verb shares
    # them (apuesto: apostar, not aponer), in subtitles, plus half of its
    # other forms that are no other verb's form and no noun or adjective (nada, uno and creo would
    # otherwise make nadar, unir and crear look common). The forms count so that soler (suele,
    # solía) comes before solar, whose infinitive is also an adjective.
    owners = defaultdict(int)
    for cells in verbs.values():
        for f in {f for fs in cells.values() for f in fs}:
            owners[f] += 1
    ranked = []
    for verb, cells in verbs.items():
        infinitive = verb[:-2] if verb.endswith("se") and verb[-4:-2] in ("ar", "er", "ir", "ír") else verb
        plain = {infinitive} | set(cells.get("G", [])) | set(cells.get("D", []))
        others = {f for fs in cells.values() for f in fs} - plain
        count = sum(frequency.get(f, 0) for f in plain if f == infinitive or owners[f] == 1) + sum(frequency.get(f, 0) for f in others if owners[f] == 1 and f not in words) // 2
        if count:
            ranked.append((-count, verb, encode_verb(verb, cells) if verb == infinitive else encode_verb(infinitive, cells).replace(infinitive, verb, 1)))
    ranked.sort()
    lines = [line for _, _, line in ranked]
    for line, (_, verb, _) in zip(lines, ranked):
        lemma, decoded = decode_verb(line)
        cells = verbs[verb]
        for cell, found in cells.items():
            assert decoded.get(cell) == found, (verb, cell, decoded.get(cell), found)
    write(out_dir + "/es-verbs.txt", VERB_HEADER, lines)
    print("verbs", len(lines), "irregular", sum(1 for l in lines if " " in l), "bytes", sum(len(l.encode()) + 1 for l in lines))
    write_words(out_dir + "/es-words.txt", words, word_forms, frequency)


def write(path, header, lines):
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write(header)
        for line in lines:
            out.write(line + "\n")


VERB_HEADER = """# Spanish verbs for DualSub Replay, made by tools/spanish/build_spanish_data.py.
# Data: Wiktionary (via Wiktextract, kaikki.org) and hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0.
# This file is licensed CC BY-SA 4.0. Most common first. Format: see the script.
"""

WORD_HEADER = """# Spanish nouns (n), adjectives (a), determiners or pronouns (d), prepositions (p), conjunctions (c),
# adverbs (r) and interjections (i) for DualSub Replay, made by
# tools/spanish/build_spanish_data.py. Data: Wiktionary (via Wiktextract, kaikki.org) and
# hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0. This file is licensed CC BY-SA 4.0.
# `=form letter lemma` lines are plural or feminine forms the app's rules don't find.
"""


def write_words(path, words, word_forms, frequency):
    kept = {}
    for lemma, letters in words.items():
        forms = {f for f, _ in word_forms.get(lemma, ())}
        if frequency.get(lemma) or any(frequency.get(f) for f in forms):
            kept[lemma] = "".join(sorted(letters))
    import spanish_rules
    lemmas_of = defaultdict(set)
    for lemma in kept:
        for form, letter in word_forms.get(lemma, ()):
            if letter in kept[lemma] and frequency.get(form):
                lemmas_of[(form, letter)].add(lemma)
    exceptions = []
    for (form, letter), lemmas in lemmas_of.items():
        found = spanish_rules.lemma_of(form, letter, kept)
        # A form that is a word of its own (ama, the owner) keeps no dictionary form.
        if found in lemmas or letter in kept.get(form, ""):
            continue
        lemmas = {lemma for lemma in lemmas if frequency.get(lemma)}
        if not lemmas:
            continue
        best = max(sorted(lemmas), key=lambda lemma: frequency.get(lemma, 0))
        exceptions.append(f"={form} {letter} {best}")
    lines = [f"{lemma} {letters}" for lemma, letters in sorted(kept.items())] + sorted(set(exceptions))
    write(path, WORD_HEADER, lines)
    print("words", len(kept), "exceptions", len(set(exceptions)), "bytes", sum(len(l.encode()) + 1 for l in lines))


if __name__ == "__main__":
    sys.path.insert(0, __import__("os").path.dirname(__file__))
    main(*sys.argv[1:4])
